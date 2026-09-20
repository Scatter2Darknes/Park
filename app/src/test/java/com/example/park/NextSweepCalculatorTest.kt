package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Builds a segment for sweep-logic tests. Avoids anything that touches android.graphics.Color. */
internal fun sweepSegment(
    fullName: String = "Mon",
    fromHour: Int = 6,
    toHour: Int = 8,
    weeks: BooleanArray = booleanArrayOf(true, true, true, true, true),
    holidays: Boolean = false
) = StreetSegment(
    blockSweepId = "test", cnn = "0", corridor = "Test St", limits = "A to B",
    cnnRightLeft = "R", blockSide = "North", fullName = fullName,
    fromHour = fromHour, toHour = toHour,
    week1 = weeks[0], week2 = weeks[1], week3 = weeks[2], week4 = weeks[3], week5 = weeks[4],
    holidays = holidays, points = emptyList(), centroidLat = 0.0, centroidLng = 0.0
)

private fun onlyWeek(n: Int) = BooleanArray(5) { it == n - 1 }

class NextSweepCalculatorTest {

    // ---- Recurrence: week-of-month flags ----

    /** Day-of-month -> which occurrence of its weekday it is: 1-7 => 1st, 8-14 => 2nd, ... 29-31 => 5th.
     *  April 2026 has no holidays, and its Tuesdays/Wednesdays hit every boundary date. */
    private val boundaryCases = listOf(
        LocalDate.of(2026, 4, 7) to 1, LocalDate.of(2026, 4, 8) to 2,
        LocalDate.of(2026, 4, 14) to 2, LocalDate.of(2026, 4, 15) to 3,
        LocalDate.of(2026, 4, 21) to 3, LocalDate.of(2026, 4, 22) to 4,
        LocalDate.of(2026, 4, 28) to 4, LocalDate.of(2026, 4, 29) to 5
    )

    @Test
    fun weekBoundaries_7_8_14_15_21_22_28_29_landOnTheRightOccurrence() {
        for ((date, occurrence) in boundaryCases) {
            val name = date.dayOfWeek.name.substring(0, 3)
            val expected = date.atTime(6, 0)

            // The flag for this date's occurrence, alone, sweeps exactly on that date...
            val right = sweepSegment(fullName = name, weeks = onlyWeek(occurrence))
            assertEquals("$date should be occurrence $occurrence", expected,
                NextSweepCalculator.nextSweepDateTime(right, date.minusDays(1).atTime(12, 0)))

            // ...and the adjacent occurrence's flag, alone, must NOT sweep on it.
            val neighbour = if (occurrence == 1) 2 else occurrence - 1
            val wrong = sweepSegment(fullName = name, weeks = onlyWeek(neighbour))
            assertNotEquals("$date must not match occurrence $neighbour", expected,
                NextSweepCalculator.nextSweepDateTime(wrong, date.minusDays(1).atTime(12, 0)))
        }
    }

