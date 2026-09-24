package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Temporary tow zones for parked cars (docs/park-closures-spec.md §3).
 *
 * A tow zone on the car's block is a DEADLINE: the start of the next enforcement window. It is the
 * third reminder family next to sweep and RPP (ReminderKind.TOW_NORMAL / TOW_URGENT, armed from
 * armParkedState, with roll-forward), plus an ADVANCE alert at the tow/closure lead time (default 2
 * days), because a tow zone is posted days ahead while a sweep is weekly and known.
 *
 * The feed only names blocks (CNNs), never a side or a point, so matching is by block:
 *  - CONFIDENT: the car's chosen street segment is on a zone's block. Gets the full reminder family.
 *  - UNCERTAIN: a park with no segment (a saved location that isn't marked off-street, or a manual
 *    "no street nearby" park) that is within TOW_UNCERTAIN_RADIUS_METERS of a zone's block. Spec:
 *    "uncertain matches become 'tow zone nearby — check signs', not a hard warning", so it gets one
 *    notice at park time and a banner line, not the reminder family.
 *
 * Staleness: the feed stopped getting new permits in July 2026 while DataSF kept republishing it.
 * With no hit, the app never says "no tow zones" from stale data: the banner says the tow data is out
 * of date instead (towStatusFor).
 */

private const val TAG = "TowAlert"

/** A feed whose newest permit is older than this has stopped updating upstream. Permits are entered
 *  every day and the median lead time is ~4 days (spec §9), so a week without any is not normal. */
const val TOW_FEED_STALE_AFTER_MILLIS = 7 * 24 * 60 * 60_000L

/** Tow data synced longer ago than this is too old to say anything about (same as closures). */
const val TOW_DATA_MAX_AGE_MILLIS = CLOSURE_DATA_MAX_AGE_MILLIS

/** A no-segment park within this distance of a zone's block counts as an UNCERTAIN match. */
const val TOW_UNCERTAIN_RADIUS_METERS = 25.0

/** Box for finding the streets around a no-segment park. ~0.0005° ≈ 55 m, comfortably over the radius. */
private const val TOW_SEARCH_BOX_DEGREES = 0.0005

enum class TowMatch { CONFIDENT, UNCERTAIN }

data class TowHit(val zone: TowZone, val match: TowMatch)

/** One enforcement window of one zone, as epoch ms. */
data class TowDeadline(val zone: TowZone, val startMillis: Long, val endMillis: Long)

// --- Pure decisions (unit tested in TowAlertsTest) ---

private fun TowWindow.toDeadline(zone: TowZone) = TowDeadline(
    zone,
    start.atZone(SF_ZONE).toInstant().toEpochMilli(),
    end.atZone(SF_ZONE).toInstant().toEpochMilli()
)

/**
 * The next tow deadline: the soonest enforcement window, across [zones], that starts strictly after
 * [now]. Deterministic for a given parked row until that start passes (like the RPP deadline), which
 * is what lets the delivery markers compare equal across re-arms. Null when no window is left.
 */
fun nextTowDeadline(zones: List<TowZone>, now: LocalDateTime): TowDeadline? =
    zones.mapNotNull { z -> z.nextWindowStartingAfter(now)?.toDeadline(z) }.minByOrNull { it.startMillis }

/** The window in force right now, if the car is inside one (the one ending last, if several overlap). */
fun activeTowWindow(zones: List<TowZone>, now: LocalDateTime): TowDeadline? =
    zones.mapNotNull { z -> z.windowContaining(now)?.toDeadline(z) }.maxByOrNull { it.endMillis }

/**
 * What the advance alert is for: the soonest FIRST window of a zone that hasn't started yet. Only a
 * zone's first window, so a Monday–Friday zone gets one heads-up, not one every day. A zone already
 * under way is covered by the daily normal/urgent reminders and the park-time "in effect" notice.
 */
fun towAdvanceTarget(zones: List<TowZone>, now: LocalDateTime): TowDeadline? =
    zones.mapNotNull { z -> z.firstWindow()?.takeIf { it.start.isAfter(now) }?.toDeadline(z) }.minByOrNull { it.startMillis }

/** Whether the feed has stopped updating: its newest permit is old, or its age is unknown. */
fun towFeedIsStale(newestEntryMillis: Long?, nowMillis: Long): Boolean =
    newestEntryMillis == null || nowMillis - newestEntryMillis > TOW_FEED_STALE_AFTER_MILLIS

/** Whether the last complete tow sync is recent enough to say anything. */
fun towDataIsUsable(lastSyncMillis: Long?, nowMillis: Long): Boolean =
    lastSyncMillis != null && nowMillis - lastSyncMillis <= TOW_DATA_MAX_AGE_MILLIS

