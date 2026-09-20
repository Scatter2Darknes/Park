package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired when the user taps "I moved my car" on an urgent (ongoing) reminder — the only way
 * to clear it, since it's deliberately not swipe-dismissible.
 */
class DismissReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Same reasoning as SnoozeReminderReceiver's logging — if this line never appears in
        // Logcat when "I moved my car" is tapped, the receiver isn't being invoked at all
        // (check AndroidManifest.xml's <receiver> entry for this class), as opposed to
        // running and the cancel() call itself having no visible effect for some other reason.
        Log.d("Park", "DismissReminderReceiver.onReceive fired")
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId != -1) {
            NotificationHelper.cancel(context, notificationId)
            Log.d("Park", "DismissReminderReceiver: cancelled notificationId=$notificationId")
        } else {
            Log.w("Park", "DismissReminderReceiver: no notificationId extra \u2014 nothing to cancel")
        }
    }
}