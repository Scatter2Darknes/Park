package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires at the moment a parked car's current deadline has passed (a sweep's start; for RPP, the
 * close of that day's enforcement window — see rollForwardAtMillis in NotificationScheduler.kt) and
 * moves the car on to its NEXT occurrence, scheduling reminders for it. That's what lets a car
 * left parked through a sweep get next week's reminders without the app being opened.
 *
 * A separate receiver (declared in AndroidManifest.xml) rather than a branch of
 * ParkingReminderReceiver, because this doesn't post anything — it only reschedules.
 * If this alarm is ever missed, the re-arm in rearmAllActiveReminders does the same recompute.
 */
class ScheduleRollForwardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val carId = intent.getLongExtra("carId", -1L)
        val parkedAtMillis = intent.getLongExtra("parkedAtMillis", -1L)
        if (carId < 0 || parkedAtMillis < 0) {
            Log.w("Park", "ScheduleRollForwardReceiver: missing extras (carId=$carId, parkedAtMillis=$parkedAtMillis) — ignoring")
            return
        }
        Log.d("Park", "ScheduleRollForwardReceiver: ${intent.getStringExtra("rollKind")} deadline passed for car $carId — rolling forward")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recomputeParkedSchedule(context.applicationContext, carId, expectedParkedAtMillis = parkedAtMillis)
            } catch (e: Exception) {
                Log.w("Park", "ScheduleRollForwardReceiver: roll-forward failed for car $carId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
