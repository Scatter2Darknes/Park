package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    /** Car ids that matter: the smallest, some ordinary ones, the largest real id, and the reserved fake test id. */
    private val carIds = listOf(0L, 1L, 2L, 7L, 999L, 123_456L, NotificationIds.MAX_REAL_CAR_ID, NotificationIds.TEST_CAR_ID)

    @Test
    fun everyPurposeForEveryCar_producesADistinctId() {
        val seen = HashMap<Int, String>()
        for (purpose in NotificationIds.Purpose.entries) for (carId in carIds) {
            val id = NotificationIds.forCar(carId, purpose)
            val who = "$purpose/car $carId"
            assertFalse("$who collides with ${seen[id]} at id $id", seen.containsKey(id))
            seen[id] = who
        }
        assertEquals(NotificationIds.Purpose.entries.size * carIds.size, seen.size)
    }

    @Test
    fun aRangeNeverSpillsIntoTheNextPurposesRange_evenAtTheLargestCarId() {
        val purposes = NotificationIds.Purpose.entries.sortedBy { it.offset }
        for (i in 0 until purposes.size - 1) {
            val highestInThisRange = NotificationIds.forCar(NotificationIds.SPAN - 1L, purposes[i])
            assertTrue(
                "${purposes[i]} at the largest car id must stay below ${purposes[i + 1]}'s first id",
                highestInThisRange < purposes[i + 1].offset
            )
        }
    }

    @Test
    fun offsetsAreEvenlySpacedBySpan_andTheOldReminderOffsetsAreUnchanged() {
        // Alarms scheduled by an earlier version must still match the codes used to cancel/replace them.
        assertEquals(0, NotificationIds.Purpose.REMINDER_NORMAL.offset)
        assertEquals(1_000_000, NotificationIds.Purpose.REMINDER_URGENT.offset)
        assertEquals(2_000_000, NotificationIds.Purpose.RPP_NORMAL.offset)
        assertEquals(3_000_000, NotificationIds.Purpose.RPP_URGENT.offset)
        assertEquals(4_000_000, NotificationIds.Purpose.ROLL_FORWARD_SWEEP.offset)
        assertEquals(5_000_000, NotificationIds.Purpose.ROLL_FORWARD_RPP.offset)
        assertEquals(6_000_000, NotificationIds.Purpose.SWEEP_ACTIVE.offset)
        NotificationIds.Purpose.entries.forEach { assertEquals(0, it.offset % NotificationIds.SPAN) }
    }

    @Test
    fun theBluetoothIdsNoLongerCollideWithTheRppReminderIds() {
        for (carId in carIds) {
            val detect = NotificationIds.forCar(carId, NotificationIds.Purpose.BLUETOOTH_AUTO_DETECT)
            val unpark = NotificationIds.forCar(carId, NotificationIds.Purpose.BLUETOOTH_AUTO_UNPARK)
            assertNotEquals(NotificationIds.forCar(carId, NotificationIds.Purpose.RPP_NORMAL), detect)
            assertNotEquals(NotificationIds.forCar(carId, NotificationIds.Purpose.RPP_URGENT), unpark)
            assertNotEquals(detect, unpark)
        }
    }

    @Test
    fun reminderRequestCodeAndNotificationId_matchTheirPurposeIds() {
        assertEquals(NotificationIds.forCar(5, NotificationIds.Purpose.REMINDER_URGENT), reminderRequestCode(5, ReminderKind.URGENT))
        assertEquals(NotificationIds.forCar(5, NotificationIds.Purpose.RPP_NORMAL), reminderNotificationId(5, ReminderKind.RPP_NORMAL))
        assertEquals(NotificationIds.forCar(5, NotificationIds.Purpose.SWEEP_ACTIVE), reminderNotificationId(5, ReminderKind.SWEEP_ACTIVE))
    }

    @Test
    fun theSettingsTestNotificationIds_neverEqualARealCarsId() {
        val testIds = setOf(
            NotificationIds.TEST_NORMAL, NotificationIds.TEST_URGENT,
            NotificationIds.TEST_RPP_NORMAL, NotificationIds.TEST_RPP_URGENT
        )
        assertEquals(4, testIds.size)
        // Real car ids are 0..MAX_REAL_CAR_ID, so in the first range they reach at most MAX_REAL_CAR_ID.
        for (id in testIds) assertTrue(id > NotificationIds.MAX_REAL_CAR_ID)
        // ...and nothing in any OTHER purpose's range can equal them either (they're below the second range).
        for (purpose in NotificationIds.Purpose.entries.filter { it.offset > 0 }) {
            for (id in testIds) assertTrue(id < purpose.offset)
        }
        // The fake car id is valid for the helper (so Snooze on a test notification doesn't crash) yet not a real car.
        assertTrue(NotificationIds.TEST_CAR_ID > NotificationIds.MAX_REAL_CAR_ID)
        NotificationIds.forCar(NotificationIds.TEST_CAR_ID, NotificationIds.Purpose.REMINDER_URGENT)
    }

    @Test
    fun aCarIdOutsideTheRange_isRejectedWithAClearMessage() {
        val tooBig = assertThrows(IllegalArgumentException::class.java) {
            NotificationIds.forCar(NotificationIds.SPAN.toLong(), NotificationIds.Purpose.REMINDER_NORMAL)
        }
        assertTrue(tooBig.message!!.contains("outside"))
        assertThrows(IllegalArgumentException::class.java) {
            NotificationIds.forCar(-1L, NotificationIds.Purpose.RPP_URGENT) // getLongExtra's "missing" default
        }
        // The old fake test car id (999_999_999) is now out of range — the Settings buttons use TEST_CAR_ID instead.
        assertThrows(IllegalArgumentException::class.java) {
            NotificationIds.forCar(999_999_999L, NotificationIds.Purpose.REMINDER_NORMAL)
        }
    }
}
