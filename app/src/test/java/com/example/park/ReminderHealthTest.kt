package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderHealthTest {

    private val on = 3   // IMPORTANCE_DEFAULT-ish: any value above NONE means "not blocked"
    private val off = 0  // IMPORTANCE_NONE

    @Test
    fun everythingAllowed_isOk() {
        val state = evaluateReminderHealth(true, on, on, on)
        assertEquals(ReminderHealth.OK, state.health)
        assertTrue(state.isHealthy)
        assertNull(state.blockedChannelId)
        assertFalse(state.statusChannelBlocked)
    }

    @Test
    fun appLevelNotificationsOff_isNotificationsBlocked() {
        val state = evaluateReminderHealth(false, on, on, on)
        assertEquals(ReminderHealth.NOTIFICATIONS_BLOCKED, state.health)
        assertFalse(state.isHealthy)
    }

    @Test
    fun appLevelBlockWinsOverAChannelBlock() {
        // With the whole app silenced, the fix is the app's notification settings, not one channel's.
        assertEquals(ReminderHealth.NOTIFICATIONS_BLOCKED, evaluateReminderHealth(false, off, off, off).health)
    }

    @Test
    fun theRemindersChannelBlocked_isReminderChannelBlocked_andNamesThatChannel() {
        val state = evaluateReminderHealth(true, off, on, on)
        assertEquals(ReminderHealth.REMINDER_CHANNEL_BLOCKED, state.health)
        assertEquals(NotificationHelper.CHANNEL_ID_NORMAL, state.blockedChannelId)
    }

    @Test
    fun theUrgentChannelBlocked_alsoCounts_becauseUrgentRemindersUseIt() {
        val state = evaluateReminderHealth(true, on, off, on)
        assertEquals(ReminderHealth.REMINDER_CHANNEL_BLOCKED, state.health)
        assertEquals(NotificationHelper.CHANNEL_ID_URGENT, state.blockedChannelId)
    }

    @Test
    fun bothReminderChannelsBlocked_reportsTheNormalOneFirst() {
        assertEquals(NotificationHelper.CHANNEL_ID_NORMAL, evaluateReminderHealth(true, off, off, on).blockedChannelId)
    }

    @Test
    fun aMissingChannel_isNotBlocked() {
        // Channels are created when the app starts; "not created yet" must not raise an alarm.
        assertEquals(ReminderHealth.OK, evaluateReminderHealth(true, null, null, null).health)
        assertEquals(ReminderHealth.OK, evaluateReminderHealth(true, on, null, null).health)
    }

    @Test
    fun theStatusChannelBlocked_isReportedButIsNeverTheBanner() {
        val state = evaluateReminderHealth(true, on, on, off)
        assertEquals(ReminderHealth.OK, state.health)
        assertTrue(state.statusChannelBlocked)
        // ...and it is still carried along when something more serious is wrong.
        assertTrue(evaluateReminderHealth(false, on, on, off).statusChannelBlocked)
    }

    @Test
    fun anyImportanceAboveNone_countsAsAllowed() {
        for (importance in listOf(1, 2, 3, 4, 5)) {   // MIN, LOW, DEFAULT, HIGH, MAX
            assertEquals("importance $importance", ReminderHealth.OK, evaluateReminderHealth(true, importance, importance).health)
        }
    }

    @Test
    fun theBannerTextMatchesTheProblem() {
        assertEquals("", reminderHealthMessage(evaluateReminderHealth(true, on, on)))
        assertEquals("Notifications are off — Park can't show sweep reminders", reminderHealthMessage(evaluateReminderHealth(false, on, on)))
        assertEquals("The Park reminders channel is off — sweep reminders won't show", reminderHealthMessage(evaluateReminderHealth(true, off, on)))
        assertTrue(reminderHealthMessage(evaluateReminderHealth(true, on, off)).contains("urgent"))
    }

    @Test
    fun channelNamesForTheSettingsRow() {
        assertEquals("Parking Reminders", reminderChannelName(NotificationHelper.CHANNEL_ID_NORMAL))
        assertEquals("Urgent: Move Your Car", reminderChannelName(NotificationHelper.CHANNEL_ID_URGENT))
        assertEquals("Parking status", reminderChannelName(NotificationHelper.CHANNEL_ID_STATUS))
    }
}
