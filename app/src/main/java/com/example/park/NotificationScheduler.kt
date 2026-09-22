package com.example.park

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// All ids live in NotificationIds (one range per purpose, with a guard on the car id and a test that
// no two purposes can collide). These two functions just map a reminder kind onto its purpose.
private fun ReminderKind.idPurpose(): NotificationIds.Purpose = when (this) {
    ReminderKind.NORMAL -> NotificationIds.Purpose.REMINDER_NORMAL
    ReminderKind.URGENT -> NotificationIds.Purpose.REMINDER_URGENT
    ReminderKind.RPP_NORMAL -> NotificationIds.Purpose.RPP_NORMAL
    ReminderKind.RPP_URGENT -> NotificationIds.Purpose.RPP_URGENT
    ReminderKind.SWEEP_ACTIVE -> NotificationIds.Purpose.SWEEP_ACTIVE
}

fun reminderRequestCode(carId: Long, kind: ReminderKind): Int = NotificationIds.forCar(carId, kind.idPurpose())

fun reminderNotificationId(carId: Long, kind: ReminderKind): Int = reminderRequestCode(carId, kind)

/**
 * [parkedAtMillis] identifies WHICH parked row this alarm belongs to. When the alarm fires,
 * ParkingReminderReceiver passes it to [recordReminderDelivery], whose UPDATE only matches a row
 * with that exact parkedAtMillis — so a reminder for a spot the user has since left can't write
 * its "delivered" marker into the row for their new spot. -1 (the default) means "unknown" and
 * makes the receiver skip recording; only [snoozeReminder] uses that, see its comment.
 */
