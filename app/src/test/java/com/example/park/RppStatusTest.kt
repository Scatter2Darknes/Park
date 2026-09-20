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
    fun nextRppDeadline_moveByCappedAtWindowEnd_whenLimitWouldExceedIt() {
        val regulation = dummyRegulation(hrsBegin = 800, hrsEnd = 1800, hrLimit = 4.0f)
        val car = dummyCar()
        val parkedSince = LocalDateTime.of(2026, 9, 14, 17, 0) // parked at 5pm, 4hr limit would land at 9pm
        val now = LocalDateTime.of(2026, 9, 14, 17, 30)
        val warning = nextRppDeadline(regulation, car, parkedSince, now)
        assertEquals(LocalDateTime.of(2026, 9, 14, 18, 0), warning?.moveByDateTime) // capped at 6pm window end
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
