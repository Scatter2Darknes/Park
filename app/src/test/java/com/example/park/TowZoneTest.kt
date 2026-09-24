package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Parsing of the SFMTA tow-zone feed (6r5h-j298) and the enforcement-window math. Rows copied from the live feed (2026-09-24). */
class TowZoneTest {

    private val liveRow = """
        {":id":"row-abc","address":"FELL ST (100-299)","casenumber":"1541850","cnn":"5440000,5441000",
         "datetimeentered":"2026-06-11T15:13:57.000","enddate":"2026-09-16T00:00:00.000","endtime":"3:00 PM",
         "notes":"Monday - Friday","permitnumber":"26-1599","startdate":"2026-08-19T00:00:00.000","starttime":"7:00 AM",
         "streetfrontagefrom":"VAN NESS AVE","streetfrontagename":"FELL ST","streetfrontageto":"GOUGH ST",
         "_24hourenforcement":"No"}
    """.trimIndent()

    private val allDayRow = """
        {":id":"row-pge","address":"BRYANT ST (414-416)","casenumber":"1562689","cnn":"3280000",
         "enddate":"2026-10-01T00:00:00.000","endtime":"11:59 PM","notes":"Monday - Thursday",
         "startdate":"2026-09-01T00:00:00.000","starttime":"12:00 AM","_24hourenforcement":"Yes"}
    """.trimIndent()

    // --- Parsing ---

    @Test
    fun parsesALiveRow() {
        val z = parseTowZones("[$liveRow]", syncId = 7L).single()
        assertEquals("row-abc", z.rowId)
        assertEquals(",5440000,5441000,", z.cnns)
        assertEquals(LocalDate.of(2026, 8, 19).toEpochDay(), z.startEpochDay)
        assertEquals(LocalDate.of(2026, 9, 16).toEpochDay(), z.endEpochDay)
        assertEquals(7 * 60, z.startMinute)
        assertEquals(15 * 60, z.endMinute)
        assertFalse(z.allDay)
        assertEquals("FELL ST", z.streetName)
        assertEquals(7L, z.lastSeenSyncId)
        assertTrue(z.enforcesOn(DayOfWeek.FRIDAY))
        assertFalse(z.enforcesOn(DayOfWeek.SATURDAY))
    }

    @Test
    fun twentyFourHourFlag_makesItAllDay() {
        assertTrue(parseTowZones("[$allDayRow]", null).single().allDay)
    }

    @Test
    fun rowsWithoutIdCnnOrDates_areDropped_butOthersOnThePageSurvive() {
        val noCnn = liveRow.replace("\"cnn\":\"5440000,5441000\"", "\"cnn\":\"\"")
        val noId = liveRow.replace("\":id\":\"row-abc\",", "")
        val noEnd = liveRow.replace("\"enddate\":\"2026-09-16T00:00:00.000\",", "")
        val zones = parseTowZones("[$noCnn,$noId,$noEnd,$allDayRow]", null)
        assertEquals(listOf("row-pge"), zones.map { it.rowId })
    }

