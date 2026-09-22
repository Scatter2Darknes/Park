package com.example.park

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A manual meter timer: the user types the specific time they want to be reminded (their own
 * read of the posted limit), and this schedules exactly one alarm for it. Deliberately separate
 * from ReminderKind and scheduleTiers entirely — there's no "early" tier that makes sense for a
 * deadline the user typed in themselves, no recurrence, and nothing to recompute, so none of
 * that machinery (delivery markers, roll-forward, re-arm) applies here.
 */

/** Schedules (or reschedules, e.g. "Did you add more time?") the alarm AND persists
 *  [triggerAtMillis] on the car's ParkedState row, so the map's priority banner and the widget
 *  can show it via soonestDeadline() (CarActions.kt) alongside the sweep/RPP deadline. */
suspend fun scheduleMeterTimer(context: Context, carId: Long, carName: String, meterLabel: String, triggerAtMillis: Long) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, MeterTimerReceiver::class.java).apply {
        putExtra("carId", carId)
        putExtra("carName", carName)
        putExtra("meterLabel", meterLabel)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context, NotificationIds.forCar(carId, NotificationIds.Purpose.METER_TIMER), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    setAlarm(alarmManager, canScheduleExactAlarmsCompat(context), triggerAtMillis, pendingIntent, "meter timer")
    AppDatabase.getInstance(context).parkedStateDao().updateMeterTimer(carId, triggerAtMillis)
    enqueueWidgetRefresh(context)
}

/** Cancels a pending meter timer alarm and any notification already posted for it — called
 *  from cancelParkingReminder, the same "this car is no longer parked here" call point every
 *  other reminder kind already uses. Doesn't touch the DB: cancelParkingReminder only ever
 *  runs right before/around a ParkedState row being replaced or deleted entirely, which
 *  already clears meterTimerAtMillis as part of that row's normal lifecycle. */
fun cancelMeterTimer(context: Context, carId: Long) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, MeterTimerReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, NotificationIds.forCar(carId, NotificationIds.Purpose.METER_TIMER), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.METER_TIMER))
}

/** Declared in AndroidManifest.xml, per every other alarm receiver in this app. */
class MeterTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val carId = intent.getLongExtra("carId", -1L)
        val carName = intent.getStringExtra("carName") ?: "Your car"
        val meterLabel = intent.getStringExtra("meterLabel") ?: "the meter"
        if (carId == -1L) {
            Log.w("Park", "MeterTimerReceiver: missing carId — ignoring")
            return
        }
        Log.d("Park", "MeterTimerReceiver: firing for car $carId ($carName)")
        showMeterTimerNotification(context, carId, carName, meterLabel)

        // Clears the persisted deadline so the priority banner/widget stop showing a countdown
        // for a timer that already fired (it would otherwise sit there reading more and more
        // negative). Guarded by parkedAtMillis so a delivery racing a re-park can't clear the
        // NEW row's own (unrelated) meter timer.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val parked = db.parkedStateDao().getForCar(carId)
                if (parked?.meterTimerAtMillis != null) {
                    db.parkedStateDao().updateMeterTimer(carId, null)
                    enqueueWidgetRefresh(context)
                }
            } catch (e: Exception) {
                Log.w("Park", "MeterTimerReceiver: failed to clear meterTimerAtMillis for car $carId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun showMeterTimerNotification(context: Context, carId: Long, carName: String, meterLabel: String) {
    val notificationId = NotificationIds.forCar(carId, NotificationIds.Purpose.METER_TIMER)
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("reminderCarId", carId)
    }
    val pendingIntent = PendingIntent.getActivity(
        context, notificationId, contentIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    // Urgent channel: a meter timer is inherently a "move now" deadline, same urgency class as
    // the sweep/RPP urgent tier — but unlike those, this is swipeable/auto-cancel rather than
    // ongoing, since there's no equivalent "I moved my car" dispatch to wire it through here.
    val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_URGENT)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("Move $carName — meter timer is up")
        .setContentText("Your $meterLabel timer just ran out.")
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(pendingIntent)
        .build()

    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    } else {
        Log.w("Park", "showMeterTimerNotification: POST_NOTIFICATIONS not granted, skipped")
    }
}
