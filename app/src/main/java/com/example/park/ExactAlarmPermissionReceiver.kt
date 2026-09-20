package com.example.park

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Upgrades reminders from the inexact fallback to exact alarms the moment the user grants the
 * "Alarms & reminders" access, without the app being opened. Android sends this broadcast to the
 * app when the permission is GRANTED.
 *
 * Revoking is handled differently by the system: it stops the app and cancels all its exact
 * alarms, and sends no broadcast. The re-arm that runs the next time the app is opened (ParkApp's
 * onStart) is what puts those reminders back, as inexact ones.
 *
 * Declared in AndroidManifest.xml like every receiver. Whether a manifest-declared receiver
 * actually gets this broadcast on a real device is worth confirming on a clean install — the
 * Android docs describe writing a receiver for it but don't spell out manifest registration.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        // The docs' own advice: the user can grant and then almost immediately revoke, so
        // confirm the access is still there before doing anything.
        if (!canScheduleExactAlarmsCompat(context)) {
            Log.d("Park", "ExactAlarmPermissionReceiver: broadcast received but exact alarms are not allowed — ignoring")
            return
        }
        Log.d("Park", "ExactAlarmPermissionReceiver: exact alarms granted — re-arming reminders")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rearmAllActiveReminders(context.applicationContext)
            } catch (e: Exception) {
                Log.w("Park", "ExactAlarmPermissionReceiver: re-arm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