    @Test
    fun unreadableHours_meanAllDay() {
        val z = parseTowZones("[${liveRow.replace("\"starttime\":\"7:00 AM\"", "\"starttime\":\"soon\"")}]", null).single()
        assertTrue(z.allDay)
    }

    @Test
    fun newestEntry_isReadAsSfLocalTime() {
        val millis = parseTowNewestEntry("""[{"newest":"2026-07-20T17:01:33.000"}]""")
        assertEquals(LocalDateTime.of(2026, 7, 20, 17, 1, 33).atZone(SF_ZONE).toInstant().toEpochMilli(), millis)
        assertNull(parseTowNewestEntry("""[{}]"""))
    }

    @Test
    fun whereClause_filtersByDateOnly() {
        assertEquals("enddate >= '2026-09-23T00:00:00'", towWhereClause(LocalDate.of(2026, 9, 23)))
    }

    @Test
    fun cnnList_isCommaWrapped_soOneBlockCantMatchAnother() {
        assertEquals(",1,22,", towCnnList(listOf(" 1", "", "22 ")))
    }

    // --- Days ---

    private fun days(vararg d: DayOfWeek) = d.fold(0) { m, day -> m or (1 shl (day.value - 1)) }

    @Test
    fun days_rangesListsAndSingles() {
        assertEquals(days(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), parseTowDays("Monday - Friday"))
        assertEquals(ALL_DAYS_MASK, parseTowDays("Monday - Sunday"))
        assertEquals(days(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), parseTowDays("Monday,Tuesday"))
        assertEquals(days(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), parseTowDays("Thursday, Friday"))
        assertEquals(days(DayOfWeek.SATURDAY), parseTowDays("Saturday"))
        assertEquals(days(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), parseTowDays("Monday, Friday, Saturday, Sunday"))
    }

    @Test
    fun days_rangesWrapPastSunday() {
        assertEquals(days(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), parseTowDays("Friday - Wednesday"))
        assertEquals(days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY), parseTowDays("Saturday - Monday"))
    }

    @Test
    fun days_blankOrUnreadable_meansEveryDay() {
        assertEquals(ALL_DAYS_MASK, parseTowDays(null))
        assertEquals(ALL_DAYS_MASK, parseTowDays(""))
        assertEquals(ALL_DAYS_MASK, parseTowDays("weekdays except holidays"))
        assertEquals(ALL_DAYS_MASK, parseTowDays("Monday, Funday"))
    }

    @Test
    fun clockTimes() {
        assertEquals(0, parseTowClockTime("12:00 AM"))
        assertEquals(7 * 60 + 30, parseTowClockTime("7:30 AM"))
        assertEquals(12 * 60, parseTowClockTime("12:00 PM"))
        assertEquals(23 * 60 + 59, parseTowClockTime("11:59 PM"))
        assertNull(parseTowClockTime("25:00 PM"))
        assertNull(parseTowClockTime(null))
    }

    // --- Windows ---

    private fun zone(
        start: LocalDate, end: LocalDate, startMinute: Int, endMinute: Int,
        allDay: Boolean = false, mask: Int = ALL_DAYS_MASK
    ) = TowZone(
        rowId = "z", caseNumber = null, permitNumber = null, cnns = ",1,", address = null, streetName = null,
        fromStreet = null, toStreet = null, startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay(),
        startMinute = startMinute, endMinute = endMinute, allDay = allDay, daysMask = mask, daysText = null, enteredMillis = null
    )

    private val tue = LocalDate.of(2026, 9, 22) // a Tuesday

    @Test
    fun dailyWindow_nextStartSkipsDaysNotListed() {
        val weekdays = zone(tue, tue.plusDays(10), 7 * 60, 17 * 60, mask = parseTowDays("Monday - Friday"))
        val fridayEvening = LocalDateTime.of(2026, 9, 25, 18, 0)
        // Next window after Friday evening is Monday 7am, not Saturday.
        assertEquals(LocalDateTime.of(2026, 9, 28, 7, 0), weekdays.nextWindowStartingAfter(fridayEvening)!!.start)
    }

    @Test
    fun beforeTheZoneStarts_theFirstWindowIsTheFirstEnforcedDay() {
        val z = zone(tue, tue.plusDays(3), 7 * 60, 17 * 60)
        assertEquals(LocalDateTime.of(2026, 9, 22, 7, 0), z.firstWindow()!!.start)
        assertEquals(LocalDateTime.of(2026, 9, 22, 7, 0), z.nextWindowStartingAfter(LocalDateTime.of(2026, 9, 1, 9, 0))!!.start)
    }

    @Test
    fun afterTheLastDay_thereIsNoNextWindow() {
        val z = zone(tue, tue, 7 * 60, 17 * 60)
        assertNull(z.nextWindowStartingAfter(LocalDateTime.of(2026, 9, 22, 7, 0)))
    }

    @Test
    fun windowContaining_endIsExclusive() {
        val z = zone(tue, tue, 7 * 60, 17 * 60)
        assertTrue(z.windowContaining(LocalDateTime.of(2026, 9, 22, 7, 0)) != null)
        assertNull(z.windowContaining(LocalDateTime.of(2026, 9, 22, 17, 0)))
        assertNull(z.windowContaining(LocalDateTime.of(2026, 9, 22, 6, 59)))
    }

    @Test
    fun elevenFiftyNinePm_meansUntilMidnight() {
        val z = zone(tue, tue, 7 * 60, 23 * 60 + 59)
        assertEquals(tue.plusDays(1).atStartOfDay(), z.windowOn(tue)!!.end)
        assertTrue(z.windowContaining(LocalDateTime.of(2026, 9, 22, 23, 59, 30)) != null)
    }

    @Test
    fun overnightWindow_runsIntoTheNextDay_evenPastTheLastDate() {
        val z = zone(tue, tue, 19 * 60, 7 * 60) // 7 PM - 7 AM, one night
        val w = z.windowOn(tue)!!
        assertEquals(LocalDateTime.of(2026, 9, 23, 7, 0), w.end)
        assertTrue(z.windowContaining(LocalDateTime.of(2026, 9, 23, 3, 0)) != null)
    }

    @Test
    fun equalStartAndEnd_isAFull24Hours() {
        val z = zone(tue, tue, 5 * 60, 5 * 60)
        assertEquals(LocalDateTime.of(2026, 9, 23, 5, 0), z.windowOn(tue)!!.end)
    }

    @Test
    fun allDay_coversTheWholeDate() {
        val z = zone(tue, tue.plusDays(1), 0, 0, allDay = true)
        assertEquals(tue.atStartOfDay(), z.windowOn(tue)!!.start)
        assertEquals(tue.plusDays(1).atStartOfDay(), z.windowOn(tue)!!.end)
    }

    @Test
    fun aZoneWithNoListedWeekdayInItsDates_neverEnforces() {
        val saturdayOnly = zone(tue, tue.plusDays(2), 7 * 60, 17 * 60, mask = parseTowDays("Saturday"))
        assertNull(saturdayOnly.firstWindow())
    }
}