fun buildPendingIntent(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    kind: ReminderKind,
    parkedAtMillis: Long = -1L
): PendingIntent {
    val intent = Intent(context, ParkingReminderReceiver::class.java).apply {
        putExtra("carId", carId)
        putExtra("carName", carName)
        putExtra("corridor", corridor)
        putExtra("nextSweepAtMillis", nextSweepAtMillis)
        putExtra("kind", kind.name)
        putExtra("parkedAtMillis", parkedAtMillis)
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
 *  schedule used.
 *
 *  Doesn't pass a parkedAtMillis, so the snoozed delivery doesn't touch the delivery marker —
 *  that's fine, because the marker was already recorded when the original reminder was posted
 *  (it's keyed by deadline, and the deadline hasn't changed).
 *
 *  KNOWN LIMITATION (accepted, deliberately not fixed): a reboot inside the snooze window wipes
 *  the snoozed alarm, and re-arming won't bring it back — the marker says this deadline was
 *  already delivered, so re-arm treats the elapsed trigger as handled. */
fun snoozeReminder(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    kind: ReminderKind
) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val trigger = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
    val pendingIntent = buildPendingIntent(context, carId, carName, corridor, nextSweepAtMillis, kind)
    setAlarm(alarmManager, canScheduleExactAlarmsCompat(context), trigger, pendingIntent, "$kind snooze")
}

/**
 * Sets one alarm: exact when the app may, otherwise the inexact [AlarmManager.setAndAllowWhileIdle]
 * fallback — so a user who hasn't granted "Alarms & reminders" still gets their reminder, just
 * possibly a few minutes late (Android batches inexact alarms and may defer them further in Doze),
 * instead of silently getting nothing. Both variants use the same [pendingIntent], so a later
 * exact set (after the permission is granted) simply replaces the inexact one.
 */
// Not private: MeterTimer.kt reuses this same exact-vs-inexact-fallback logic for the manual
// meter timer alarm, which isn't part of the tiered sweep/RPP system this file otherwise owns.
fun setAlarm(
    alarmManager: AlarmManager,
    exactAlarmsAllowed: Boolean,
    triggerAtMillis: Long,
    pendingIntent: PendingIntent,
    label: String
) {
    if (exactAlarmsAllowed) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    } else {
        android.util.Log.w("Park", "Exact alarm permission not granted — $label scheduled as an inexact fallback (may be late)")
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
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
 * Records that [kind]'s reminder for [deadlineMillis] was delivered, on the parked row that was
 * created at [parkedAtMillis]. Called from the two places a reminder is actually posted: the
 * alarm-triggered path (ParkingReminderReceiver) and the immediate-fire branch below.
 *
 * `suspend` is Kotlin's marker for a function that can pause without blocking a thread — Room
 * requires DAO calls to be made from one (or a background thread), so callers run this inside a
 * coroutine.
 */
suspend fun recordReminderDelivery(
    context: Context,
    carId: Long,
    parkedAtMillis: Long,
    kind: ReminderKind,
    deadlineMillis: Long
) {
    val dao = AppDatabase.getInstance(context).parkedStateDao()
    when (kind) {
        ReminderKind.NORMAL -> dao.markNormalDelivered(carId, parkedAtMillis, deadlineMillis)
        ReminderKind.URGENT -> dao.markUrgentDelivered(carId, parkedAtMillis, deadlineMillis)
        ReminderKind.RPP_NORMAL -> dao.markRppNormalDelivered(carId, parkedAtMillis, deadlineMillis)
        ReminderKind.RPP_URGENT -> dao.markRppUrgentDelivered(carId, parkedAtMillis, deadlineMillis)
        ReminderKind.SWEEP_ACTIVE -> Unit // one-off notice; has no delivery marker
    }
}

/**
 * Schedules one reminder tier, or — if its trigger time has already elapsed but sweeping
 * itself hasn't happened yet — fires it immediately instead of silently dropping it.
 *
 * This matters because we never assume a car gets parked in a "safe, plenty of lead time"
 * spot: if someone parks somewhere that starts sweeping in 10 minutes while their offsets
 * are 2h/15min, both trigger times are already in the past the moment this runs. Previously
 * that meant no notification at all. Now the notification fires right away instead.
 *
 * [alreadyDeliveredForDeadline] is true when this tier's delivery marker equals [nextSweepAtMillis]
 * — the user already got (or dismissed) this exact reminder. In that case an elapsed trigger is
 * left alone instead of re-posting it, and any alarm still pending in the slot (e.g. a snooze)
 * is deliberately not cancelled. A FUTURE trigger is still scheduled either way: it means the
 * offset was changed to something later than the reminder that already fired.
 *
 * @return whether this tier is taken care of: an alarm was set (exact or the inexact fallback),
 *   the reminder fired right now, or it had already been delivered. False when nothing was set
 *   and nothing fired (the deadline already passed, or the notification couldn't be posted).
 */
private suspend fun scheduleOrFireImmediately(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    parkedAtMillis: Long,
    offsetMillis: Long,
    kind: ReminderKind,
    now: Long,
    exactAlarmsAllowed: Boolean,
    alreadyDeliveredForDeadline: Boolean,
    alarmManager: AlarmManager
): Boolean {
    val trigger = nextSweepAtMillis - offsetMillis
    return when {
        // Sweeping itself has already fully passed — nothing left to usefully alert about.
        nextSweepAtMillis <= now -> {
            cancelAlarm(context, carId, kind)
            false
        }

        trigger > now -> {
            setAlarm(
                alarmManager, exactAlarmsAllowed, trigger,
                buildPendingIntent(context, carId, carName, corridor, nextSweepAtMillis, kind, parkedAtMillis),
                "$kind reminder"
            )
            true
        }

        alreadyDeliveredForDeadline -> {
            android.util.Log.d("Park", "$kind reminder for car $carId already delivered for deadline $nextSweepAtMillis — not re-posting")
            true
        }

        else -> {
            // Trigger time already elapsed but sweeping is still ahead — fire now rather
            // than waiting for an alarm that's already in the past. Doesn't require the
            // exact-alarm permission at all, since this is a direct notification post, not
            // a scheduled alarm.
            cancelAlarm(context, carId, kind)
            val (title, text) = buildReminderContent(carName, corridor, nextSweepAtMillis, kind)
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
            // Only recorded if it was actually posted (POST_NOTIFICATIONS granted) — otherwise
            // the user was never told, and a later re-arm should get to try again.
            if (posted) recordReminderDelivery(context, carId, parkedAtMillis, kind, nextSweepAtMillis)
            posted
        }
    }
}


/**
 * Roll-forward alarms: one tiny alarm per car and per reminder family (sweep, RPP), set for the
 * moment the current deadline has passed — a sweep's start, or (for RPP) the close of that day's
 * enforcement window. When it fires, [ScheduleRollForwardReceiver] runs [recomputeParkedSchedule],
 * which moves the parked row on to its NEXT occurrence and schedules reminders for it — so a car
 * that stays parked through a sweep gets next week's reminders without the app being opened.
 *
 * This is deliberately a separate alarm rather than "roll forward when the reminder is delivered":
 * that would advance nextSweepAtMillis while the sweep is still ahead, and every on-screen
 * countdown would jump to next week early. If a roll-forward alarm is missed (reboot, force-stop),
 * the re-arm in [rearmAllActiveReminders] does the same recompute the next time it runs.
 */
enum class RollForwardKind { SWEEP, RPP }

private fun rollForwardRequestCode(carId: Long, kind: RollForwardKind): Int = NotificationIds.forCar(carId, when (kind) {
    RollForwardKind.SWEEP -> NotificationIds.Purpose.ROLL_FORWARD_SWEEP
    RollForwardKind.RPP -> NotificationIds.Purpose.ROLL_FORWARD_RPP
})

private fun cancelRollForward(context: Context, carId: Long, kind: RollForwardKind) {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val pendingIntent = PendingIntent.getBroadcast(
        context, rollForwardRequestCode(carId, kind),
        Intent(context, ScheduleRollForwardReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarmManager.cancel(pendingIntent)
}

/** Sets the roll-forward alarm for [atMillis], or cancels it if that moment has already passed
 *  (which also stops a roll-forward from ever looping: it only re-arms for a FUTURE moment). */
private fun scheduleRollForward(
    context: Context,
    carId: Long,
    parkedAtMillis: Long,
    kind: RollForwardKind,
    atMillis: Long,
    now: Long,
    exactAlarmsAllowed: Boolean,
    alarmManager: AlarmManager
) {
    if (atMillis <= now) {
        cancelRollForward(context, carId, kind)
        return
    }
    val intent = Intent(context, ScheduleRollForwardReceiver::class.java).apply {
        putExtra("carId", carId)
        putExtra("parkedAtMillis", parkedAtMillis)
        putExtra("rollKind", kind.name)
    }
    val pendingIntent = PendingIntent.getBroadcast(
        context, rollForwardRequestCode(carId, kind), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    setAlarm(alarmManager, exactAlarmsAllowed, atMillis, pendingIntent, "$kind roll-forward")
}

/**
 * The shared body of scheduling both tiers of one reminder family (sweep or RPP) against one
 * deadline. [normalDeliveredForMillis]/[urgentDeliveredForMillis] are the delivery markers off
 * the parked row (null when scheduling for a brand-new row). Also sets the family's roll-forward
 * alarm for [rollForwardAtMillis].
 *
 * [clearStaleNotifications] is the one difference between the two scheduling paths:
 *  - true ("schedule fresh": a new park, or a Settings change): a notification still on screen
 *    that does NOT belong to this deadline is stale and gets cancelled. One that DOES belong
 *    to it (marker == deadline) is kept — the user is looking at the right reminder, and an
 *    ongoing urgent one shouldn't vanish because they changed an unrelated setting.
 *  - false ("re-arm": boot / app foreground): never cancels any notification.
 */
private suspend fun scheduleTiers(
    context: Context,
    carId: Long,
    carName: String,
    label: String,
    deadlineMillis: Long,
    parkedAtMillis: Long,
    normalKind: ReminderKind,
    urgentKind: ReminderKind,
    reminderOffsetMillis: Long,
    urgentOffsetMillis: Long?,
    normalDeliveredForMillis: Long?,
    urgentDeliveredForMillis: Long?,
    clearStaleNotifications: Boolean,
    rollKind: RollForwardKind,
    rollForwardAtMillis: Long
): Boolean {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val now = System.currentTimeMillis()
    val exactAlarmsAllowed = canScheduleExactAlarmsCompat(context)
    val normalDelivered = normalDeliveredForMillis == deadlineMillis
    val urgentDelivered = urgentDeliveredForMillis == deadlineMillis

    if (clearStaleNotifications) {
        if (!normalDelivered) NotificationHelper.cancel(context, reminderNotificationId(carId, normalKind))
        // Urgent tier switched off in Settings: nothing of it should stay on screen either.
        if (!urgentDelivered || urgentOffsetMillis == null) {
            NotificationHelper.cancel(context, reminderNotificationId(carId, urgentKind))
        }
    }

    val normalHandled = scheduleOrFireImmediately(
        context, carId, carName, label, deadlineMillis, parkedAtMillis, reminderOffsetMillis,
        normalKind, now, exactAlarmsAllowed, normalDelivered, alarmManager
    )

    val urgentHandled = if (urgentOffsetMillis != null) {
        scheduleOrFireImmediately(
            context, carId, carName, label, deadlineMillis, parkedAtMillis, urgentOffsetMillis,
            urgentKind, now, exactAlarmsAllowed, urgentDelivered, alarmManager
        )
    } else {
        cancelAlarm(context, carId, urgentKind) // urgent tier disabled in Settings
        false
    }

    scheduleRollForward(context, carId, parkedAtMillis, rollKind, rollForwardAtMillis, now, exactAlarmsAllowed, alarmManager)
    return normalHandled || urgentHandled
}

/**
 * Schedules both reminder tiers for a car:
 *  - NORMAL: dismissible, fires [reminderOffsetMillis] before sweeping — "I'll eventually move it"
 *  - URGENT: ongoing/non-swipeable, fires [urgentOffsetMillis] before sweeping (if non-null) — "move it now"
 *
 * Already-shown notifications from a previous parked state are cleared first so a stale
 * "move your car" doesn't linger after re-parking or re-scheduling. Pass the parked row's
 * delivery markers when rescheduling an existing row; leave them null for a brand-new one. Also
 * sets the roll-forward alarm that advances the row past this sweep once it has happened.
 *
 * @return whether at least one tier is taken care of (an alarm set, exact or inexact fallback, or a
 *   reminder fired now) — what ParkedState.notificationScheduled records.
 */
suspend fun scheduleParkingReminders(
    context: Context,
    carId: Long,
    carName: String,
    corridor: String,
    nextSweepAtMillis: Long,
    parkedAtMillis: Long,
    reminderOffsetMillis: Long,
    urgentOffsetMillis: Long?,
    normalDeliveredForMillis: Long? = null,
    urgentDeliveredForMillis: Long? = null
) = scheduleTiers(
    context, carId, carName, corridor, nextSweepAtMillis, parkedAtMillis,
    ReminderKind.NORMAL, ReminderKind.URGENT, reminderOffsetMillis, urgentOffsetMillis,
    normalDeliveredForMillis, urgentDeliveredForMillis, clearStaleNotifications = true,
    rollKind = RollForwardKind.SWEEP, rollForwardAtMillis = nextSweepAtMillis
)

/**
 * Same shape as scheduleParkingReminders, for the RPP non-permit move-by deadline instead of a
 * sweep start time. [zoneLabel] plays the role scheduleParkingReminders' "corridor" does (see
 * buildReminderContent's doc comment). [rollForwardAtMillis] is when the RPP roll-forward alarm
 * should fire — see [rppWindowEndMillis].
 */
suspend fun scheduleRppReminders(
    context: Context,
    carId: Long,
    carName: String,
    zoneLabel: String,
    moveByAtMillis: Long,
    parkedAtMillis: Long,
    reminderOffsetMillis: Long,
    urgentOffsetMillis: Long?,
    normalDeliveredForMillis: Long? = null,
    urgentDeliveredForMillis: Long? = null,
    rollForwardAtMillis: Long = moveByAtMillis
) = scheduleTiers(
    context, carId, carName, zoneLabel, moveByAtMillis, parkedAtMillis,
    ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, reminderOffsetMillis, urgentOffsetMillis,
    normalDeliveredForMillis, urgentDeliveredForMillis, clearStaleNotifications = true,
    rollKind = RollForwardKind.RPP, rollForwardAtMillis = rollForwardAtMillis
)

/**
 * When the enforcement window containing [moveBy] closes — where RPP's roll-forward alarm goes.
 *
 * Not at the deadline itself, unlike a sweep: recomputing right at the RPP deadline gives back the
 * same deadline (nextRppDeadline keeps returning today's move-by time for as long as today's window
 * is still open), so nothing would advance. Only once the window has closed does the next
 * occurrence become tomorrow's.
 */
fun rppWindowEndMillis(regulation: RppZoneRegulation, moveBy: LocalDateTime): Long {
    val windowEnd = moveBy.toLocalDate().atTime(militaryHourToLocalTime(regulation.hrsEnd))
    return (if (windowEnd.isAfter(moveBy)) windowEnd else moveBy)
        .atZone(SF_ZONE).toInstant().toEpochMilli()
}

/** Cancels both RPP alarm tiers, the RPP roll-forward alarm, and any currently-shown RPP notifications for a car. */
fun cancelRppReminder(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.RPP_NORMAL)
    cancelAlarm(context, carId, ReminderKind.RPP_URGENT)
    cancelRollForward(context, carId, RollForwardKind.RPP)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.RPP_URGENT))
}

/** Cancels the sweep half only — both alarm tiers, the sweep roll-forward alarm and their notifications
 *  — for a car whose new spot has no upcoming sweep. The RPP half is independent (see cancelRppReminder). */
fun cancelSweepReminder(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.NORMAL)
    cancelAlarm(context, carId, ReminderKind.URGENT)
    cancelRollForward(context, carId, RollForwardKind.SWEEP)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.URGENT))
}

