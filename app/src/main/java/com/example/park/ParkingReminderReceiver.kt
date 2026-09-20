package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        formatSweepDateTime(Instant.ofEpochMilli(nextSweepAtMillis).atZone(ZoneId.systemDefault()))
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
    }
}

class ParkingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val carId = intent.getLongExtra("carId", -1L)
        val carName = intent.getStringExtra("carName") ?: "Your car"
        val corridor = intent.getStringExtra("corridor") ?: "your parked street"
        val nextSweepAtMillis = intent.getLongExtra("nextSweepAtMillis", -1L)
        val kind = intent.getStringExtra("kind")
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() }
            ?: ReminderKind.NORMAL

        val (title, text) = buildReminderContent(carName, corridor, nextSweepAtMillis, kind)

        NotificationHelper.showReminder(
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
    }
}