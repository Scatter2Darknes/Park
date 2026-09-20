package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * sweepStatus with the default thresholds (SOON above 2 days, SAFE above 3 days). Never calls
 * sweepStatusColor, which uses android.graphics.Color.parseColor and is a stub on the JVM.
 */
class SweepStatusTest {

    // Monday 6am-8am, every week. 2026-04-13 is a Monday (April 2026 has no holidays).
    private val seg = sweepSegment(fullName = "Mon", fromHour = 6, toHour = 8)
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 4, day, hour, minute)

    @Test
    fun activeAtTheStartMinute_andThroughTheWindow() {
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, sweepStatus(seg, at(13, 6, 0)))
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, sweepStatus(seg, at(13, 7, 30)))
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, sweepStatus(seg, at(13, 7, 59)))
    }

    @Test
    fun exactlyAtWindowEnd_isNoLongerActive() {
        // Next sweep is a week away (6.9 days) => SAFE by default thresholds.
        assertEquals(SweepStatus.SAFE, sweepStatus(seg, at(13, 8, 0)))
    }

    @Test
    fun thirtyMinutesBeforeStart_isVerySoon_thirtyOneIsNot() {
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, sweepStatus(seg, at(13, 5, 30))) // 30 min in
        assertEquals(SweepStatus.IMMINENT, sweepStatus(seg, at(13, 5, 29)))            // 31 min out
    }

    @Test
    fun onTheSweepDayButOutsideTheWindow_usesTheNextOccurrence() {
        assertEquals(SweepStatus.SAFE, sweepStatus(seg, at(13, 12, 0))) // afternoon after the sweep
    }

    @Test
    fun soonAndImminentThresholds_areStrictlyGreaterThan() {
        // Next sweep is Mon Apr 20 6:00.
        assertEquals(SweepStatus.SAFE, sweepStatus(seg, at(16, 12, 0)))     // 3.75 days out
        assertEquals(SweepStatus.SOON, sweepStatus(seg, at(17, 6, 0)))      // exactly 3.0 days: not > 3 => SOON
        assertEquals(SweepStatus.SOON, sweepStatus(seg, at(17, 12, 0)))     // 2.75 days
        assertEquals(SweepStatus.IMMINENT, sweepStatus(seg, at(18, 6, 0)))  // exactly 2.0 days: not > 2 => IMMINENT
        assertEquals(SweepStatus.IMMINENT, sweepStatus(seg, at(19, 12, 0))) // 0.75 days
    }

    @Test
    fun aHolidaySweepDay_isNotActive() {
        // Memorial Day 2026 is Monday May 25: the 6:30am "window" is suspended; next sweep is Jun 1.
        assertEquals(SweepStatus.SAFE, sweepStatus(seg, LocalDateTime.of(2026, 5, 25, 6, 30)))
    }

    @Test
    fun onlyTheMatchingWeekOfTheMonthCounts() {
        // 2nd Monday only. Apr 6 is the 1st Monday: not active, and the next sweep (Apr 13) is a week off.
        val secondOnly = sweepSegment(fullName = "Mon", fromHour = 6, toHour = 8,
            weeks = booleanArrayOf(false, true, false, false, false))
        assertEquals(SweepStatus.SAFE, sweepStatus(secondOnly, at(6, 7, 0)))
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, sweepStatus(secondOnly, at(13, 7, 0)))
    }

    @Test
    fun aScheduleWithNoOccurrenceInRange_isSafe() {
        assertEquals(SweepStatus.SAFE, sweepStatus(sweepSegment(fullName = "HOLIDAY"), at(13, 7, 0)))
    }
}
