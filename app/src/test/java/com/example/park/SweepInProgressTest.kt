package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * sweepInProgressEndDateTime backs the "Sweeping in progress — move now" notice posted when a car
 * is parked during a sweep. The existing activeWindowEndDateTime does NOT check that the window has
 * started (it returns today's end time on any sweep day), which is why this stricter function exists.
 */
class SweepInProgressTest {

    // Monday 6am-8am, every week. 2026-09-14 is a Monday (not a holiday).
    private val segment = StreetSegment(
        blockSweepId = "test", cnn = "0", corridor = "Test St", limits = "A to B",
        cnnRightLeft = "R", blockSide = "North", fullName = "Mon", fromHour = 6, toHour = 8,
        week1 = true, week2 = true, week3 = true, week4 = true, week5 = true,
        holidays = false, points = emptyList(), centroidLat = 0.0, centroidLng = 0.0
    )
    private val monday = LocalDateTime.of(2026, 9, 14, 0, 0)

    @Test
    fun parkedInsideWindow_returnsWindowEnd() {
        assertEquals(monday.withHour(8), NextSweepCalculator.sweepInProgressEndDateTime(segment, monday.withHour(7).withMinute(15)))
    }

    @Test
    fun exactlyAtWindowStart_isInProgress() {
        assertEquals(monday.withHour(8), NextSweepCalculator.sweepInProgressEndDateTime(segment, monday.withHour(6)))
    }

    @Test
    fun beforeWindowOpens_isNotInProgress_evenThoughActiveWindowEndWouldSayOtherwise() {
        val fiveAm = monday.withHour(5)
        assertEquals(monday.withHour(8), NextSweepCalculator.activeWindowEndDateTime(segment, fiveAm)) // the gap this guards against
        assertNull(NextSweepCalculator.sweepInProgressEndDateTime(segment, fiveAm))
    }

    @Test
    fun atOrAfterWindowEnd_isNotInProgress() {
        assertNull(NextSweepCalculator.sweepInProgressEndDateTime(segment, monday.withHour(8)))
        assertNull(NextSweepCalculator.sweepInProgressEndDateTime(segment, monday.withHour(9)))
    }

    @Test
    fun wrongDay_isNotInProgress() {
        assertNull(NextSweepCalculator.sweepInProgressEndDateTime(segment, monday.plusDays(1).withHour(7)))
    }
}
