package com.example.park

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the RPP paging loop with a fake ArcGIS server instead of the network.
 *
 * The regression this guards: the live filter returns 6,502 raw rows, two of which are junk (zone "0")
 * that the parser drops. A page holding one of them parsed to fewer rows than the page size, which the
 * loop read as "last page" — so the sync stopped after 2 of 4 pages (3,998 rows) and then pruned the
 * other 2,504 as vanished.
 */
class RppPagingTest {

    /** A fake layer with [total] rows in OBJECTID order; [junkIds] carry zone "0" (dropped by the parser). */
    private class FakeServer(
        val total: Int,
        val junkIds: Set<Int> = emptySet(),
        val maxRecordCount: Int = 10_000,
        /** When false the flag is never sent at all (an older server); otherwise it is true/false per page. */
        val sendsExceededFlag: Boolean = true,
        /** Serves nothing past this many rows while still claiming there is more (a cut-off response). */
        val stopsServingAt: Int = total
    ) {
        val requests = mutableListOf<Pair<Int, Int>>() // (limit, offset)
        val offsets get() = requests.map { it.second }

        fun page(limit: Int, offset: Int): String {
            requests += limit to offset
            val served = minOf(limit, maxRecordCount)
            val ids = (offset + 1..minOf(offset + served, stopsServingAt)).toList()
            val features = ids.joinToString(",") { id ->
                val zone = if (id in junkIds) "0" else "A"
                """{"attributes":{"OBJECTID":$id,"DAYS":"M-F","HRS_BEGIN":800,"HRS_END":1800,"HRLIMIT":2.0,""" +
                    """"RPPAREA1":"$zone","RPPAREA2":null,"RPPAREA3":null},""" +
                    """"geometry":{"paths":[[[-122.4,37.7],[-122.41,37.71]]]}}"""
            }
            val more = offset + ids.size < total
            val flag = if (sendsExceededFlag) ""","exceededTransferLimit":$more""" else ""
            return """{"features":[$features]$flag}"""
        }
    }

    private class Collected(val fetch: FeedFetch, val rows: List<RppZoneRegulation>, val totalCalls: Int)

    private fun collect(
        server: FakeServer,
        pageSize: Int = 2000,
        serverTotal: () -> Int = { server.total }
    ): Collected {
        val rows = mutableListOf<RppZoneRegulation>()
        var totalCalls = 0
        val fetch = runBlocking {
            collectRppPages(
                pageSize = pageSize,
                fetchPage = server::page,
                fetchServerTotal = { totalCalls++; serverTotal() },
                onPage = { rows += it }
            )
        }
        return Collected(fetch, rows, totalCalls)
    }

    @Test
    fun aJunkRowOnAFullPage_doesNotEndPagingEarly() {
        // The live shape: 6,502 raw rows, 2 junk rows in page 2, 2,000 per page.
        val server = FakeServer(total = 6502, junkIds = setOf(2500, 3000))
        val result = collect(server)
        assertEquals(listOf(0, 2000, 4000, 6000), server.offsets)
        assertEquals(6500, result.rows.size)
        assertEquals(6500, result.fetch.keptRows)
        assertEquals(6502, result.fetch.rawRows)
        assertTrue(result.fetch.complete)
        // The server's count includes the junk rows, so the guard must compare RAW rows, not kept ones.
        assertEquals(6502, result.fetch.serverTotal)
        assertNull(pruneSkipReason(existingCount = 6502, fetch = result.fetch))
    }

    /**
     * An in-memory stand-in for the rpp_zone_regulation table (objectId -> lastSeenSyncId), whose delete
     * mirrors RppZoneRegulationDao.deleteNotSeenSince: `lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId`.
     * It is not Room itself (no Robolectric here), but the decide-then-delete path around it is the real one.
     */
    private class FakeTable(initialIds: Iterable<Int>, oldSyncId: Long?) {
        val stamps = initialIds.associate { it.toString() to oldSyncId }.toMutableMap()
        fun deleteNotSeenSince(syncId: Long): Int {
            val doomed = stamps.filterValues { it == null || it < syncId }.keys
            doomed.forEach { stamps.remove(it) }
            return doomed.size
        }
    }

    private class SyncRun(val removed: Int?, val warnings: List<String>)

