package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Delivery markers for RPP reminders compare against a deadline that is NOT stored anywhere — it's
 * recomputed by nextRppDeadline each time (see NotificationScheduler.currentRppDeadlineMillis).
 * For that comparison to mean anything, the deadline recomputed at re-arm time ("from" = now,
 * some time after parking) must equal the one computed at park time ("from" = the park time).
 * These tests pin that down.
 */
class RppRearmDeterminismTest {

    private val regulation = RppZoneRegulation(
        objectId = "1", zoneLetters = "A", days = "M-F",
        hrsBegin = 800, hrsEnd = 1800, hrLimit = 2.0f,
        points = emptyList(), centroidLat = 0.0, centroidLng = 0.0
    )
    private val car = Car(name = "Test Car")

    /** The deadline as saveParkedState computes it at park time. */
    private fun atParkTime(parkedAt: LocalDateTime) =
        nextRppDeadline(regulation, car, parkedSince = parkedAt, from = parkedAt)?.moveByDateTime

    /** The deadline as a later re-arm computes it. */
    private fun atRearm(parkedAt: LocalDateTime, now: LocalDateTime) =
        nextRppDeadline(regulation, car, parkedSince = parkedAt, from = now)?.moveByDateTime

    @Test
    fun parkedDuringWindow_sameDeadlineBeforeAndAfterItPasses() {
        val parkedAt = LocalDateTime.of(2026, 9, 14, 9, 0) // Monday 9am -> deadline 11am
        val expected = LocalDateTime.of(2026, 9, 14, 11, 0)
        assertEquals(expected, atParkTime(parkedAt))
        for (now in listOf(
            LocalDateTime.of(2026, 9, 14, 9, 30),  // reboot before the reminders
            LocalDateTime.of(2026, 9, 14, 10, 59), // just before the deadline
            LocalDateTime.of(2026, 9, 14, 11, 30), // deadline already passed, window still open
            LocalDateTime.of(2026, 9, 14, 17, 59)  // last minute of the window
        )) {
            assertEquals("re-arm at $now", expected, atRearm(parkedAt, now))
        }
    }

    @Test
    fun parkedBeforeWindowOpens_sameDeadlineWhenRecomputedLater() {
        val parkedAt = LocalDateTime.of(2026, 9, 14, 6, 0) // overnight; clock starts at 8am -> 10am
        val expected = LocalDateTime.of(2026, 9, 14, 10, 0)
        assertEquals(expected, atParkTime(parkedAt))
        for (now in listOf(
            LocalDateTime.of(2026, 9, 14, 7, 0),
            LocalDateTime.of(2026, 9, 14, 8, 30),
            LocalDateTime.of(2026, 9, 14, 12, 0)
        )) {
            assertEquals("re-arm at $now", expected, atRearm(parkedAt, now))
        }
    }

    @Test
    fun parkedOnUnenforcedDay_sameDeadlineWhenRecomputedLater() {
        val parkedAt = LocalDateTime.of(2026, 9, 19, 10, 0) // Saturday -> Monday 8am + 2h
        val expected = LocalDateTime.of(2026, 9, 21, 10, 0)
        assertEquals(expected, atParkTime(parkedAt))
        assertEquals(expected, atRearm(parkedAt, LocalDateTime.of(2026, 9, 20, 15, 0))) // Sunday
    }

    @Test
    fun onceTheWindowClosesTheDeadlineRollsToTheNextEnforcedDay() {
        // Documents the boundary of the determinism above: after that day's window has closed,
        // re-arm computes the NEXT day's deadline, which no longer equals the marker recorded for
        // the earlier one — so tomorrow's reminders are armed rather than treated as delivered.
        val parkedAt = LocalDateTime.of(2026, 9, 14, 9, 0)
        val original = atParkTime(parkedAt)
        val afterClose = atRearm(parkedAt, LocalDateTime.of(2026, 9, 14, 19, 0))
        assertNotEquals(original, afterClose)
        assertEquals(LocalDateTime.of(2026, 9, 15, 10, 0), afterClose)
    }
}