/** Cancels every alarm tier — sweep AND RPP — and any currently-shown notifications for a car.
 *  The single call site every "this car is no longer parked" path (unsubscribe, delete) needs,
 *  so neither reminder kind has to be remembered separately at those call sites. */
fun cancelParkingReminder(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.NORMAL)
    cancelAlarm(context, carId, ReminderKind.URGENT)
    cancelRollForward(context, carId, RollForwardKind.SWEEP)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.NORMAL))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.URGENT))
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.SWEEP_ACTIVE))
    // The Bluetooth "Did X just park?" / "X unparked" notices belong to this car's parked state too;
    // left behind after an unpark or a car delete they'd point at a spot that no longer exists.
    // (The unpark path posts its own fresh notice AFTER calling this, so it isn't affected.)
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.BLUETOOTH_AUTO_DETECT))
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.BLUETOOTH_AUTO_UNPARK))
    cancelRppReminder(context, carId)
    // A manual meter timer belongs to the spot it was set at, same as everything else here —
    // stale once the car is no longer parked there.
    cancelMeterTimer(context, carId)
}

/** An RPP deadline plus the two epoch-millis values derived from it. */
private class RppDeadlineInfo(val warning: RppWarning, val moveByMillis: Long, val rollForwardAtMillis: Long)

