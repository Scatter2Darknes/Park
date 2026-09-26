package com.example.park

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Public Works temporary no-parking permits for parked cars (StreetUsePermit.kt; the source is PermitSource
 * in CurbSources.kt). Owner decisions, 2026-09-25: temporary-occupancy permits only, from the old street-use
 * system (the only one with block ids), and "check the signs" only:
 *  - a heads-up at the lead time before a permit on the car's block starts (fires at once when the permit
 *    is already inside the lead time), with its own delivery marker;
 *  - a one-per-park notice when a permit is already in effect on the block;
 *  - a banner line.
 * Never a move-by deadline and never "towed": the permit has no reliable hours, and its signs may not cover
 * the car's spot.
 *
 * Matching is by block, like tow zones: the car's street segment's CNN, or, for a park with no segment, any
 * block within TOW_UNCERTAIN_RADIUS_METERS. A saved location marked off the street gets nothing.
 */

private const val TAG = "PermitAlert"

/** Box for finding the streets around a no-segment park: ~55 m, comfortably over the matching radius. */
private const val PERMIT_SEARCH_BOX_DEGREES = 0.0005

// --- Pure decisions (unit tested in PermitAlertsTest) ---

/** What the banner shows about permits for one parked car. */
sealed interface PermitStatus {
    /** A permit on the car's block that is in effect now, or starts within the lead time. */
    data class Posted(val permit: StreetUsePermit, val inEffect: Boolean) : PermitStatus
    /** No usable permit data: can't say. Never shown as "clear". */
    object Unchecked : PermitStatus
    /** Checked with usable data; nothing to show. */
    object Clear : PermitStatus
}

/** Permits in effect at [nowMillis] (start inclusive, end exclusive). */
fun permitsInEffect(permits: List<StreetUsePermit>, nowMillis: Long): List<StreetUsePermit> =
    permits.filter { it.startMillis <= nowMillis && it.endMillis > nowMillis }

/** The soonest permit that hasn't started yet: what the heads-up is for. */
fun nextPermitToStart(permits: List<StreetUsePermit>, nowMillis: Long): StreetUsePermit? =
    permits.filter { it.startMillis > nowMillis }.minByOrNull { it.startMillis }

/**
 * The banner status. A permit in effect wins (the one ending last), else the soonest one starting within
 * [leadMillis]. A real permit is shown even from old data; without one, old or missing data is Unchecked.
 */
fun permitStatusFor(permits: List<StreetUsePermit>, nowMillis: Long, dataUsable: Boolean, leadMillis: Long): PermitStatus {
    permitsInEffect(permits, nowMillis).maxByOrNull { it.endMillis }?.let { return PermitStatus.Posted(it, inEffect = true) }
    nextPermitToStart(permits, nowMillis)?.takeIf { it.startMillis <= nowMillis + leadMillis }
        ?.let { return PermitStatus.Posted(it, inEffect = false) }
    return if (dataUsable) PermitStatus.Clear else PermitStatus.Unchecked
}

/** Whether permit data last synced at [lastSyncMillis] is recent enough to say "checked" (same limit as closures). */
fun permitDataIsUsable(lastSyncMillis: Long?, nowMillis: Long): Boolean =
    lastSyncMillis != null && nowMillis - lastSyncMillis <= CLOSURE_DATA_MAX_AGE_MILLIS

/** "16TH AVE (TARAVAL ST to ULLOA ST)", or the street, or "your block". */
fun permitPlaceLabel(permit: StreetUsePermit): String {
    val street = permit.streetName ?: return "your block"
    return if (permit.crossStreet1 != null && permit.crossStreet2 != null) "$street (${permit.crossStreet1} to ${permit.crossStreet2})" else street
}

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/**
 * The permit's period as the permit states it: "Sep 28 – Oct 3, 8:00 AM – 5:00 PM", "Oct 9", "Sep 28 – Oct 3".
 * The times are the permit's first-day start and last-day end; they aren't claimed to be daily hours.
 */
fun permitPeriodText(permit: StreetUsePermit): String {
    val start = Instant.ofEpochMilli(permit.startMillis).atZone(SF_ZONE)
    val end = Instant.ofEpochMilli(permit.endMillis).atZone(SF_ZONE)
    val allDay = start.toLocalTime() == LocalTime.MIDNIGHT && end.toLocalTime() == LocalTime.MIDNIGHT
    val lastDay = if (end.toLocalTime() == LocalTime.MIDNIGHT) end.minusDays(1) else end
    val dates = if (lastDay.toLocalDate() == start.toLocalDate()) start.format(MONTH_DAY)
    else "${start.format(MONTH_DAY)} – ${lastDay.format(MONTH_DAY)}"
    return if (allDay) dates else "$dates, ${start.format(TIME)} – ${end.format(TIME)}"
}

/** One banner line for [status], or null for nothing to show. "Check signs", never "move" or "towed". */
fun permitBannerText(status: PermitStatus?): String? = when (status) {
    is PermitStatus.Posted ->
        if (status.inEffect) "No-parking permit on your block now (${permitPeriodText(status.permit)}) — check signs"
        else "No-parking permit on your block ${permitPeriodText(status.permit)} — check signs"
    PermitStatus.Unchecked -> "No-parking permit check unavailable"
    PermitStatus.Clear, null -> null
}

