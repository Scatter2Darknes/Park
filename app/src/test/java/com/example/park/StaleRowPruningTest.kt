package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRowPruningTest {
    @Test
    fun emptyTable_alwaysSafe() {
        assertTrue(isSafeToPrune(existingCount = 0, fetchedCount = 0))
        assertTrue(isSafeToPrune(existingCount = 0, fetchedCount = 5_000))
    }

    @Test
    fun aFullResyncIsSafe_evenWhenSomeRowsVanished() {
        assertTrue(isSafeToPrune(existingCount = 36_000, fetchedCount = 36_000))
        assertTrue(isSafeToPrune(existingCount = 36_000, fetchedCount = 30_000)) // ~17% retired upstream
    }

    @Test
    fun exactlyTwentyPercent_isStillSafe_butJustUnderIsNot() {
        assertTrue(isSafeToPrune(existingCount = 1_000, fetchedCount = 200))
        assertFalse(isSafeToPrune(existingCount = 1_000, fetchedCount = 199))
        assertEquals(0.2, PRUNE_MIN_FETCHED_FRACTION, 0.0)
    }

    @Test
    fun aTruncatedResponse_isNeverSafe() {
        assertFalse(isSafeToPrune(existingCount = 36_000, fetchedCount = 0))
        assertFalse(isSafeToPrune(existingCount = 36_000, fetchedCount = 2_000))
    }

    private fun fetch(raw: Int, kept: Int = raw, complete: Boolean = true, total: Int? = raw) =
        FeedFetch(keptRows = kept, rawRows = raw, complete = complete, serverTotal = total)

    @Test
    fun pruneSkipReason_allowsACompleteMatchingFetch() {
        assertNull(pruneSkipReason(existingCount = 6502, fetch = fetch(raw = 6502, kept = 6500)))
        assertNull(pruneSkipReason(existingCount = 0, fetch = fetch(raw = 10)))
    }

    @Test
    fun pruneSkipReason_refusesAnIncompleteFetch_evenIfTheCountsMatch() {
        assertNotNull(pruneSkipReason(existingCount = 100, fetch = fetch(raw = 100, complete = false)))
    }

    @Test
    fun pruneSkipReason_refusesWhenTheServerCountIsUnknownOrDiffers() {
        assertNotNull(pruneSkipReason(existingCount = 100, fetch = fetch(raw = 100, total = null)))
        assertNotNull(pruneSkipReason(existingCount = 100, fetch = fetch(raw = 100, total = 101)))
        assertNotNull(pruneSkipReason(existingCount = 100, fetch = fetch(raw = 100, total = 99)))
    }

    @Test
    fun pruneSkipReason_keepsTheFractionGuardAsABackstop() {
        // Complete and matching, but the feed is under 20% of the stored rows: still suspicious.
        assertNotNull(pruneSkipReason(existingCount = 1_000, fetch = fetch(raw = 199)))
        assertNull(pruneSkipReason(existingCount = 1_000, fetch = fetch(raw = 200)))
        // A 60% retirement, which the old 50% guard would have refused, is now allowed when counts match.
        assertNull(pruneSkipReason(existingCount = 1_000, fetch = fetch(raw = 400)))
    }
}