/**
 * The RPP deadline for [parked] as of now, or null when none applies (no regulation matched,
 * the car holds a permit, or the days didn't parse).
 *
 * Nothing stores this deadline — it's recomputed every time from the regulation, the car and
 * parked-at time. That's safe for delivery markers because the result is deterministic: for a
 * given parked row it stays the same value from the moment of parking until the deadline's own
 * window closes (see RppRearmDeterminismTest), so the value recorded when a reminder is
 * delivered still matches the value recomputed at a later re-arm.
 */
private suspend fun currentRppDeadline(
    db: AppDatabase,
    parked: ParkedState,
    car: Car
): RppDeadlineInfo? {
    val regulation = parked.rppRegulationId?.let { db.rppZoneRegulationDao().getById(it) } ?: return null
    val parkedSince = Instant.ofEpochMilli(parked.parkedAtMillis).atZone(SF_ZONE).toLocalDateTime()
    val warning = nextRppDeadline(regulation, car, parkedSince, sfNow()) ?: return null
    return RppDeadlineInfo(
        warning,
        warning.moveByDateTime.atZone(SF_ZONE).toInstant().toEpochMilli(),
        rppWindowEndMillis(regulation, warning.moveByDateTime)
    )
}

/** Serializes arming: the foreground trigger and the boot trigger (and Settings changes, sync,
 *  and roll-forward alarms) can overlap, and two runs racing through "not delivered yet → post
 *  it" could post the same reminder twice before either recorded its marker. Kotlin's Mutex is
 *  not reentrant, so only the public entry points take it; the private helpers below don't. */
