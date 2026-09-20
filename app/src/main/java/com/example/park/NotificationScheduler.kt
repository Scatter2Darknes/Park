package com.example.park

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// Every kind's alarms/notifications for a car reuse the car's id shifted by a kind-specific
// offset, keeping all four request codes / notification IDs trivially unique (and stable
// across reschedules, so FLAG_UPDATE_CURRENT replaces the right one) without a second ID
// scheme to track. RPP's offsets are far enough from URGENT_ID_OFFSET that a car with both a
// sweep reminder and an RPP reminder scheduled at once can never collide.
private const val URGENT_ID_OFFSET = 1_000_000
private const val RPP_NORMAL_ID_OFFSET = 2_000_000
private const val RPP_URGENT_ID_OFFSET = 3_000_000

fun reminderRequestCode(carId: Long, kind: ReminderKind): Int = carId.toInt() + when (kind) {
    ReminderKind.NORMAL -> 0
    ReminderKind.URGENT -> URGENT_ID_OFFSET
    ReminderKind.RPP_NORMAL -> RPP_NORMAL_ID_OFFSET
    ReminderKind.RPP_URGENT -> RPP_URGENT_ID_OFFSET
}

fun reminderNotificationId(carId: Long, kind: ReminderKind): Int = reminderRequestCode(carId, kind)

fun buildPendingIntent(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    kind: ReminderKind
): PendingIntent {
    val intent = Intent(context, ParkingReminderReceiver::class.java).apply {
        putExtra("carId", carId)
        putExtra("carName", carName)
        putExtra("corridor", corridor)
        putExtra("nextSweepAtMillis", nextSweepAtMillis)
        putExtra("kind", kind.name)
    }
    return PendingIntent.getBroadcast(
        context,
        reminderRequestCode(carId, kind),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

const val SNOOZE_MINUTES = 10L

/** Reschedules [kind]'s reminder for this car [SNOOZE_MINUTES] from now, reusing the same
 *  alarm slot (and thus the same ParkingReminderReceiver delivery path) that the original
 *  schedule used. */
fun snoozeReminder(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    kind: ReminderKind
) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val trigger = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
    val pendingIntent = buildPendingIntent(context, carId, carName, corridor, nextSweepAtMillis, kind)
    if (exactAlarmsAllowed) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
    } else {
        android.util.Log.w("Park", "Exact alarm permission not granted — snooze not scheduled")
    }
}

private fun cancelAlarm(context: Context, carId: Long, kind: ReminderKind) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, ParkingReminderReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, reminderRequestCode(carId, kind), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
}

/**
 * Schedules one reminder tier, or — if its trigger time has already elapsed but sweeping
 * itself hasn't happened yet — fires it immediately instead of silently dropping it.
 *
 * This matters because we never assume a car gets parked in a "safe, plenty of lead time"
 * spot: if someone parks somewhere that starts sweeping in 10 minutes while their offsets
 * are 2h/15min, both trigger times are already in the past the moment this runs. Previously
 * that meant no notification at all. Now the notification fires right away instead.
 */
private fun scheduleOrFireImmediately(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    offsetMillis: Long,
    kind: ReminderKind,
    now: Long,
    exactAlarmsAllowed: Boolean,
    alarmManager: AlarmManager
) {
    val trigger = nextSweepAtMillis - offsetMillis
    when {
        // Sweeping itself has already fully passed — nothing left to usefully alert about.
        nextSweepAtMillis <= now -> cancelAlarm(context, carId, kind)

        trigger > now -> {
            if (exactAlarmsAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger,
                    buildPendingIntent(context, carId, carName, corridor, nextSweepAtMillis, kind)
                )
            } else {
                android.util.Log.w("Park", "Exact alarm permission not granted — $kind reminder not scheduled")
            }
        }

        else -> {
            // Trigger time already elapsed but sweeping is still ahead — fire now rather
            // than waiting for an alarm that's already in the past. Doesn't require the
            // exact-alarm permission at all, since this is a direct notification post, not
            // a scheduled alarm.
            cancelAlarm(context, carId, kind)
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
}

/**
 * Schedules both reminder tiers for a car:
 *  - NORMAL: dismissible, fires [reminderOffsetMillis] before sweeping — "I'll eventually move it"
 *  - URGENT: ongoing/non-swipeable, fires [urgentOffsetMillis] before sweeping (if non-null) — "move it now"
 *
 * Any already-shown notifications from a previous parked state are cleared first so a stale
 * "move your car" doesn't linger after re-parking or re-scheduling.
 */
fun scheduleParkingReminders(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    reminderOffsetMillis: Long,
    urgentOffsetMillis: Long?
) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val now = System.currentTimeMillis()
    val exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.URGENT))

    scheduleOrFireImmediately(
        context, carId, carName, corridor, nextSweepAtMillis, reminderOffsetMillis,
        ReminderKind.NORMAL, now, exactAlarmsAllowed, alarmManager
    )

    if (urgentOffsetMillis != null) {
        scheduleOrFireImmediately(
            context, carId, carName, corridor, nextSweepAtMillis, urgentOffsetMillis,
            ReminderKind.URGENT, now, exactAlarmsAllowed, alarmManager
        )
    } else {
        cancelAlarm(context, carId, ReminderKind.URGENT) // urgent tier disabled in Settings
    }
}

