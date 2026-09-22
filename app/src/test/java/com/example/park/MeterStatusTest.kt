package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private fun dummyZone(
    days: String? = "Mo,Tu,We,Th,Fr",
    hrsBegin: Int? = 900,
    hrsEnd: Int? = 1800,
    timeLimitMinutes: Int? = 120
) = MeteredZone(
    id = "1#1",
    postId = "1",
    lat = 0.0,
    lng = 0.0,
    streetName = "Test St",
    days = days,
    hrsBegin = hrsBegin,
    hrsEnd = hrsEnd,
    timeLimitMinutes = timeLimitMinutes
)

class MeterStatusTest {

    @Test
    fun parseMeterDays_commaSeparatedWeekdays() {
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            parseMeterDays("Mo,Tu,We,Th,Fr")
        )
    }

    @Test
    fun parseMeterDays_singleDay() {
        assertEquals(setOf(DayOfWeek.SATURDAY), parseMeterDays("Sa"))
        assertEquals(setOf(DayOfWeek.SUNDAY), parseMeterDays("Su"))
    }

    @Test
    fun parseMeterDays_garbageReturnsEmpty() {
        assertEquals(emptySet<DayOfWeek>(), parseMeterDays("garbage"))
    }

    @Test
    fun parseMeterTimeToMilitary_morningAndEvening() {
        assertEquals(700, parseMeterTimeToMilitary("7:00 AM"))
        assertEquals(1800, parseMeterTimeToMilitary("6:00 PM"))
    }

    @Test
    fun parseMeterTimeToMilitary_noonAndMidnight() {
        assertEquals(1200, parseMeterTimeToMilitary("12:00 PM"))
        assertEquals(0, parseMeterTimeToMilitary("12:00 AM"))
    }

    @Test
    fun parseMeterTimeToMilitary_garbageReturnsNull() {
        assertNull(parseMeterTimeToMilitary("noon"))
        assertNull(parseMeterTimeToMilitary(""))
    }

    @Test
    fun parseMeterTimeLimitMinutes_minutesAndHours() {
        assertEquals(60, parseMeterTimeLimitMinutes("60 minutes"))
        assertEquals(120, parseMeterTimeLimitMinutes("2 hours"))
        assertEquals(240, parseMeterTimeLimitMinutes("4 Hour"))
    }

    @Test
    fun parseMeterTimeLimitMinutes_nullOrGarbage() {
        assertNull(parseMeterTimeLimitMinutes(null))
        assertNull(parseMeterTimeLimitMinutes("unlimited"))
    }

    @Test
    fun isMeterEnforcedOrUnknown_insideWindowOnEnforcedDay() {
        val zone = dummyZone(days = "Mo,Tu,We,Th,Fr", hrsBegin = 900, hrsEnd = 1800)
        val monday1pm = LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(13, 0)) // a Monday
        assertTrue(isMeterEnforcedOrUnknown(zone, monday1pm))
    }

    @Test
    fun isMeterEnforcedOrUnknown_outsideWindow() {
        val zone = dummyZone(days = "Mo,Tu,We,Th,Fr", hrsBegin = 900, hrsEnd = 1800)
        val mondayEarly = LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(7, 0))
        assertFalse(isMeterEnforcedOrUnknown(zone, mondayEarly))
    }

    @Test
    fun isMeterEnforcedOrUnknown_wrongDay() {
        val zone = dummyZone(days = "Sa", hrsBegin = 900, hrsEnd = 1800)
        val monday1pm = LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(13, 0)) // a Monday
        assertFalse(isMeterEnforcedOrUnknown(zone, monday1pm))
    }

    @Test
    fun isMeterEnforcedOrUnknown_unknownHoursAlwaysTrue() {
        val zone = dummyZone(days = null, hrsBegin = null, hrsEnd = null)
        val anytime = LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(3, 0))
        assertTrue(isMeterEnforcedOrUnknown(zone, anytime))
    }
}