private val armMutex = Mutex()

private class ArmSettings(val reminderOffsetMillis: Long, val urgentOffsetMillis: Long?)

private suspend fun loadArmSettings(context: Context): ArmSettings {
    val settingsRepo = SettingsRepository(context)
    val offsetMinutes = settingsRepo.notificationOffsetMinutes.first()
    val urgentEnabled = settingsRepo.urgentReminderEnabled.first()
    val urgentOffsetMinutes = settingsRepo.urgentOffsetMinutes.first()
    return ArmSettings(
        reminderOffsetMillis = offsetMinutes * 60_000L,
        urgentOffsetMillis = if (urgentEnabled) urgentOffsetMinutes * 60_000L else null
    )
}

/**
 * Brings one parked car fully up to date: recomputes its sweep deadline from the segment as it is
 * NOW (so a "Fix schedule" override, refreshed street data, or a sweep that has since passed are
 * all picked up), stores it if it changed, then schedules both reminder families and their
 * roll-forward alarms. Returns whether the stored deadline changed.
 *
 * A notification for a deadline that has been MOVED before it happened (an override or data
 * change while it was still ahead) is stale, so those are cleared even when [clearStaleNotifications]
 * is false. A deadline that merely PASSED and rolled to the next occurrence is not stale — its
 * notification is what the user is looking at — so that case leaves notifications alone.
 */
