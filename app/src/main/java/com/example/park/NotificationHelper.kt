package com.example.park

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// RPP_NORMAL/RPP_URGENT reuse the exact same channels/urgency treatment as their sweep
// counterparts (see channelId/ongoing-notification logic below) — the two are otherwise
// identical, just for a different deadline (RPP's non-permit time limit rather than a sweep
// start time), so a separate pair of channels would add nothing besides more settings surface.
enum class ReminderKind { NORMAL, URGENT, RPP_NORMAL, RPP_URGENT }

object NotificationHelper {
    const val CHANNEL_ID_NORMAL = "parking_reminders"
    const val CHANNEL_ID_URGENT = "parking_urgent_reminders"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val normalChannel = NotificationChannel(
            CHANNEL_ID_NORMAL,
            "Parking Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Early heads-up that street cleaning is coming up"
        }

        val urgentChannel = NotificationChannel(
            CHANNEL_ID_URGENT,
            "Urgent: Move Your Car",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Street cleaning is imminent — stays until you dismiss it"
        }

        manager.createNotificationChannel(normalChannel)
        manager.createNotificationChannel(urgentChannel)
    }

    /**
     * @param carId Needed (in addition to the already-formatted title/text) so tapping the
     *   notification opens the app centered on that car's parked spot — see the
     *   "reminderCarId" extra below and MainActivity's handling of it.
     */
    fun showReminder(
        context: Context,
        notificationId: Int,
        kind: ReminderKind,
        title: String,
        text: String,
        carId: Long,
        carName: String,
        corridor: String,
        nextSweepAtMillis: Long
    ) {
        val isUrgent = kind == ReminderKind.URGENT || kind == ReminderKind.RPP_URGENT
        val channelId = if (isUrgent) CHANNEL_ID_URGENT else CHANNEL_ID_NORMAL
        android.util.Log.d("Park", "showReminder: notificationId=$notificationId kind=$kind carId=$carId \u2014 building notification")
        // Per-channel enablement is separate from the app-level POST_NOTIFICATIONS
        // permission check below — Android lets someone disable "Parking Reminders" while
        // leaving "Urgent: Move Your Car" on (or vice versa), which would explain one tier's
        // test button working and the other's silently not, even with the app's overall
        // notification permission granted.
        val channelImportance = NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId)?.importance
        if (channelImportance == NotificationManagerCompat.IMPORTANCE_NONE) {
            android.util.Log.w("Park", "showReminder: channel $channelId is disabled (importance=NONE) \u2014 this notification will not show regardless of app-level permission")
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // placeholder icon — swap for a real app icon later
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)) // protects longer corridor names from truncating
        // No .setPriority() here — it only has any effect below API 26, and minSdk is
        // already 29, so channel importance (set above, IMPORTANCE_HIGH either way) is
        // the only thing that's ever actually governed this. Removed rather than left as
        // a harmless-looking call that's really just dead weight.

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminderCarId", carId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(contentPendingIntent)

        val snoozeIntent = Intent(context, SnoozeReminderReceiver::class.java).apply {
            putExtra("notificationId", notificationId)
            putExtra("carId", carId)
            putExtra("carName", carName)
            putExtra("corridor", corridor)
            putExtra("nextSweepAtMillis", nextSweepAtMillis)
            putExtra("kind", kind.name)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze 10 min", snoozePendingIntent)

        if (isUrgent) {
            val dismissIntent = Intent(context, DismissReminderReceiver::class.java).apply {
                putExtra("notificationId", notificationId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "I moved my car", dismissPendingIntent)

            // Ongoing (can't be swiped away by accident) — cleared only via a deliberate tap
            // or the "I moved my car" action above.
            builder.setOngoing(true)
            builder.setAutoCancel(true)
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
            builder.setAutoCancel(true)
        }

        val notification = builder.build()

        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            android.util.Log.d("Park", "showReminder: notify() called for notificationId=$notificationId")
        } else {
            // The most likely explanation for "the test button does nothing" — this check
            // fails completely silently otherwise, with no log, no toast, no exception.
            // NotificationManagerCompat.areNotificationsEnabled() additionally catches the
            // "permission granted but the person (or Android) disabled this specific
            // notification channel" case, which the permission check alone can't see.
            android.util.Log.w(
                "Park",
                "showReminder: notificationId=$notificationId NOT shown \u2014 " +
                        "POST_NOTIFICATIONS not granted (areNotificationsEnabled=" +
                        "${NotificationManagerCompat.from(context).areNotificationsEnabled()})"
            )
        }
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}