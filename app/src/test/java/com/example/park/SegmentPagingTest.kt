package com.example.park

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The street-segment (Socrata) side of the same "prune only on a provably complete fetch" policy. */
class SegmentPagingTest {

    private class FakeSocrata(val total: Int, val stopsServingAt: Int = total) {
        val offsets = mutableListOf<Int>()
        var pauses = 0

        fun page(limit: Int, offset: Int): String {
            offsets += offset
            val ids = (offset until minOf(offset + limit, stopsServingAt)).toList()
            return ids.joinToString(",", "[", "]") { """{"blocksweepid":"$it","cnn":"1"}""" }
        }
    }

    private fun collect(server: FakeSocrata, pageSize: Int = 1000, serverTotal: () -> Int = { server.total }) =
        runBlocking {
            collectSegmentPages(
                pageSize = pageSize,
                pause = { server.pauses++ },
                fetchPage = server::page,
                fetchServerTotal = serverTotal,
                onPage = {}
            )
        }

    @Test
    fun aMultiPageFetch_matchesTheServerCount_andMayPrune() {
        val server = FakeSocrata(total = 2500)
        val fetch = collect(server)
        assertEquals(listOf(0, 1000, 2000), server.offsets)
        assertEquals(2500, fetch.rawRows)
        assertTrue(fetch.complete)
        assertEquals(2500, fetch.serverTotal)
        assertNull(pruneSkipReason(existingCount = 2600, fetch = fetch))
    }

    @Test
    fun anExactMultipleOfThePageSize_isConfirmedByAnEmptyPage() {
        val server = FakeSocrata(total = 2000)
        val fetch = collect(server)
        assertEquals(listOf(0, 1000, 2000), server.offsets)
        assertTrue(fetch.complete)
        assertEquals(2000, fetch.rawRows)
    }

    @Test
    fun aShortFetch_thatEndedEarly_doesNotPrune() {
        // The server stops serving at 1,500 of 2,500: the short page looks like the end, but the count says otherwise.
        val server = FakeSocrata(total = 2500, stopsServingAt = 1500)
        val fetch = collect(server)
        assertTrue(fetch.complete)
        assertEquals(1500, fetch.rawRows)
        assertEquals(2500, fetch.serverTotal)
        assertNotNull(pruneSkipReason(existingCount = 1500, fetch = fetch))
    }

    @Test
    fun anUnreadableCount_doesNotPrune() {
        val fetch = collect(FakeSocrata(total = 1500), serverTotal = { throw java.io.IOException("HTTP 425") })
        assertTrue(fetch.complete)
        assertNull(fetch.serverTotal)
        assertNotNull(pruneSkipReason(existingCount = 1500, fetch = fetch))
    }

    @Test
    fun aServerThatNeverEnds_isCutOffAtThePageCap() {
        val server = FakeSocrata(total = 1_000_000)
        val fetch = collect(server, pageSize = 1)
        assertEquals(MAX_FEED_PAGES, server.offsets.size)
        assertFalse(fetch.complete)
        assertNotNull(pruneSkipReason(existingCount = 10, fetch = fetch))
    }

    @Test
    fun parseSocrataCount_readsTheStringCount() {
        assertEquals(37878, parseSocrataCount("""[{"count":"37878"}]"""))
        assertNull(parseSocrataCount("[]"))
        assertNull(parseSocrataCount("""[{"other":"1"}]"""))
    }

    @Test
    fun parseSegments_keepsEveryRow_soRawAndParsedCountsAgree() {
        // collectSegmentPages relies on this: if parseSegments ever starts dropping rows, paging on the
        // parsed size would repeat the RPP bug.
        val json = """[{"blocksweepid":"1"},{"cnn":"2"},{},{"blocksweepid":"4","line":{"coordinates":[[-122.4,37.7]]}}]"""
        assertEquals(4, parseSegments(json).size)
    }
}
