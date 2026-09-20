package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired when the user taps "Snooze 10 min" on either reminder tier: dismisses the current
 * notification and reschedules the same reminder [SNOOZE_MINUTES] later via
 * [snoozeReminder].
 */
class SnoozeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Logged under the same "Park" tag NotificationScheduler already uses for its own
        // exact-alarm warning — added specifically because this receiver previously had zero
        // logging at all, making "the button does nothing" impossible to tell apart from "the
        // receiver never even ran" (a manifest/delivery problem) versus "it ran but something
        // inside silently no-op'd." If this line never shows up in Logcat when Snooze is
        // tapped, the receiver isn't being invoked at all — check AndroidManifest.xml's
        // <receiver> entry for this class next, not this file.
        Log.d("Park", "SnoozeReminderReceiver.onReceive fired")

        val notificationId = intent.getIntExtra("notificationId", -1)
        val carId = intent.getLongExtra("carId", -1L)
        val carName = intent.getStringExtra("carName") ?: "Your car"
        val corridor = intent.getStringExtra("corridor") ?: "your parked street"
        val nextSweepAtMillis = intent.getLongExtra("nextSweepAtMillis", -1L)
        val kind = intent.getStringExtra("kind")
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
            ?: ReminderKind.NORMAL

        if (carId < 0 || nextSweepAtMillis <= 0) {
            Log.w("Park", "SnoozeReminderReceiver: missing/invalid extras (carId=$carId, nextSweepAtMillis=$nextSweepAtMillis) — aborting")
            return
        }

        Log.d("Park", "SnoozeReminderReceiver: carId=$carId kind=$kind — cancelling current notification and rescheduling")
        // Cancel by the notificationId the notification was actually posted under (passed
        // through as an extra), not by recomputing reminderNotificationId(carId, kind) — the
        // two only coincide for real scheduled reminders. The Settings "Test normal"/"Test
        // urgent" buttons post under fixed IDs with a fake carId, so recomputing here used to
        // target a notification ID that was never posted, leaving the real one stuck on screen
        // with no visible sign that Snooze had done anything.
        if (notificationId != -1) {
            NotificationHelper.cancel(context, notificationId)
        } else {
            NotificationHelper.cancel(context, reminderNotificationId(carId, kind))
        }
        snoozeReminder(context, carId, carName, corridor, nextSweepAtMillis, kind)
    }
}
