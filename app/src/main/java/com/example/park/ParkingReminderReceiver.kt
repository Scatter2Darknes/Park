package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Shared by the alarm-triggered path (this receiver) and the immediate-fire path in
 * NotificationScheduler.kt, so a reminder looks identical regardless of which triggered it.
 *
 * [corridor] and [nextSweepAtMillis] are reused generically for the RPP kinds too \u2014 "corridor"
 * becomes the zone label (e.g. "RPP Zone A") and "nextSweepAtMillis" becomes the non-permit
 * move-by deadline \u2014 rather than adding a second, near-identical pair of parameters just for
 * RPP's differently-named deadline.
 */
fun buildReminderContent(
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    kind: ReminderKind
): Pair<String, String> {
    val timeText = if (nextSweepAtMillis > 0) {
        formatSweepDateTime(Instant.ofEpochMilli(nextSweepAtMillis).atZone(SF_ZONE))
    } else null

    return when (kind) {
        ReminderKind.URGENT -> "Move $carName now" to
                ("Street cleaning on $corridor starts" + (timeText?.let { " $it" } ?: " soon"))
        ReminderKind.NORMAL -> "Move $carName soon" to
                ("Street cleaning is coming up on $corridor" + (timeText?.let { " \u2014 $it" } ?: ""))
        ReminderKind.RPP_URGENT -> "Move $carName now" to
                ("The non-permit time limit in $corridor is up" + (timeText?.let { " $it" } ?: " soon"))
        ReminderKind.RPP_NORMAL -> "Move $carName soon" to
                ("Non-permit time limit coming up in $corridor" + (timeText?.let { " \u2014 $it" } ?: ""))
        // Here nextSweepAtMillis is when the sweeping window ENDS, not when it starts.
        ReminderKind.SWEEP_ACTIVE -> "Sweeping in progress \u2014 move $carName now" to
                ("Street cleaning on $corridor is under way" + (timeText?.let { " until $it" } ?: ""))
        // For the tow kinds, corridor is the zone's street and nextSweepAtMillis the enforcement start
        // (TOW_ACTIVE: the end of the window in force).
        ReminderKind.TOW_URGENT -> "Move $carName now \u2014 tow-away zone" to
                ("A temporary tow-away zone on $corridor starts" + (timeText?.let { " $it" } ?: " soon"))
        ReminderKind.TOW_NORMAL -> "Move $carName soon \u2014 tow-away zone" to
                ("A temporary tow-away zone on $corridor is coming up" + (timeText?.let { " \u2014 $it" } ?: ""))
        ReminderKind.TOW_ADVANCE -> "$carName: tow-away zone posted" to
                ("A temporary tow-away zone on $corridor starts" + (timeText?.let { " $it" } ?: " soon") +
                        ". Move your car before then, or it may be towed.")
        ReminderKind.TOW_ACTIVE -> "Tow-away zone in effect \u2014 move $carName now" to
                ("A temporary tow-away zone on $corridor is in effect" + (timeText?.let { " until $it" } ?: ""))
        // A Public Works permit has no reliable hours and may not cover the car's spot: "check the signs",
        // never "move or be towed". corridor is the permit's place label, nextSweepAtMillis its start.
        ReminderKind.PERMIT_ADVANCE -> "$carName: no-parking permit on your block" to
                ("A temporary no-parking permit on $corridor starts" + (timeText?.let { " $it" } ?: " soon") +
                        ". Check the signs near your car; if they cover your spot, move before then.")
    }
}

class ParkingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val carId = intent.getLongExtra("carId", -1L)
        val carName = intent.getStringExtra("carName") ?: "Your car"
        val corridor = intent.getStringExtra("corridor") ?: "your parked street"
        val nextSweepAtMillis = intent.getLongExtra("nextSweepAtMillis", -1L)
        val parkedAtMillis = intent.getLongExtra("parkedAtMillis", -1L)
        val kind = intent.getStringExtra("kind")
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
            ?: ReminderKind.NORMAL

        // -1 is getLongExtra's "missing" default. NotificationIds.forCar rejects it (an id outside
        // its range would collide with another purpose), so bail out here rather than crash.
        if (carId < 0) {
            Log.w("Park", "ParkingReminderReceiver: no carId in the intent — ignoring")
            return
        }

        val (title, text) = buildReminderContent(carName, corridor, nextSweepAtMillis, kind)

        // Posted synchronously, before any database work, so the reminder itself never depends
        // on Room being ready.
        val posted = NotificationHelper.showReminder(
            context = context,
            notificationId = reminderNotificationId(carId, kind),
            kind = kind,
            title = title,
            text = text,
            carId = carId,
            carName = carName,
            corridor = corridor,
            nextSweepAtMillis = nextSweepAtMillis
        )

        // Snoozed alarms carry no parkedAtMillis (their marker was recorded when the original
        // fired), and a notification that wasn't actually posted isn't a delivery.
        if (!posted || carId < 0 || parkedAtMillis < 0 || nextSweepAtMillis <= 0) return

        // A receiver's process can be killed as soon as onReceive() returns, which would cut a
        // database write off halfway. goAsync() tells the system "I'm not done yet" and hands
        // back a PendingResult; the work then runs in a coroutine (Kotlin's lightweight
        // background task) and finish() releases the receiver when it's done. finish() sits in
        // `finally` so it runs even if the write throws — otherwise the system would hold the
        // receiver open until its ~10 second timeout. (WorkManager was rejected for this: it's
        // heavier than a single UPDATE needs, and setExpedited crashes below API 31.)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recordReminderDelivery(context.applicationContext, carId, parkedAtMillis, kind, nextSweepAtMillis)
            } catch (e: Exception) {
                // Worst case a later re-arm re-posts this reminder once — annoying, not unsafe.
                Log.w("Park", "ParkingReminderReceiver: failed to record delivery of $kind for car $carId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