    @Test
    fun eachOfTheFiveWeekFlagsWorksOnItsOwn() {
        // Tuesdays 2026-03-31 (5th), 04-07 (1st), 04-14 (2nd), 04-21 (3rd), 04-28 (4th).
        val cases = mapOf(
            1 to LocalDate.of(2026, 4, 7), 2 to LocalDate.of(2026, 4, 14), 3 to LocalDate.of(2026, 4, 21),
            4 to LocalDate.of(2026, 4, 28), 5 to LocalDate.of(2026, 6, 30)
        )
        for ((week, expected) in cases) {
            val seg = sweepSegment(fullName = "Tue", weeks = onlyWeek(week))
            assertEquals("week $week", expected.atTime(6, 0),
                NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 4, 1, 12, 0)))
        }
    }

    @Test
    fun monthWithNoFifthOccurrence_skipsToTheNextMonthThatHasOne() {
        // February 2026 has 28 days, so no weekday has a 5th occurrence in it; the next 5th Monday is March 30.
        val seg = sweepSegment(fullName = "Mon", weeks = onlyWeek(5))
        assertEquals(LocalDateTime.of(2026, 3, 30, 6, 0),
            NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 2, 1, 12, 0)))
    }

    // ---- `from` at the edges of the window ----

    @Test
    fun fromExactlyAtWindowStart_isNotAfter_soItReturnsNextWeek() {
        val seg = sweepSegment(fullName = "Mon", fromHour = 6, toHour = 8)
        val mondayAtStart = LocalDateTime.of(2026, 4, 13, 6, 0)
        assertEquals(LocalDateTime.of(2026, 4, 20, 6, 0), NextSweepCalculator.nextSweepDateTime(seg, mondayAtStart))
    }

    @Test
    fun oneSecondBeforeWindowStart_returnsTodaysStart() {
        val seg = sweepSegment(fullName = "Mon", fromHour = 6, toHour = 8)
        assertEquals(LocalDateTime.of(2026, 4, 13, 6, 0),
            NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 4, 13, 5, 59, 59)))
    }

    @Test
    fun sweepInProgress_returnsNextWeeksStart_andExactlyAtWindowEndDoesToo() {
        val seg = sweepSegment(fullName = "Mon", fromHour = 6, toHour = 8)
        val nextWeek = LocalDateTime.of(2026, 4, 20, 6, 0)
        assertEquals(nextWeek, NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 4, 13, 7, 15)))
        assertEquals(nextWeek, NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 4, 13, 8, 0)))
    }

    // ---- Holidays ----

    @Test
    fun mondayHoliday_pushesAMondaySweepOutAWeek() {
        // Memorial Day 2026 is Monday May 25.
        val seg = sweepSegment(fullName = "Mon")
        assertEquals(LocalDateTime.of(2026, 6, 1, 6, 0),
            NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 5, 24, 12, 0)))
    }

    @Test
    fun thanksgivingThursday_isSkippedOnEveryList() {
        // Thanksgiving 2026 is Thursday Nov 26; both the full and the major-only lists include it.
        for (holidays in listOf(false, true)) {
            val seg = sweepSegment(fullName = "Thu", holidays = holidays)
            assertEquals("holidays=$holidays", LocalDateTime.of(2026, 12, 3, 6, 0),
                NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 11, 25, 12, 0)))
        }
    }

    @Test
    fun dayAfterThanksgiving_isSuspendedForDaytimeRoutes_butNotNightlyOnes() {
        val from = LocalDateTime.of(2026, 11, 26, 12, 0) // Thanksgiving afternoon; the Friday is Nov 27
        val daytime = sweepSegment(fullName = "Fri", fromHour = 8, toHour = 10, holidays = false)
        assertEquals(LocalDateTime.of(2026, 12, 4, 8, 0), NextSweepCalculator.nextSweepDateTime(daytime, from))

        val nightly = sweepSegment(fullName = "Fri", fromHour = 8, toHour = 10, holidays = true)
        assertEquals(LocalDateTime.of(2026, 11, 27, 8, 0), NextSweepCalculator.nextSweepDateTime(nightly, from))
    }

    @Test
    fun observedDateShift_july3_2026_isSkippedForAFridaySweep() {
        // July 4, 2026 is a Saturday, so it is observed on Friday July 3.
        val seg = sweepSegment(fullName = "Fri", fromHour = 8, toHour = 10)
        assertEquals(LocalDateTime.of(2026, 7, 10, 8, 0),
            NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2026, 7, 2, 12, 0)))
    }

    @Test
    fun routeStartingBefore6am_followsTheMajorHolidaysOnlyList_evenWithHolidaysFlagOff() {
        // The live feed has thousands of overnight rows with holidays=0 (see SfHolidayCalendar.isNightlyRoute).
        // MLK Day 2026 is Monday Jan 19: a 2am route is still swept, an 8am route is suspended.
        val from = LocalDateTime.of(2026, 1, 18, 12, 0)
        val overnight = sweepSegment(fullName = "Mon", fromHour = 2, toHour = 6, holidays = false)
        assertEquals(LocalDateTime.of(2026, 1, 19, 2, 0), NextSweepCalculator.nextSweepDateTime(overnight, from))
        val daytime = sweepSegment(fullName = "Mon", fromHour = 8, toHour = 10, holidays = false)
        assertEquals(LocalDateTime.of(2026, 1, 26, 8, 0), NextSweepCalculator.nextSweepDateTime(daytime, from))
    }

    // ---- dayOfWeekFromName ----

    @Test
    fun dayOfWeekFromName_parsesFullNamesAbbreviationsAndCase() {
        assertEquals(DayOfWeek.MONDAY, NextSweepCalculator.dayOfWeekFromName("Monday"))
        assertEquals(DayOfWeek.MONDAY, NextSweepCalculator.dayOfWeekFromName("Mon"))
        assertEquals(DayOfWeek.MONDAY, NextSweepCalculator.dayOfWeekFromName("MONDAY"))
        assertEquals(DayOfWeek.WEDNESDAY, NextSweepCalculator.dayOfWeekFromName("  wed  "))
        assertEquals(DayOfWeek.TUESDAY, NextSweepCalculator.dayOfWeekFromName("Tue"))
        assertEquals(DayOfWeek.THURSDAY, NextSweepCalculator.dayOfWeekFromName("Thursday"))
        assertEquals(DayOfWeek.SUNDAY, NextSweepCalculator.dayOfWeekFromName("Sun"))
    }

    @Test
    fun dayOfWeekFromName_usesTheFirstWordOnly_soRecurrenceSuffixesAreIgnored() {
        assertEquals(DayOfWeek.MONDAY, NextSweepCalculator.dayOfWeekFromName("Mon 2nd & 4th"))
        assertEquals(DayOfWeek.TUESDAY, NextSweepCalculator.dayOfWeekFromName("Tue 1st, 3rd, 5th"))
        assertEquals(DayOfWeek.FRIDAY, NextSweepCalculator.dayOfWeekFromName("Fri 1st"))
    }

    @Test
    fun dayOfWeekFromName_returnsNullForGarbage() {
        assertNull(NextSweepCalculator.dayOfWeekFromName(""))
        assertNull(NextSweepCalculator.dayOfWeekFromName("   "))
        assertNull(NextSweepCalculator.dayOfWeekFromName("Funday"))
        assertNull(NextSweepCalculator.dayOfWeekFromName("2nd Monday"))
        // The live feed has ~824 segments named "HOLIDAY" (routes swept only on holidays). They have
        // no weekday, so today they parse to null and therefore never show an upcoming sweep.
        assertNull(NextSweepCalculator.dayOfWeekFromName("HOLIDAY"))
    }

    @Test
    fun aSegmentWithAnUnparseableDay_hasNoNextSweep() {
        assertNull(NextSweepCalculator.nextSweepDateTime(sweepSegment(fullName = "HOLIDAY"), LocalDateTime.of(2026, 4, 1, 12, 0)))
    }

    // ---- The search limit ----

    @Test
    fun weekFiveOnlySchedule_canHaveAGapLongerThan60Days_soTheOldLimitReturnedNull() {
        // 5th Tuesdays in 2026: Mar 31, then Jun 30 — 90 days apart. Searching from April 1:
        val seg = sweepSegment(fullName = "Tue", weeks = onlyWeek(5))
        val from = LocalDateTime.of(2026, 4, 1, 12, 0)

        // The old limit (60 days) gave up before reaching June 30, which sweepStatus treats as SAFE
        // and saveParkedState treats as "no reminder" — a silently missed sweep.
        assertNull(NextSweepCalculator.nextSweepDateTime(seg, from, maxDaysToSearch = 60))
        assertNull(NextSweepCalculator.nextSweepDateTime(seg, from, maxDaysToSearch = 89))

        // The default is now 250 days and finds it.
        assertEquals(LocalDateTime.of(2026, 6, 30, 6, 0), NextSweepCalculator.nextSweepDateTime(seg, from))
    }

    @Test
    fun defaultSearchLimit_coversTheLongestGapsThatOccurInPractice() {
        // A brute force over every weekday, both holiday lists, and 2026-2035 found a worst case of
        // 210 days (a Monday in 2033). Spot-check that exact case, then the limit's margin.
        val seg = sweepSegment(fullName = "Mon", fromHour = 8, toHour = 10, weeks = onlyWeek(5))
        assertTrue(NextSweepCalculator.DEFAULT_MAX_DAYS_TO_SEARCH >= 250)
        assertEquals(LocalDateTime.of(2033, 8, 29, 8, 0),
            NextSweepCalculator.nextSweepDateTime(seg, LocalDateTime.of(2033, 1, 31, 12, 0))) // Memorial Day (May 30, the 5th Monday) is suspended
    }
}