private suspend fun armParkedState(
    context: Context,
    db: AppDatabase,
    storedParked: ParkedState,
    car: Car,
    settings: ArmSettings,
    clearStaleNotifications: Boolean
): Boolean {
    val now = System.currentTimeMillis()
    var parked = storedParked
    var deadlineChanged = false
    var scheduleMoved = false

    val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
    // A segment that's gone (never synced, or retired upstream) keeps its stored deadline: a
    // reminder that might be stale beats silently dropping one that might be real.
    if (segment != null) {
        // Across every row of the curb (see CurbSchedule), so a sweep day carried by a sibling row is never lost.
        val recomputed = CurbSchedule.nextSweepDateTime(loadCurbRows(context, segment))
            ?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()
        val old = parked.nextSweepAtMillis
        if (recomputed != old) {
            android.util.Log.d("Park", "Recomputed sweep deadline for car ${parked.carId}: $old -> $recomputed")
            db.parkedStateDao().updateNextSweep(parked.carId, parked.parkedAtMillis, recomputed)
            parked = parked.copy(nextSweepAtMillis = recomputed)
            deadlineChanged = true
            scheduleMoved = old != null && old > now
        }
    }
    val clearStale = clearStaleNotifications || scheduleMoved

    val nextMillis = parked.nextSweepAtMillis
    if (nextMillis != null) {
        scheduleTiers(
            context, parked.carId, car.name, segment?.corridor ?: "your parked street",
            nextMillis, parked.parkedAtMillis,
            ReminderKind.NORMAL, ReminderKind.URGENT, settings.reminderOffsetMillis, settings.urgentOffsetMillis,
            parked.normalDeliveredForMillis, parked.urgentDeliveredForMillis, clearStale,
            RollForwardKind.SWEEP, nextMillis
        )
    } else {
        // No upcoming occurrence: nothing to remind about, and any alarm left over is for a deadline that no longer exists.
        cancelAlarm(context, parked.carId, ReminderKind.NORMAL)
        cancelAlarm(context, parked.carId, ReminderKind.URGENT)
        cancelRollForward(context, parked.carId, RollForwardKind.SWEEP)
        if (clearStale) {
            NotificationHelper.cancel(context, reminderNotificationId(parked.carId, ReminderKind.NORMAL))
            NotificationHelper.cancel(context, reminderNotificationId(parked.carId, ReminderKind.URGENT))
        }
    }

    val rpp = currentRppDeadline(db, parked, car)
    if (rpp != null) {
        scheduleTiers(
            context, parked.carId, car.name,
            rppZoneLabel(rpp.warning),
            rpp.moveByMillis, parked.parkedAtMillis,
            ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, settings.reminderOffsetMillis, settings.urgentOffsetMillis,
            parked.rppNormalDeliveredForMillis, parked.rppUrgentDeliveredForMillis, clearStaleNotifications,
            RollForwardKind.RPP, rpp.rollForwardAtMillis
        )
    } else if (clearStaleNotifications) {
        cancelRppReminder(context, parked.carId) // no regulation matched, car holds a permit, or nothing found
    } else {
        // Re-arm never touches notifications — only the (now pointless) alarms.
        cancelAlarm(context, parked.carId, ReminderKind.RPP_NORMAL)
        cancelAlarm(context, parked.carId, ReminderKind.RPP_URGENT)
        cancelRollForward(context, parked.carId, RollForwardKind.RPP)
    }
    return deadlineChanged
}

private suspend fun armAllParkedStates(context: Context, clearStaleNotifications: Boolean) = armMutex.withLock {
    val db = AppDatabase.getInstance(context)
    val settings = loadArmSettings(context)
    val carsById = db.carDao().getAll().associateBy { it.id }

    var anyDeadlineChanged = false
    db.parkedStateDao().getAll().forEach { parked ->
        val car = carsById[parked.carId] ?: return@forEach
        if (armParkedState(context, db, parked, car, settings, clearStaleNotifications)) anyDeadlineChanged = true
    }
    // The widget shows the stored deadline, so it has to be redrawn when one moved.
    if (anyDeadlineChanged) enqueueWidgetRefresh(context)
}