/** The tow line in the banner for one parked car. The upcoming deadline itself shows through soonestDeadline. */
sealed interface TowStatus {
    /** The car is on a zone's block and enforcement is on right now. */
    data class InEffect(val deadline: TowDeadline) : TowStatus
    /** A zone may be on the car's block (uncertain match), starting within the lead time or in force. */
    data class Nearby(val deadline: TowDeadline) : TowStatus
    /** No usable tow data at all. Never shown as "clear". */
    object Unchecked : TowStatus
    /** Synced, but the feed itself has stopped updating: a new zone on this block could be missing. */
    data class Stale(val newestEntryMillis: Long?) : TowStatus
    /** Checked with current data. Nothing to show beyond the deadline, if any. */
    object Clear : TowStatus
}

/**
 * The banner status. A real match is always shown (it's real even from old data). Without one,
 * missing data reads as Unchecked and a stale feed as Stale, never as Clear. Stale is shown even when
 * there IS a confident upcoming deadline (the deadline shows through soonestDeadline anyway), because
 * a stale feed can also be missing a SECOND, sooner zone.
 */
fun towStatusFor(
    hits: List<TowHit>,
    now: LocalDateTime,
    dataUsable: Boolean,
    newestEntryMillis: Long?,
    leadMillis: Long
): TowStatus {
    val nowMillis = now.atZone(SF_ZONE).toInstant().toEpochMilli()
    val confident = hits.filter { it.match == TowMatch.CONFIDENT }.map { it.zone }
    activeTowWindow(confident, now)?.let { return TowStatus.InEffect(it) }
    val uncertain = hits.filter { it.match == TowMatch.UNCERTAIN }.map { it.zone }
    (activeTowWindow(uncertain, now) ?: nextTowDeadline(uncertain, now)?.takeIf { it.startMillis <= nowMillis + leadMillis })
        ?.let { return TowStatus.Nearby(it) }
    return when {
        !dataUsable -> TowStatus.Unchecked
        towFeedIsStale(newestEntryMillis, nowMillis) -> TowStatus.Stale(newestEntryMillis)
        else -> TowStatus.Clear
    }
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/** "FELL ST (VAN NESS AVE to GOUGH ST)", or the address, or "your block". */
fun towPlaceLabel(zone: TowZone): String {
    val street = zone.streetName ?: return zone.address ?: "your block"
    return if (zone.fromStreet != null && zone.toStreet != null) "$street (${zone.fromStreet} to ${zone.toStreet})" else street
}

/** The label the tow reminders use in place of a corridor, e.g. "FELL ST". */
fun towReminderLabel(zone: TowZone): String = zone.streetName ?: zone.address ?: "your block"

/** "until 5:00 PM" / "until Mon, Sep 28 at 7:00 AM". */
private fun untilText(endMillis: Long, nowMillis: Long): String {
    val end = Instant.ofEpochMilli(endMillis).atZone(SF_ZONE)
    val today = Instant.ofEpochMilli(nowMillis).atZone(SF_ZONE).toLocalDate()
    return "until " + if (end.toLocalDate() == today) end.format(TIME_ONLY) else end.format(DAY_TIME)
}

/** One banner line for [status], or null for nothing to show. */
fun towBannerText(status: TowStatus?, nowMillis: Long): String? = when (status) {
    is TowStatus.InEffect -> "⚠ Tow-away zone in effect now ${untilText(status.deadline.endMillis, nowMillis)} — move your car"
    is TowStatus.Nearby -> {
        val d = status.deadline
        val window = if (d.startMillis <= nowMillis) "now" else Instant.ofEpochMilli(d.startMillis).atZone(SF_ZONE).format(DAY_TIME)
        "Tow-away zone may be on your block ($window) — check signs"
    }
    TowStatus.Unchecked -> "Tow-zone check unavailable — check signs"
    is TowStatus.Stale -> {
        val since = status.newestEntryMillis?.let { " (no new permits since ${Instant.ofEpochMilli(it).atZone(SF_ZONE).format(MONTH_DAY)})" } ?: ""
        "City tow-zone data may be out of date$since — check signs"
    }
    TowStatus.Clear, null -> null
}

/** Which tow line the collapsed banner shows when several cars have one: lower wins. */
fun towBannerRank(status: TowStatus?): Int = when (status) {
    is TowStatus.InEffect -> 0
    is TowStatus.Nearby -> 1
    TowStatus.Unchecked -> 2
    is TowStatus.Stale -> 3
    TowStatus.Clear, null -> 4
}

/** The park-time notice for an uncertain match. Never claims the car IS in the zone. */
fun towNearbyContent(carName: String, deadline: TowDeadline, nowMillis: Long): Pair<String, String> {
    val zone = deadline.zone
    val window = if (deadline.startMillis <= nowMillis) "in effect now, ${untilText(deadline.endMillis, nowMillis)}"
    else "from ${Instant.ofEpochMilli(deadline.startMillis).atZone(SF_ZONE).format(DAY_TIME)}"
    val days = zone.daysText?.let { " ($it)" } ?: ""
    return "$carName: tow-away zone nearby" to
        "A temporary tow-away zone on ${towPlaceLabel(zone)} is $window$days. It may include your spot — check signs."
}

// --- Android side ---

/**
 * Every tow zone that applies to [parked], not over yet, CONFIDENT first. Empty when the car is at a
 * saved location marked off-street (spec §3: off-street skips tow checks; closures still apply).
 */
suspend fun findTowZonesForParkedCar(context: Context, parked: ParkedState): List<TowHit> {
    val db = AppDatabase.getInstance(context)
    val fromEpochDay = LocalDate.now(SF_ZONE).minusDays(1).toEpochDay() // an overnight window from yesterday can still be on
    if (parked.parkedViaSafeLocationId != null) {
        val location = db.savedLocationDao().getAll().firstOrNull { it.id == parked.parkedViaSafeLocationId }
        if (location?.isOffStreet == true) return emptyList()
    }
    val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
    val cnn = segment?.cnn?.takeIf { it.isNotBlank() }
    if (cnn != null) {
        return db.towZoneDao().getForCnn(cnn, fromEpochDay).map { TowHit(it, TowMatch.CONFIDENT) }
    }
    // No street segment: the blocks within a few metres of where the car is.
    val point = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng)
    else LatLng(parked.parkedLat, parked.parkedLng)
    val nearbyCnns = db.streetSegmentDao().getNearby(
        point.lat - TOW_SEARCH_BOX_DEGREES, point.lat + TOW_SEARCH_BOX_DEGREES,
        point.lng - TOW_SEARCH_BOX_DEGREES, point.lng + TOW_SEARCH_BOX_DEGREES
    )
        .filter { it.cnn.isNotBlank() && (projectOntoPolyline(point, it.points)?.distanceMeters ?: Double.MAX_VALUE) <= TOW_UNCERTAIN_RADIUS_METERS }
        .map { it.cnn }
        .distinct()
    return nearbyCnns.flatMap { db.towZoneDao().getForCnn(it, fromEpochDay) }
        .distinctBy { it.rowId }
        .map { TowHit(it, TowMatch.UNCERTAIN) }
}

