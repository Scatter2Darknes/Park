package com.example.park

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Can this app actually SHOW its reminders? Alarms can be scheduled perfectly and still produce nothing on screen if
 * Android is dropping the app's notifications — `notify()` silently does nothing when notifications are blocked. That
 * is worse than the exact-alarm problem (reminders merely late): here a parked car gets no visible reminder at all, and
 * nothing in the app said so. This is the detection; the map banner and the Settings row show it, and the receiver logs
 * it when it is about to post into the void.
 *
 * Two independent switches can block them:
 *  - the APP-level setting (the Android 13+ runtime permission POST_NOTIFICATIONS, or the older per-app toggle), and
 *  - a single notification CHANNEL set to "off" (importance NONE). The reminder tiers use two channels
 *    ("Parking Reminders" and "Urgent: Move Your Car"), so either being off silences part of the reminders.
 * The quiet "Parking status" channel only carries informational notices, so it is reported but is NOT a reason for the
 * red banner.
 */
enum class ReminderHealth { OK, NOTIFICATIONS_BLOCKED, REMINDER_CHANNEL_BLOCKED }

data class ReminderHealthState(
    val health: ReminderHealth,
    /** For REMINDER_CHANNEL_BLOCKED: the first blocked reminder channel (the one to open in Settings). */
    val blockedChannelId: String? = null,
    /** The informational "Parking status" channel is off. Lower severity: shown in Settings only, never as the banner. */
    val statusChannelBlocked: Boolean = false
) {
    val isHealthy: Boolean get() = health == ReminderHealth.OK
}

/** A channel that has been switched off reports this importance. */
private const val CHANNEL_OFF = 0 // NotificationManager.IMPORTANCE_NONE

/**
 * Pure decision, so it can be unit tested on the JVM. A channel importance of null means the channel doesn't exist
 * (yet) — that is not "blocked": it is created when the app starts.
 *
 * App-level blocking wins over channel-level (everything is silenced, so the fix is the app's notification settings);
 * then the normal reminders channel, then the urgent one.
 */
fun evaluateReminderHealth(
    appNotificationsEnabled: Boolean,
    normalChannelImportance: Int?,
    urgentChannelImportance: Int?,
    statusChannelImportance: Int? = null
): ReminderHealthState {
    val statusBlocked = statusChannelImportance == CHANNEL_OFF
    return when {
        !appNotificationsEnabled ->
            ReminderHealthState(ReminderHealth.NOTIFICATIONS_BLOCKED, statusChannelBlocked = statusBlocked)
        normalChannelImportance == CHANNEL_OFF ->
            ReminderHealthState(ReminderHealth.REMINDER_CHANNEL_BLOCKED, NotificationHelper.CHANNEL_ID_NORMAL, statusBlocked)
        urgentChannelImportance == CHANNEL_OFF ->
            ReminderHealthState(ReminderHealth.REMINDER_CHANNEL_BLOCKED, NotificationHelper.CHANNEL_ID_URGENT, statusBlocked)
        else -> ReminderHealthState(ReminderHealth.OK, statusChannelBlocked = statusBlocked)
    }
}

/** Reads the real settings from the system. Cheap; call it when a screen resumes rather than polling. */
fun currentReminderHealth(context: Context): ReminderHealthState {
    val manager = NotificationManagerCompat.from(context)
    fun importanceOf(channelId: String): Int? = manager.getNotificationChannelCompat(channelId)?.importance
    return evaluateReminderHealth(
        appNotificationsEnabled = manager.areNotificationsEnabled(),
        normalChannelImportance = importanceOf(NotificationHelper.CHANNEL_ID_NORMAL),
        urgentChannelImportance = importanceOf(NotificationHelper.CHANNEL_ID_URGENT),
        statusChannelImportance = importanceOf(NotificationHelper.CHANNEL_ID_STATUS)
    )
}

/** Banner text for the map. */
fun reminderHealthMessage(state: ReminderHealthState): String = when (state.health) {
    ReminderHealth.OK -> ""
    ReminderHealth.NOTIFICATIONS_BLOCKED -> "Notifications are off — Park can't show sweep reminders"
    ReminderHealth.REMINDER_CHANNEL_BLOCKED -> when (state.blockedChannelId) {
        NotificationHelper.CHANNEL_ID_URGENT -> "The Park urgent-reminders channel is off — urgent reminders won't show"
        else -> "The Park reminders channel is off — sweep reminders won't show"
    }
}

/** Human name of a blocked channel, for the Settings row. */
fun reminderChannelName(channelId: String?): String = when (channelId) {
    NotificationHelper.CHANNEL_ID_URGENT -> "Urgent: Move Your Car"
    NotificationHelper.CHANNEL_ID_STATUS -> "Parking status"
    else -> "Parking Reminders"
}

/**
 * Opens the system screen where the block can be lifted: the channel's own page when only a channel is off, otherwise
 * the app's notification settings. (Android can't switch notifications back on from inside the app.)
 */
fun openReminderHealthSettings(context: Context, state: ReminderHealthState) {
    if (state.health == ReminderHealth.REMINDER_CHANNEL_BLOCKED && state.blockedChannelId != null) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, state.blockedChannelId)
        }
        context.startActivity(intent)
    } else {
        openAppNotificationSettings(context)
    }
}
