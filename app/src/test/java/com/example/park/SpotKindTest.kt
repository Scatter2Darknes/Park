package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one "what kind of spot" choice in LocationStyleDialog and the two stored flags behind it. */
class SpotKindTest {

    private fun location(safe: Boolean?, offStreet: Boolean?) =
        SavedLocation(name = "Home", lat = 37.7, lng = -122.4, isSafeFromSweeping = safe, isOffStreet = offStreet)

    @Test
    fun readsTheStoredFlags_nullMeaningOff() {
        assertEquals(SpotKind.ON_STREET, location(null, null).spotKind)
        assertEquals(SpotKind.ON_STREET, location(false, false).spotKind)
        assertEquals(SpotKind.NEVER_SWEPT, location(true, null).spotKind)
        assertEquals(SpotKind.OFF_STREET, location(true, true).spotKind)
    }

    @Test
    fun offStreetWins_evenWithoutTheSafeFlag() {
        // Can't be set from the UI, but an off-street spot is never swept either way.
        assertEquals(SpotKind.OFF_STREET, location(false, true).spotKind)
    }

    @Test
    fun eachChoiceWritesAConsistentPair() {
        assertFalse(SpotKind.ON_STREET.isSafeFromSweeping); assertFalse(SpotKind.ON_STREET.isOffStreet)
        assertTrue(SpotKind.NEVER_SWEPT.isSafeFromSweeping); assertFalse(SpotKind.NEVER_SWEPT.isOffStreet)
        assertTrue(SpotKind.OFF_STREET.isSafeFromSweeping); assertTrue(SpotKind.OFF_STREET.isOffStreet)
    }

    @Test
    fun savingAChoiceAndReadingItBack_roundTrips() {
        for (kind in SpotKind.entries) {
            assertEquals(kind, location(kind.isSafeFromSweeping, kind.isOffStreet).spotKind)
        }
    }
}