/** The zones the reminder family is armed for: confident matches only, and none when tow checks are off. */
internal suspend fun confidentTowZones(context: Context, parked: ParkedState, enabled: Boolean): List<TowZone> =
    if (!enabled) emptyList()
    else findTowZonesForParkedCar(context, parked).filter { it.match == TowMatch.CONFIDENT }.map { it.zone }

/** The banner status for one parked car, from stored data only. */
suspend fun resolveTowStatus(context: Context, parked: ParkedState, leadMillis: Long): TowStatus {
    val settings = SettingsRepository(context)
    val nowMillis = System.currentTimeMillis()
    return towStatusFor(
        findTowZonesForParkedCar(context, parked),
        sfNow(),
        towDataIsUsable(settings.towLastSyncMillis.first(), nowMillis),
        settings.towNewestEntryMillis.first(),
        leadMillis
    )
}

/** The next confident tow deadline for the banner/widget (soonestDeadline), or null. */
suspend fun resolveTowDeadlineMillis(context: Context, parked: ParkedState): Long? =
    nextTowDeadline(confidentTowZones(context, parked, enabled = true), sfNow())?.startMillis

/**
 * The park-time tow notices, sent once per park by the park-time check (never by re-arms):
 *  - the car is on a zone's block and enforcement is on RIGHT NOW: the urgent "in effect" notice
 *    (like the sweep-in-progress one), since the next-window reminders only cover the NEXT window;
 *  - otherwise an uncertain match starting within the lead time (or in force): one "check signs" notice.
 */
internal suspend fun notifyTowOnPark(context: Context, carId: Long, parkedAtMillis: Long) {
    val db = AppDatabase.getInstance(context)
    val parked = db.parkedStateDao().getForCar(carId)?.takeIf { it.parkedAtMillis == parkedAtMillis } ?: return
    val carName = db.carDao().getAll().firstOrNull { it.id == carId }?.name ?: "Your car"
    val hits = findTowZonesForParkedCar(context, parked)
    val now = sfNow()
    val nowMillis = System.currentTimeMillis()
    val active = activeTowWindow(hits.filter { it.match == TowMatch.CONFIDENT }.map { it.zone }, now)
    if (active != null) {
        val label = towReminderLabel(active.zone)
        val (title, text) = buildReminderContent(carName, label, active.endMillis, ReminderKind.TOW_ACTIVE)
        NotificationHelper.showReminder(
            context, reminderNotificationId(carId, ReminderKind.TOW_ACTIVE), ReminderKind.TOW_ACTIVE,
            title, text, carId, carName, label, active.endMillis
        )
        Log.d(TAG, "car $carId: parked inside tow zone ${active.zone.rowId}, in effect until ${Instant.ofEpochMilli(active.endMillis)}")
        return
    }
    val status = towStatusFor(hits, now, dataUsable = true, newestEntryMillis = nowMillis, leadMillis = closureLeadMillis(context))
    if (status is TowStatus.Nearby) {
        val (title, text) = towNearbyContent(carName, status.deadline, nowMillis)
        postStandardNotice(context, carId, NotificationIds.Purpose.TOW_NEARBY, title, text)
        Log.d(TAG, "car $carId: uncertain tow zone ${status.deadline.zone.rowId} notified at park time")
    }
}