/** Which permit line the collapsed banner shows when several cars have one: lower wins. */
fun permitBannerRank(status: PermitStatus?): Int = when (status) {
    is PermitStatus.Posted -> if (status.inEffect) 0 else 1
    PermitStatus.Unchecked -> 2
    PermitStatus.Clear, null -> 3
}

/** Title and text of the one-per-park notice for a permit already in effect. */
fun permitInEffectContent(carName: String, permit: StreetUsePermit): Pair<String, String> =
    "$carName: no-parking permit on your block" to
        "A temporary no-parking permit on ${permitPlaceLabel(permit)} is in effect (${permitPeriodText(permit)}). " +
        "It may cover your spot — check the signs."

// --- Android side ---

/**
 * Every permit that applies to [parked] and isn't over at [nowMillis]. Empty at a saved location marked off
 * the street. By the segment's block when the car has one, else the blocks within the matching radius.
 */
suspend fun findPermitsForParkedCar(context: Context, parked: ParkedState, nowMillis: Long = System.currentTimeMillis()): List<StreetUsePermit> {
    if (isParkedOffStreet(context, parked)) return emptyList()
    val db = AppDatabase.getInstance(context)
    val cnn = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }?.cnn?.takeIf { it.isNotBlank() }
    if (cnn != null) return db.streetUsePermitDao().getForCnn(cnn, nowMillis)
    val point = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng)
    else LatLng(parked.parkedLat, parked.parkedLng)
    return db.streetSegmentDao().getNearby(
        point.lat - PERMIT_SEARCH_BOX_DEGREES, point.lat + PERMIT_SEARCH_BOX_DEGREES,
        point.lng - PERMIT_SEARCH_BOX_DEGREES, point.lng + PERMIT_SEARCH_BOX_DEGREES
    )
        .filter { it.cnn.isNotBlank() && (projectOntoPolyline(point, it.points)?.distanceMeters ?: Double.MAX_VALUE) <= TOW_UNCERTAIN_RADIUS_METERS }
        .map { it.cnn }
        .distinct()
        .flatMap { db.streetUsePermitDao().getForCnn(it, nowMillis) }
        .distinctBy { it.rowKey }
}

/** The banner status for one parked car, from stored data only. */
suspend fun resolvePermitStatus(context: Context, parked: ParkedState, leadMillis: Long): PermitStatus {
    val now = System.currentTimeMillis()
    val lastSync = SettingsRepository(context).permitsLastSyncMillis.first()
    return permitStatusFor(findPermitsForParkedCar(context, parked, now), now, permitDataIsUsable(lastSync, now), leadMillis)
}

/**
 * Arms (or fires, or cancels) the permit heads-up for one parked car: for the soonest permit on the block that
 * hasn't started, [leadMillis] before its start, or at once if that's already past. Switched off: everything
 * permit-related for the car goes, alarm and notices alike.
 */
internal suspend fun armPermitAlert(context: Context, parked: ParkedState, carName: String, enabled: Boolean, leadMillis: Long) {
    if (!enabled) {
        cancelPermitAlerts(context, parked.carId)
        return
    }
    val now = System.currentTimeMillis()
    val next = nextPermitToStart(findPermitsForParkedCar(context, parked, now), now)
    if (next == null) {
        cancelAlarm(context, parked.carId, ReminderKind.PERMIT_ADVANCE)
        return
    }
    scheduleOrFireImmediately(
        context, parked.carId, carName, permitPlaceLabel(next), next.startMillis, parked.parkedAtMillis,
        leadMillis, ReminderKind.PERMIT_ADVANCE, now, canScheduleExactAlarmsCompat(context),
        alreadyDeliveredForDeadline = parked.permitAdvanceDeliveredForMillis == next.startMillis,
        alarmManager = context.getSystemService(AlarmManager::class.java)
    )
}

/** Cancels the permit alarm and both permit notifications for a car: the "not parked here any more" path. */
fun cancelPermitAlerts(context: Context, carId: Long) {
    cancelAlarm(context, carId, ReminderKind.PERMIT_ADVANCE)
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.PERMIT_ADVANCE))
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.PERMIT_NOTICE))
}

/**
 * The park-time notice: once per park (called only by the park-time step, so re-arms never repeat it), when a
 * permit is already in effect on the block. One starting later is covered by the heads-up.
 */
internal suspend fun notifyPermitOnPark(context: Context, carId: Long, parkedAtMillis: Long) {
    val db = AppDatabase.getInstance(context)
    val parked = db.parkedStateDao().getForCar(carId)?.takeIf { it.parkedAtMillis == parkedAtMillis } ?: return // re-parked meanwhile
    val carName = db.carDao().getAll().firstOrNull { it.id == carId }?.name ?: "Your car"
    val now = System.currentTimeMillis()
    val permit = permitsInEffect(findPermitsForParkedCar(context, parked, now), now).maxByOrNull { it.endMillis } ?: return
    val (title, text) = permitInEffectContent(carName, permit)
    postStandardNotice(context, carId, NotificationIds.Purpose.PERMIT_NOTICE, title, text)
    Log.d(TAG, "car $carId: permit ${permit.permitNumber} in effect, notified at park time")
}