/**
 * Recomputes ONE car's sweep deadline from its segment as it is now and reschedules its reminders
 * (see [armParkedState]). Called when something that feeds the deadline may have changed: a
 * "Fix schedule" override saved or removed, a data refresh, or a roll-forward alarm firing.
 * Because delivery markers are keyed by deadline value, a changed deadline invalidates them
 * automatically — the new deadline's reminders are due afresh.
 *
 * [expectedParkedAtMillis], when given, makes this a no-op if the car has been re-parked since the
 * caller (e.g. a roll-forward alarm) was set up — that alarm belonged to the old row.
 */
suspend fun recomputeParkedSchedule(context: Context, carId: Long, expectedParkedAtMillis: Long? = null) =
    armMutex.withLock {
        val db = AppDatabase.getInstance(context)
        val parked = db.parkedStateDao().getForCar(carId) ?: return@withLock
        if (expectedParkedAtMillis != null && parked.parkedAtMillis != expectedParkedAtMillis) {
            android.util.Log.d("Park", "recomputeParkedSchedule: car $carId was re-parked since this was scheduled — ignoring")
            return@withLock
        }
        val car = db.carDao().getAll().firstOrNull { it.id == carId } ?: return@withLock
        val changed = armParkedState(context, db, parked, car, loadArmSettings(context), clearStaleNotifications = false)
        if (changed) enqueueWidgetRefresh(context)
    }

/**
 * [recomputeParkedSchedule] for every parked car on the same CURB as [blockSweepId] — what an override save/remove
 * needs. A car parked on a sibling row of the curb is affected too, because its deadline is the earliest sweep across
 * all the curb's rows (see CurbSchedule), so an override on any one of them can move it.
 */
suspend fun recomputeSchedulesForSegment(context: Context, blockSweepId: String) {
    val db = AppDatabase.getInstance(context)
    val curbIds = db.streetSegmentDao().getById(blockSweepId)
        ?.let { db.streetSegmentDao().getByCurb(it.cnn, it.cnnRightLeft).map { row -> row.blockSweepId } }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(blockSweepId)
    db.parkedStateDao().getForSegments(curbIds).forEach {
        recomputeParkedSchedule(context, it.carId)
    }
}

/**
 * "Schedule fresh" path for every parked car: re-evaluates and reschedules reminders using the
 * latest values from Settings. Called whenever a reminder-related setting changes, so a car
 * parked before the change picks up the new behavior immediately — including firing right away
 * if the new settings mean its trigger time has already elapsed, unless that reminder was
 * already delivered for the current deadline (see the delivery markers on ParkedState).
 *
 * Sweep and RPP reminders are independent per car (a block can be both swept AND RPP-zoned) —
 * each is scheduled, left alone, or cancelled based on its own data, not gated on the other's
 * presence.
 */
suspend fun rescheduleAllActiveReminders(context: Context) =
    armAllParkedStates(context, clearStaleNotifications = true)

/**
 * "Re-arm" path: restores every parked car's alarms after something wiped them (a reboot — see
 * BootReceiver — or the app being force-stopped) and is safe to call any number of times. Also
 * the backstop for a missed roll-forward alarm, and what runs after a data sync.
 *  - Each car's sweep deadline is first recomputed from its segment, so a deadline that has
 *    passed rolls on to the next occurrence and an override or data change is picked up.
 *  - Triggers still in the future get an alarm.
 *  - A trigger that already elapsed while the deadline is still ahead fires immediately ONLY if
 *    that reminder was never delivered (the alarm was lost, i.e. a missed reminder).
 *  - It never cancels a notification the user can currently see, other than one for a deadline
 *    that was moved before it happened.
 */
suspend fun rearmAllActiveReminders(context: Context) =
    armAllParkedStates(context, clearStaleNotifications = false)

/**
 * Called after street data has been (re)loaded — a successful network sync or a manual import —
 * so a parked car whose block's schedule changed upstream gets its deadline and alarms recomputed
 * right away instead of waiting for the next app open. It's the same recompute as the re-arm, and
 * never lets a failure here fail the sync that triggered it.
 */
suspend fun refreshParkedSchedulesAfterSync(context: Context) {
    try {
        rearmAllActiveReminders(context.applicationContext)
    } catch (e: Exception) {
        android.util.Log.w("Park", "Recomputing parked schedules after a data load failed", e)
    }
}
