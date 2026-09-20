package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun dummyRegulation(
    zoneLetters: String = "A",
    days: String = "M-F",
    hrsBegin: Int = 800,
    hrsEnd: Int = 1800,
    hrLimit: Float = 2.0f
) = RppZoneRegulation(
    objectId = "1",
    zoneLetters = zoneLetters,
    days = days,
    hrsBegin = hrsBegin,
    hrsEnd = hrsEnd,
    hrLimit = hrLimit,
    points = emptyList(),
    centroidLat = 0.0,
    centroidLng = 0.0
)

private fun dummyCar(permitZoneLetters: String? = null) = Car(name = "Test Car", permitZoneLetters = permitZoneLetters)

class RppStatusTest {

    @Test
    fun parseDaysRange_mondayToFriday() {
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            parseDaysRange("M-F")
        )
    }

    @Test
    fun parseDaysRange_mondayToSaturday() {
        assertTrue(DayOfWeek.SATURDAY in parseDaysRange("M-Sa"))
        assertTrue(DayOfWeek.SUNDAY !in parseDaysRange("M-Sa"))
    }

    @Test
    fun parseDaysRange_mondayToSunday_isAllSevenDays() {
        assertEquals(DayOfWeek.entries.toSet(), parseDaysRange("M-Su"))
    }

    @Test
    fun formatRppDays_translatesShorthandToDisplayNames() {
        assertEquals("Mon–Fri", formatRppDays("M-F"))
        assertEquals("Mon–Sat", formatRppDays("M-Sa"))
    }

    @Test
    fun militaryHourToLocalTime_parsesOnTheHourAndWithMinutes() {
        assertEquals(LocalTime.of(8, 0), militaryHourToLocalTime(800))
        assertEquals(LocalTime.of(18, 30), militaryHourToLocalTime(1830))
    }

    @Test
    fun nextRppDeadline_null_whenCarHoldsPermitForOneOfTheZoneLetters() {
        val regulation = dummyRegulation(zoneLetters = "A,Q")
        val car = dummyCar(permitZoneLetters = "Q")
        val now = LocalDateTime.of(2026, 9, 14, 10, 0) // a Monday, within 8am-6pm
        assertNull(nextRppDeadline(regulation, car, now.minusHours(1), now))
    }

    @Test
    fun nextRppDeadline_skipsForwardToNextEnforcedDay_whenParkedOnAnOffDay() {
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val saturday = LocalDateTime.of(2026, 9, 19, 10, 0)
        val warning = nextRppDeadline(regulation, car, saturday, saturday)
        // Next enforced day after Saturday 9/19 is Monday 9/21 — deadline is that day's window
        // open (8am) plus the 2hr limit, not null, since the car will still need to move once
        // Monday's enforcement starts if it's still parked there.
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 0), warning?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_beforeWindowOpensToday_returnsTodaysWindowOpenPlusLimit() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val beforeWindow = LocalDateTime.of(2026, 9, 14, 7, 0) // parked at 7am, window opens at 8am
        val warning = nextRppDeadline(regulation, car, beforeWindow, beforeWindow)
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 0), warning?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_afterWindowClosedToday_rollsToNextDay() {
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val afterWindow = LocalDateTime.of(2026, 9, 14, 18, 0) // Monday, right at window close
        val warning = nextRppDeadline(regulation, car, afterWindow, afterWindow)
        assertEquals(LocalDateTime.of(2026, 9, 15, 10, 0), warning?.moveByDateTime) // Tuesday 8am + 2hr
    }

    @Test
    fun nextRppDeadline_moveByIsParkedTimePlusLimit_whenParkedAfterWindowOpened() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val parkedSince = LocalDateTime.of(2026, 9, 14, 9, 0) // parked at 9am, after window opened at 8am
        val now = LocalDateTime.of(2026, 9, 14, 10, 0)
        val warning = nextRppDeadline(regulation, car, parkedSince, now)
        assertEquals(LocalDateTime.of(2026, 9, 14, 11, 0), warning?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_clockStartsAtWindowOpen_whenParkedBeforeWindowOpened() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val parkedSince = LocalDateTime.of(2026, 9, 14, 6, 0) // parked overnight, before window opened
        val now = LocalDateTime.of(2026, 9, 14, 9, 0)
        val warning = nextRppDeadline(regulation, car, parkedSince, now)
        // Clock starts at 8am (window open), not 6am (actual park time) — 2hr limit -> 10am.
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 0), warning?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_limitRunsPastWindowEnd_meansNoViolationToday_andRollsToNextMorning() {
        // Previously this clamped to 6pm ("limit is up" at the moment enforcement ends, with nothing
        // scheduled for the next day). Now: at 5pm a 4h limit lands at 9pm, after the 6pm close, so
        // there's no violation today and the next one is tomorrow's window-open + 4h.
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 4.0f)
        val car = dummyCar()
        val parkedSince = LocalDateTime.of(2026, 9, 14, 17, 0) // Monday 5pm
        val now = LocalDateTime.of(2026, 9, 14, 17, 30)
        val warning = nextRppDeadline(regulation, car, parkedSince, now)
        assertEquals(LocalDateTime.of(2026, 9, 15, 12, 0), warning?.moveByDateTime) // Tuesday 8am + 4h
    }

    @Test
    fun nextRppDeadline_559pmAnd601pm_bothGiveTomorrowMorning() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val tomorrow10am = LocalDateTime.of(2026, 9, 15, 10, 0)
        val at559 = LocalDateTime.of(2026, 9, 14, 17, 59)
        val at601 = LocalDateTime.of(2026, 9, 14, 18, 1)
        assertEquals(tomorrow10am, nextRppDeadline(regulation, car, at559, at559)?.moveByDateTime)
        assertEquals(tomorrow10am, nextRppDeadline(regulation, car, at601, at601)?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_fridayEvening_rollsToMonday() {
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()
        val friday559 = LocalDateTime.of(2026, 9, 18, 17, 59)
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 0), nextRppDeadline(regulation, car, friday559, friday559)?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_justBeforeClose_stillGivesATodayDeadline_whenTheLimitFits() {
        // 1h limit, parked 4:30pm: 5:30pm is inside the window, so it IS a violation today.
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 1.0f)
        val parked = LocalDateTime.of(2026, 9, 14, 16, 30)
        assertEquals(LocalDateTime.of(2026, 9, 14, 17, 30), nextRppDeadline(regulation, dummyCar(), parked, parked)?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_limitAsLongAsTheWholeWindow_neverProducesADeadline() {
        // The live feed has 72-hour rows; a limit that can't be exceeded within a window never applies.
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 72f)
        val now = LocalDateTime.of(2026, 9, 14, 9, 0)
        assertNull(nextRppDeadline(regulation, dummyCar(), now, now))
    }

    @Test
    fun nextRppDeadline_zeroOrNegativeLimit_returnsNull() {
        val now = LocalDateTime.of(2026, 9, 14, 9, 0)
        assertNull(nextRppDeadline(dummyRegulation(hrLimit = 0f), dummyCar(), now, now))
        assertNull(nextRppDeadline(dummyRegulation(hrLimit = -1f), dummyCar(), now, now))
    }

    @Test
    fun nextRppDeadline_skipsHolidays() {
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val car = dummyCar()

        // Thanksgiving 2026 (Thu Nov 26) and the day after (Fri Nov 27) are suspended: from Wednesday
        // evening the next enforced day is Monday Nov 30.
        val wedEvening = LocalDateTime.of(2026, 11, 25, 19, 0)
        assertEquals(LocalDateTime.of(2026, 11, 30, 10, 0), nextRppDeadline(regulation, car, wedEvening, wedEvening)?.moveByDateTime)

        // Christmas 2026 is a Friday: from Thursday evening the next enforced day is Monday Dec 28.
        val thuEvening = LocalDateTime.of(2026, 12, 24, 19, 0)
        assertEquals(LocalDateTime.of(2026, 12, 28, 10, 0), nextRppDeadline(regulation, car, thuEvening, thuEvening)?.moveByDateTime)

        // Jan 1, 2028 is a Saturday, so New Year's Day is observed Friday Dec 31, 2027 (a date in the
        // PREVIOUS year's calendar): from Thursday evening the next enforced day is Monday Jan 3.
        val dec30 = LocalDateTime.of(2027, 12, 30, 19, 0)
        assertEquals(LocalDateTime.of(2028, 1, 3, 10, 0), nextRppDeadline(regulation, car, dec30, dec30)?.moveByDateTime)
    }

    @Test
    fun nextRppDeadline_parkedOnAHolidayMorning_startsFromTheNextEnforcedDay() {
        // Labor Day 2026 is Monday Sep 7: parking at 10am that day starts no clock.
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f)
        val labor = LocalDateTime.of(2026, 9, 7, 10, 0)
        assertEquals(LocalDateTime.of(2026, 9, 8, 10, 0), nextRppDeadline(regulation, dummyCar(), labor, labor)?.moveByDateTime)
    }

    @Test
    fun isRppSuspended_usesTheFullHolidayList() {
        assertTrue(SfHolidayCalendar.isRppSuspended(LocalDate.of(2026, 9, 7)))    // Labor Day
        assertTrue(SfHolidayCalendar.isRppSuspended(LocalDate.of(2026, 11, 27)))  // day after Thanksgiving
        assertTrue(SfHolidayCalendar.isRppSuspended(LocalDate.of(2027, 12, 31)))  // New Year's 2028, observed
        assertTrue(!SfHolidayCalendar.isRppSuspended(LocalDate.of(2026, 9, 8)))   // an ordinary Tuesday
    }

    @Test
    fun activeRppWindowEndMillis_isNullOnAHoliday_evenInsideTheWindow() {
        val regulation = dummyRegulation(days = "M-F", hrsBegin = 800, hrsEnd = 1800)
        assertNull(activeRppWindowEndMillis(regulation, LocalDateTime.of(2026, 9, 7, 10, 0))) // Labor Day
        assertEquals(8 * 60 * 60 * 1000L, activeRppWindowEndMillis(regulation, LocalDateTime.of(2026, 9, 8, 10, 0))) // next day: active
    }

    @Test
    fun nextRppDeadline_null_whenNoZoneLettersAtAll() {
        val regulation = dummyRegulation(zoneLetters = "")
        val car = dummyCar()
        val now = LocalDateTime.of(2026, 9, 14, 10, 0)
        assertNull(nextRppDeadline(regulation, car, now.minusHours(1), now))
    }

    @Test
    fun activeRppWindowEndMillis_null_outsideEnforcedDays() {
        val regulation = dummyRegulation(days = "M-F")
        val saturday = LocalDateTime.of(2026, 9, 19, 10, 0)
        assertNull(activeRppWindowEndMillis(regulation, saturday))
    }

    @Test
    fun activeRppWindowEndMillis_null_outsideEnforcedHours() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800)
        val beforeWindow = LocalDateTime.of(2026, 9, 14, 7, 0)
        val afterWindow = LocalDateTime.of(2026, 9, 14, 18, 0)
        assertNull(activeRppWindowEndMillis(regulation, beforeWindow))
        assertNull(activeRppWindowEndMillis(regulation, afterWindow))
    }

    @Test
    fun activeRppWindowEndMillis_returnsMillisUntilWindowCloses_whenActive() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800)
        val now = LocalDateTime.of(2026, 9, 14, 16, 0) // 2 hours before the 6pm close
        assertEquals(2 * 60 * 60 * 1000L, activeRppWindowEndMillis(regulation, now))
    }
}
