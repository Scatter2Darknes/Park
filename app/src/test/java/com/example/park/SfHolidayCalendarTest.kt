package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun dummySegment(holidays: Boolean) = StreetSegment(
    blockSweepId = "test",
    cnn = "0",
    corridor = "Test St",
    limits = "A to B",
    cnnRightLeft = "R",
    blockSide = "North",
    fullName = "Mon",
    fromHour = 8,
    toHour = 10,
    week1 = true,
    week2 = true,
    week3 = true,
    week4 = true,
    week5 = true,
    holidays = holidays,
    points = emptyList(),
    centroidLat = 0.0,
    centroidLng = 0.0
)

/**
 * Verified against SFMTA's own published 2026 Holiday Parking Enforcement Schedule table
 * (sfmta.com/getting-around/drive-park/holiday-enforcement-schedule) — every date here is
 * transcribed from that table, not derived independently, so this is checking the computed
 * rules against the real calendar rather than against themselves.
 */
class SfHolidayCalendarTest {

    @Test
    fun fullSuspensionHolidays2026_matchesSfmtaPublishedTable() {
        val expected = setOf(
            LocalDate.of(2026, 1, 1),   // New Year's Day
            LocalDate.of(2026, 1, 19),  // MLK Day
            LocalDate.of(2026, 2, 16),  // Presidents' Day
            LocalDate.of(2026, 5, 25),  // Memorial Day
            LocalDate.of(2026, 6, 19),  // Juneteenth
            LocalDate.of(2026, 7, 3),   // Independence Day, observed (7/4 falls on a Saturday)
            LocalDate.of(2026, 9, 7),   // Labor Day
            LocalDate.of(2026, 10, 12), // Indigenous Peoples' Day
            LocalDate.of(2026, 11, 11), // Veterans Day
            LocalDate.of(2026, 11, 26), // Thanksgiving
            LocalDate.of(2026, 11, 27), // Day After Thanksgiving
            LocalDate.of(2026, 12, 25), // Christmas
        )
        assertEquals(expected, SfHolidayCalendar.fullSuspensionHolidays(2026).keys)
    }

    @Test
    fun majorHolidaysOnly2026_isJustTheThreeBigOnes() {
        val expected = setOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 11, 26),
            LocalDate.of(2026, 12, 25),
        )
        assertEquals(expected, SfHolidayCalendar.majorHolidaysOnly(2026).keys)
    }

    @Test
    fun newYearsDaySpillsBackIntoDecember_whenNextJan1IsASaturday() {
        // Jan 1, 2028 falls on a Saturday, so its observed date (Fri Dec 31, 2027) belongs to
        // 2027's suspension list, not 2028's — otherwise a Dec 31 candidate date would never
        // see it, since NextSweepCalculator looks up the holiday set by the CANDIDATE date's
        // own year.
        assertEquals(LocalDate.of(2027, 12, 31), LocalDate.of(2028, 1, 1).minusDays(1))
        assertTrue(LocalDate.of(2028, 1, 1).dayOfWeek == java.time.DayOfWeek.SATURDAY)
        assertEquals("New Year's Day", SfHolidayCalendar.fullSuspensionHolidays(2027)[LocalDate.of(2027, 12, 31)])
    }

    @Test
    fun isSuspended_ordinaryWeekdayRoute_usesFullList() {
        val segment = dummySegment(holidays = false)
        assertTrue(SfHolidayCalendar.isSuspended(LocalDate.of(2026, 11, 26), segment)) // Thanksgiving
        assertTrue(SfHolidayCalendar.isSuspended(LocalDate.of(2026, 9, 7), segment))   // Labor Day
        assertFalse(SfHolidayCalendar.isSuspended(LocalDate.of(2026, 9, 14), segment)) // an ordinary Monday
    }

    @Test
    fun isSuspended_nightlyCommercialRoute_onlyUsesMajorThree() {
        val segment = dummySegment(holidays = true)
        assertTrue(SfHolidayCalendar.isSuspended(LocalDate.of(2026, 11, 26), segment))  // Thanksgiving
        assertFalse(SfHolidayCalendar.isSuspended(LocalDate.of(2026, 9, 7), segment))   // Labor Day not suspended for this route
    }

    @Test
    fun holidayName_returnsNullOnNonHolidayDate() {
        assertNull(SfHolidayCalendar.holidayName(LocalDate.of(2026, 9, 14), dummySegment(holidays = false)))
    }
}