    /** One full sync against [server]: stamp what the fetch returns, then run the real prune step. */
    private fun syncAndPrune(server: FakeServer, table: FakeTable, syncId: Long, serverTotal: Int = server.total): SyncRun {
        val existingCount = table.stamps.size
        val fetch = runBlocking {
            collectRppPages(
                pageSize = 2000,
                fetchPage = server::page,
                fetchServerTotal = { serverTotal },
                onPage = { page -> page.forEach { table.stamps[it.objectId] = syncId } }
            )
        }
        val warnings = mutableListOf<String>()
        val removed = runBlocking {
            pruneIfSafe(
                "RPP", existingCount, fetch,
                deleteUnseen = { table.deleteNotSeenSince(syncId) },
                warn = { warnings += it },
                info = {}
            )
        }
        return SyncRun(removed, warnings)
    }

    @Test
    fun liveShape_prunesRunsAndRemovesOnlyTheJunkRows() {
        // 6,502 raw rows, 2 of them junk (zone "0"); the server's count is 6502. The table still holds
        // all 6,502 from a sync made before F4 started dropping junk, stamped with an older sync id.
        val junk = setOf(2500, 3000)
        val server = FakeServer(total = 6502, junkIds = junk)
        val table = FakeTable((1..6502).toList(), oldSyncId = 1L)

        val run = syncAndPrune(server, table, syncId = 2L, serverTotal = 6502)

        assertEquals("the prune must RUN, not be skipped", 2, run.removed)
        assertTrue("no skip warning expected: ${run.warnings}", run.warnings.isEmpty())
        assertEquals((1..6502).map { it.toString() }.toSet() - junk.map { it.toString() }.toSet(), table.stamps.keys)
        assertEquals(6500, table.stamps.size)
    }

    @Test
    fun theSyncAfterThePagingBug_restoresTheDeletedRows_andRemovesNothingValid() {
        // The S25 state after the bug: 3,998 rows stored (the other 2,504 wrongly deleted).
        val server = FakeServer(total = 6502, junkIds = setOf(2500, 3000))
        val table = FakeTable((1..4000).filter { it !in setOf(2500, 3000) }, oldSyncId = 5L)

        val run = syncAndPrune(server, table, syncId = 6L)

        assertEquals(0, run.removed)
        assertEquals(6500, table.stamps.size)
    }

    @Test
    fun aTruncatedFetch_deletesNothing_andLogsTheReason() {
        val server = FakeServer(total = 6502, stopsServingAt = 4000)
        val table = FakeTable((1..6502).toList(), oldSyncId = 1L)

        val run = syncAndPrune(server, table, syncId = 2L)

        assertNull(run.removed)
        assertEquals("every stored row survives", 6502, table.stamps.size)
        assertEquals(1, run.warnings.size)
        assertTrue(run.warnings[0], run.warnings[0].startsWith("Skipping stale-RPP cleanup: fetch stopped"))
    }

    @Test
    fun aCountMismatch_deletesNothing_andLogsBothNumbers() {
        val server = FakeServer(total = 6502)
        val table = FakeTable((1..6502).toList(), oldSyncId = 1L)

        val run = syncAndPrune(server, table, syncId = 2L, serverTotal = 6600)

        assertNull(run.removed)
        assertEquals(6502, table.stamps.size)
        assertTrue(run.warnings[0], "6502" in run.warnings[0] && "6600" in run.warnings[0])
    }

    @Test
    fun threePageFetch_getsEveryRow_andMayPrune() {
        val server = FakeServer(total = 4500)
        val result = collect(server)
        assertEquals(listOf(0, 2000, 4000), server.offsets)
        assertEquals(4500, result.fetch.keptRows)
        assertEquals(4500, result.fetch.rawRows)
        assertTrue(result.fetch.complete)
        assertEquals(1, result.totalCalls)
        assertNull(pruneSkipReason(existingCount = 4600, fetch = result.fetch))
    }

    @Test
    fun anExactMultipleOfThePageSize_needsNoExtraTrustInTheParsedCount() {
        // 4,000 rows = exactly two full pages; the flag says so on page 2, so no third request.
        val server = FakeServer(total = 4000)
        val result = collect(server)
        assertEquals(listOf(0, 2000), server.offsets)
        assertEquals(4000, result.fetch.rawRows)
        assertTrue(result.fetch.complete)
    }

    @Test
    fun whenTheFlagIsNeverSent_aFullRawPageStillTriggersTheNextRequest() {
        val server = FakeServer(total = 4000, sendsExceededFlag = false)
        val result = collect(server)
        // Full, full, then an empty page proves the end.
        assertEquals(listOf(0, 2000, 4000), server.offsets)
        assertEquals(4000, result.fetch.rawRows)
        assertTrue(result.fetch.complete)
    }

