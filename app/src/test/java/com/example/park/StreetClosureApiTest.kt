package com.example.park

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Parsing and paging of the SFMTA street-closures feed (8x25-yybr). Row shapes copied from the live feed (2026-09-23). */
class StreetClosureApiTest {

    private val liveRow = """
        {"objectid":"35303","case_num":"260497","case_name":"PG&E","type":"Special Traffic Permit",
         "cnn":"10404000","street":"PHELPS ST","from_st":"MCKINNON AVE","to_st":"NEWCOMB AVE",
         "veh_imp":"some-lanes-closed",
         "start_utc":"2026-09-15T08:00:00.000","end_utc":"2026-09-16T07:59:00.000",
         "start_dt":"2026-09-15T00:00:00.000","end_dt":"2026-09-15T23:59:00.000",
         "shape":{"type":"LineString","coordinates":[[-122.392978294,37.737966149],[-122.39353684,37.737355701]]}}
    """.trimIndent()

    @Test
    fun parsesALiveRow() {
        val c = parseStreetClosures("[$liveRow]", syncId = 7L).single()
        assertEquals("35303", c.objectId)
        assertEquals("10404000", c.cnn)
        assertEquals("some-lanes-closed", c.vehicleImpact)
        assertFalse(c.isFullClosure)
        assertEquals(2, c.points.size)
        assertEquals(37.737966149, c.points[0].lat, 1e-9) // GeoJSON is [lng, lat]
        assertEquals(7L, c.lastSeenSyncId)
        // Read from start_dt (local midnight), not start_utc, which is an hour late on this row.
        assertEquals(ZonedDateTime.of(2026, 9, 15, 0, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli(), c.startMillis)
    }

    @Test
    fun theLocalColumn_wins_becauseTheUtcColumnIsSometimesAnHourLate() {
        val pdtMidnight = ZonedDateTime.of(2026, 9, 15, 0, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        // The live PG&E row: local midnight in September, but start_utc says 08:00Z (really 07:00Z).
        assertEquals(pdtMidnight, feedMillis(local = "2026-09-15T00:00:00.000", utc = "2026-09-15T08:00:00.000"))
        // Winter (PST, UTC-8): local time must follow DST, not a fixed offset.
        val pstMidnight = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        assertEquals(pstMidnight, feedMillis(local = "2026-12-01T00:00:00.000", utc = null))
        // UTC is only the fallback.
        assertEquals(pdtMidnight, feedMillis(local = null, utc = "2026-09-15T07:00:00.000"))
        assertEquals(pdtMidnight, feedMillis(local = "garbage", utc = "2026-09-15T07:00:00.000"))
        assertNull(feedMillis(local = null, utc = null))
    }

    @Test
    fun rowsThatCantBeMatchedOrTimed_areDropped_butARowWithoutGeometryIsKept() {
        val json = """[
            {"cnn":"1","start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00"},
            {"objectid":"2","start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00"},
            {"objectid":"3","cnn":"1","end_utc":"2026-09-15T09:00:00"},
            {"objectid":"4","cnn":"1","start_utc":"2026-09-15T09:00:00","end_utc":"2026-09-15T08:00:00"},
            {"objectid":"5","cnn":"1","start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00","shape":{"type":"LineString","coordinates":"broken"}},
            {"objectid":"6","cnn":"1","start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00"}
        ]"""
        val kept = parseStreetClosures(json, syncId = null)
        assertEquals(listOf("6"), kept.map { it.objectId })
        assertTrue(kept.single().points.isEmpty())
    }

    @Test
    fun aMissingVehicleImpact_countsAsAFullClosure() {
        val c = parseStreetClosures("""[{"objectid":"1","cnn":"1","start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00"}]""", null).single()
        assertNull(c.vehicleImpact)
        assertTrue(c.isFullClosure)
    }

    @Test
    fun theWhereClause_filtersOnStatusAndTimeOnly() {
        val cutoff = ZonedDateTime.of(2026, 9, 23, 21, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        assertEquals("status = 'Permitted' AND end_utc > '2026-09-24T04:00:00'", closuresWhereClause(cutoff))
    }

    // --- Paging ---

    /** A fake Socrata server where every [junkEvery]th row has no CNN (so the parser drops it). */
    private class FakeFeed(val total: Int, val stopsServingAt: Int = total, val junkEvery: Int = 0) {
        val offsets = mutableListOf<Int>()
        fun page(limit: Int, offset: Int): String {
            offsets += offset
            return (offset until minOf(offset + limit, stopsServingAt)).joinToString(",", "[", "]") { i ->
                val cnn = if (junkEvery > 0 && i % junkEvery == 0) "" else """"cnn":"$i","""
                """{"objectid":"$i",$cnn"start_utc":"2026-09-15T08:00:00","end_utc":"2026-09-15T09:00:00"}"""
            }
        }
    }

    private fun collect(feed: FakeFeed, pageSize: Int = 100, total: () -> Int? = { feed.total }) = runBlocking {
        collectSocrataPages(
            pageSize = pageSize,
            pause = {},
            fetchPage = feed::page,
            parse = { parseStreetClosures(it, 1L) },
            fetchServerTotal = total,
            onPage = {}
        )
    }

    @Test
    fun pagingAdvancesByRawRows_evenWhenTheParserDropsSome() {
        // Every 10th row is junk. Advancing by the parsed count would re-request overlapping offsets (the RPP bug).
        val feed = FakeFeed(total = 250, junkEvery = 10)
        val fetch = collect(feed)
        assertEquals(listOf(0, 100, 200), feed.offsets)
        assertEquals(250, fetch.rawRows)
        assertEquals(225, fetch.keptRows)
        assertTrue(fetch.complete)
        assertNull(pruneSkipReason(existingCount = 240, fetch = fetch))
    }

    @Test
    fun aFetchThatStopsEarly_doesNotPrune() {
        val fetch = collect(FakeFeed(total = 250, stopsServingAt = 150))
        assertTrue(fetch.complete)
        assertEquals(150, fetch.rawRows)
        assertNotNull(pruneSkipReason(existingCount = 150, fetch = fetch))
    }

    @Test
    fun anUnreadableCount_doesNotPrune() {
        val fetch = collect(FakeFeed(total = 50), total = { throw java.io.IOException("HTTP 425") })
        assertNull(fetch.serverTotal)
        assertNotNull(pruneSkipReason(existingCount = 50, fetch = fetch))
    }

    @Test
    fun anExactMultipleOfThePageSize_isConfirmedByAnEmptyPage() {
        val feed = FakeFeed(total = 200)
        val fetch = collect(feed)
        assertEquals(listOf(0, 100, 200), feed.offsets)
        assertEquals(200, fetch.rawRows)
        assertTrue(fetch.complete)
    }
}
