package com.example.park

import org.junit.Assert.assertFalse
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
    fun exactlyHalf_isStillSafe_butJustUnderIsNot() {
        assertTrue(isSafeToPrune(existingCount = 1_000, fetchedCount = 500))
        assertFalse(isSafeToPrune(existingCount = 1_000, fetchedCount = 499))
    }

    @Test
    fun aTruncatedResponse_isNeverSafe() {
        assertFalse(isSafeToPrune(existingCount = 36_000, fetchedCount = 0))
        assertFalse(isSafeToPrune(existingCount = 36_000, fetchedCount = 2_000))
    }
}