    @Test
    fun aServerThatCapsPagesBelowTheRequestedSize_isFollowedByRowsActuallyReturned() {
        // Asked for 2,000 a page, the server hands back 1,000: the offset must advance by 1,000.
        val server = FakeServer(total = 2500, maxRecordCount = 1000)
        val result = collect(server)
        assertEquals(listOf(0, 1000, 2000), server.offsets)
        assertEquals(2500, result.fetch.rawRows)
        assertTrue(result.fetch.complete)
        assertNull(pruneSkipReason(existingCount = 2500, fetch = result.fetch))
    }

    @Test
    fun aTruncatedFetch_isIncomplete_andNeverPrunes() {
        // The server claims more rows but stops serving at 4,000 of 6,502.
        val server = FakeServer(total = 6502, stopsServingAt = 4000)
        val result = collect(server)
        assertFalse(result.fetch.complete)
        assertEquals(4000, result.fetch.rawRows)
        assertEquals("no point asking for a total on a fetch already known to be incomplete", 0, result.totalCalls)
        assertNotNull(pruneSkipReason(existingCount = 6502, fetch = result.fetch))
        // Even if the count were somehow known and equal, an incomplete fetch must not prune.
        assertNotNull(pruneSkipReason(existingCount = 4000, fetch = result.fetch.copy(serverTotal = 4000)))
    }

    @Test
    fun aServerThatNeverStopsSaying_more_isCutOffAtThePageCap() {
        val server = FakeServer(total = 1_000_000, maxRecordCount = 1)
        val result = collect(server, pageSize = 1)
        assertEquals(MAX_FEED_PAGES, server.requests.size)
        assertFalse(result.fetch.complete)
        assertNotNull(pruneSkipReason(existingCount = 100, fetch = result.fetch))
    }

    @Test
    fun aCountMismatch_doesNotPrune() {
        // Every page was fetched cleanly, but the server now reports more rows than we saw.
        val server = FakeServer(total = 6502)
        val result = collect(server, serverTotal = { 6600 })
        assertTrue(result.fetch.complete)
        assertEquals(6502, result.fetch.rawRows)
        val reason = pruneSkipReason(existingCount = 6502, fetch = result.fetch)
        assertNotNull(reason)
        assertTrue(reason!!, "6502" in reason && "6600" in reason)
    }

    @Test
    fun anUnreadableServerCount_doesNotPrune_andDoesNotFailTheSync() {
        val server = FakeServer(total = 3000)
        val result = collect(server, serverTotal = { throw java.io.IOException("count query timed out") })
        assertTrue(result.fetch.complete)
        assertEquals(3000, result.fetch.keptRows)
        assertNull(result.fetch.serverTotal)
        assertNotNull(pruneSkipReason(existingCount = 3000, fetch = result.fetch))
    }

    @Test
    fun aFailedPage_propagates_soNothingIsPruned() {
        val server = FakeServer(total = 6502)
        var calls = 0
        val failed = runCatching {
            runBlocking {
                collectRppPages(
                    pageSize = 2000,
                    fetchPage = { limit, offset -> if (++calls == 3) throw java.io.IOException("HTTP 500") else server.page(limit, offset) },
                    fetchServerTotal = { 6502 },
                    onPage = {}
                )
            }
        }
        assertTrue(failed.isFailure)
    }

    @Test
    fun parseRppCount_readsTheCountOnlyResponse() {
        assertEquals(6502, parseRppCount("""{"count":6502}"""))
        assertNull(parseRppCount("""{"features":[]}"""))
        assertNull(parseRppCount("""{"count":null}"""))
    }

    @Test
    fun parseRppPage_reportsRawCountAndTheFlag() {
        val json = """{"features":[
            {"attributes":{"OBJECTID":1,"RPPAREA1":"A"},"geometry":{"paths":[[[-122.4,37.7]]]}},
            {"attributes":{"OBJECTID":2,"RPPAREA1":"0"},"geometry":{"paths":[[[-122.4,37.7]]]}}
          ],"exceededTransferLimit":true}"""
        val page = parseRppPage(json)
        assertEquals(2, page.rawCount)
        assertEquals(1, page.rows.size)
        assertEquals(true, page.exceededTransferLimit)
        assertNull(parseRppPage("""{"features":[]}""").exceededTransferLimit)
        assertEquals(false, parseRppPage("""{"features":[],"exceededTransferLimit":false}""").exceededTransferLimit)
    }
}