/**
 * Same shape as scheduleParkingReminders, for the RPP non-permit move-by deadline instead of a
 * sweep start time. [zoneLabel] plays the role scheduleParkingReminders' "corridor" does (see
 * buildReminderContent's doc comment).
 */
fun scheduleRppReminders(
    context: Context,
    carId: Long,
    carName: String,
    zoneLabel: String,
    moveByAtMillis: Long,
    reminderOffsetMillis: Long,
    urgentOffsetMillis: Long?
) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val now = System.currentTimeMillis()
    val exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_URGENT))

    scheduleOrFireImmediately(
        context, carId, carName, zoneLabel, moveByAtMillis, reminderOffsetMillis,
        ReminderKind.RPP_NORMAL, now, exactAlarmsAllowed, alarmManager
    )

    if (urgentOffsetMillis != null) {
        scheduleOrFireImmediately(
            context, carId, carName, zoneLabel, moveByAtMillis, urgentOffsetMillis,
            ReminderKind.RPP_URGENT, now, exactAlarmsAllowed, alarmManager
        )
    } else {
        cancelAlarm(context, carId, ReminderKind.RPP_URGENT) // urgent tier disabled in Settings
    }
}

/** Cancels both RPP alarm tiers and any currently-shown RPP notifications for a car. */
fun cancelRppReminder(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.RPP_NORMAL)
    cancelAlarm(context, carId, ReminderKind.RPP_URGENT)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_URGENT))
}

/** Cancels every alarm tier — sweep AND RPP — and any currently-shown notifications for a car.
 *  The single call site every "this car is no longer parked" path (unsubscribe, delete) needs,
 *  so neither reminder kind has to be remembered separately at those call sites. */
fun cancelParkingReminder(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.NORMAL)
    cancelAlarm(context, carId, ReminderKind.URGENT)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.URGENT))
    cancelRppReminder(context, carId)
}

/**
 * Re-evaluates and reschedules reminders for every car that currently has a parked state,
 * using the latest values from Settings. Called whenever a reminder-related setting changes,
 * so a car parked before the change picks up the new behavior immediately — including firing
 * right away if the new settings mean its trigger time has already elapsed.
 *
 * Sweep and RPP reminders are independent per car (a block can be both swept AND RPP-zoned) —
 * each is scheduled, left alone, or cancelled based on its own data, not gated on the other's
 * presence.
 */
suspend fun rescheduleAllActiveReminders(context: Context) {
    val db = AppDatabase.getInstance(context)
    val settingsRepo = SettingsRepository(context)
    val offsetMinutes = settingsRepo.notificationOffsetMinutes.first()
    val urgentEnabled = settingsRepo.urgentReminderEnabled.first()
    val urgentOffsetMinutes = settingsRepo.urgentOffsetMinutes.first()
    val reminderOffsetMillis = offsetMinutes * 60_000L
    val urgentOffsetMillis = if (urgentEnabled) urgentOffsetMinutes * 60_000L else null

    val carsById = db.carDao().getAll().associateBy { it.id }

    db.parkedStateDao().getAll().forEach { parked ->
        val car = carsById[parked.carId] ?: return@forEach

        val nextMillis = parked.nextSweepAtMillis
        if (nextMillis != null) {
            val corridor = parked.segmentBlockSweepId
                ?.let { db.streetSegmentDao().getById(it) }
                ?.corridor
                ?: "your parked street"

            scheduleParkingReminders(
                context = context,
                carId = parked.carId,
                carName = car.name,
                corridor = corridor,
                nextSweepAtMillis = nextMillis,
                reminderOffsetMillis = reminderOffsetMillis,
                urgentOffsetMillis = urgentOffsetMillis
            )
        }

        val regulation = parked.rppRegulationId?.let { db.rppZoneRegulationDao().getById(it) }
        val deadline = regulation?.let {
            val parkedSince = Instant.ofEpochMilli(parked.parkedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            nextRppDeadline(it, car, parkedSince, LocalDateTime.now())
        }
        if (deadline != null) {
            scheduleRppReminders(
                context = context,
                carId = parked.carId,
                carName = car.name,
                zoneLabel = "RPP Zone ${deadline.zoneLetters.sorted().joinToString("/")}",
                moveByAtMillis = deadline.moveByDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                reminderOffsetMillis = reminderOffsetMillis,
                urgentOffsetMillis = urgentOffsetMillis
            )
        } else {
            cancelRppReminder(context, parked.carId) // no regulation matched, car holds a permit, or nothing found
        }
    }
}