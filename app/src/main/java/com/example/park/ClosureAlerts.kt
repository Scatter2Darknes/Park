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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/*
 * Street-closure alerts for parked cars (docs/park-closures-spec.md §4).
 *
 * Deliberately separate from ReminderKind / scheduleTiers, the same way the meter timer is: a closure
 * is not a deadline, has no normal/urgent tiers and no snooze, and must never read as "move or get
 * towed". It has one alarm slot per car (NotificationIds.Purpose.CLOSURE_ALERT) and one delivery marker
 * (ParkedState.closureDeliveredForMillis). It is armed from armParkedState, so everything that re-arms
 * sweep and RPP reminders (boot, app open, settings changes, data syncs, roll-forward) re-arms this too.
 *
 * BLOCKED_IN gets the scheduled alert above. NEARBY gets the banner line plus ONE notice per park,
 * sent by the park-time check (owner decision, 2026-09-23): with a 200 m radius, alerting on every
 * nearby closure would notify every day next to a daily Shared Space.
 */

private const val TAG = "ClosureAlert"

/** The default of how far ahead of a closure's start the alerts go out (Settings: closureAlertLeadHours). */
const val CLOSURE_ALERT_LEAD_MILLIS = SettingsDefaults.CLOSURE_ALERT_LEAD_HOURS * 60 * 60_000L

/** Closure data older than this is too old to say "no closures" about (the feed publishes daily). */
const val CLOSURE_DATA_MAX_AGE_MILLIS = 3 * 24 * 60 * 60_000L

/** The park-time check only goes to the network when the stored data is older than this. */
const val CLOSURE_PARK_TIME_REFETCH_AFTER_MILLIS = 6 * 60 * 60_000L

/** Network budget for the park-time fetch. Kept short so the Bluetooth receiver (goAsync) isn't held long. */
const val CLOSURE_PARK_TIME_FETCH_TIMEOUT_MILLIS = 12_000L

/**
 * How long a BroadcastReceiver that just parked a car waits (inside goAsync) for the closure check
 * to finish: the fetch budget plus a little for matching and arming. Receivers for background
 * broadcasts get about a minute; the Bluetooth path may already have spent up to 8 s on a GPS fix.
 */
const val CLOSURE_CHECK_RECEIVER_WAIT_MILLIS = CLOSURE_PARK_TIME_FETCH_TIMEOUT_MILLIS + 5_000L

/** How far ahead a BLOCKED_IN closure is shown in the banner. NEARBY uses the alert lead time instead. */
const val CLOSURE_BANNER_HORIZON_MILLIS = 7 * 24 * 60 * 60_000L

/** An alarm that fires up to this early still counts as due, so a slightly early alarm can't reschedule itself forever. */
private const val DUE_TOLERANCE_MILLIS = 60_000L

// --- Pure decisions (unit tested in ClosureAlertsTest) ---

sealed interface ClosureAlertPlan {
    object None : ClosureAlertPlan
    data class ScheduleAt(val closure: StreetClosure, val triggerMillis: Long) : ClosureAlertPlan
    data class FireNow(val closure: StreetClosure) : ClosureAlertPlan
}

/**
 * Which "blocked in" alert is due next for one parked car, and when.
 *
 * Alerts go out one closure at a time, in start order. [deliveredForMillis] (the row's marker) is
 * the start of the last closure alerted about, so the next one is the first BLOCKED_IN closure
 * starting after it. Its alert is due [leadMillis] before it starts, but never while an
 * already-alerted closure on the block is still going on: a block that closes every day (a Shared
 * Space) gets one alert per closure, each after the previous one has ended, instead of several at once.
 *
 * Accepted gap: a closure added to the feed LATE that starts before one already alerted about is
 * skipped. The feed expands recurring closures well ahead, so this should be rare.
 */
fun planClosureAlert(
    hits: List<ClosureHit>,
    deliveredForMillis: Long?,
    nowMillis: Long,
    leadMillis: Long = CLOSURE_ALERT_LEAD_MILLIS
): ClosureAlertPlan {
    val blocked = hits
        .filter { it.impact == ClosureImpact.BLOCKED_IN && it.closure.endMillis > nowMillis }
        .map { it.closure }
        .sortedBy { it.startMillis }
    val delivered = deliveredForMillis ?: Long.MIN_VALUE
    val next = blocked.firstOrNull { it.startMillis > delivered } ?: return ClosureAlertPlan.None
    // The end of any already-alerted closure that is still going on.
    val ongoingAlertedEnd = blocked.filter { it.startMillis <= delivered }.maxOfOrNull { it.endMillis }
    val trigger = maxOf(next.startMillis - leadMillis, ongoingAlertedEnd ?: Long.MIN_VALUE)
    return if (trigger > nowMillis + DUE_TOLERANCE_MILLIS) ClosureAlertPlan.ScheduleAt(next, trigger)
    else ClosureAlertPlan.FireNow(next)
}

/**
 * The nearby closure worth one notice right after parking, or null. Only closures not yet over that
 * start within [leadMillis] count (the same window the banner uses). Null when a BLOCKED_IN closure
 * is also due within that window: its own alert covers the spot, and two notices at once is noise.
 */
fun pickNearbyToNotifyOnPark(hits: List<ClosureHit>, nowMillis: Long, leadMillis: Long = CLOSURE_ALERT_LEAD_MILLIS): ClosureHit? {
    fun soon(hit: ClosureHit) = hit.closure.endMillis > nowMillis && hit.closure.startMillis <= nowMillis + leadMillis
    if (hits.any { it.impact == ClosureImpact.BLOCKED_IN && soon(it) }) return null
    return hits.filter { it.impact == ClosureImpact.NEARBY && soon(it) }.minByOrNull { it.closure.startMillis }
}

/** Title and text for the one-per-park nearby notice. Informational only: never "move" or "towed". */
fun closureNearbyContent(carName: String, closure: StreetClosure, nowMillis: Long): Pair<String, String> {
    val what = closure.caseName?.let { " for $it" } ?: ""
    val text = "${closurePlaceLabel(closure)} is closed$what, ${formatClosureWindow(closure, nowMillis)}. " +
        "Check signs near your car."
    return "$carName: street closure nearby" to text
}

/**
 * The personalised line on the one-time Tier 2 offer card: how many distinct closures (a recurring
 * one, expanded into a row per day, counts once) affect the parked car in the next week. [hits] are
 * the car's closures (its block or within the nearby radius, ~2 blocks). Never claims "none" from
 * data too old to say so.
 */
fun closureOfferSummary(hits: List<ClosureHit>, dataUsable: Boolean, nowMillis: Long): String {
    val count = hits
        .filter { it.closure.endMillis > nowMillis && it.closure.startMillis <= nowMillis + CLOSURE_BANNER_HORIZON_MILLIS }
        .distinctBy { it.closure.caseNum ?: it.closure.objectId }
        .size
    return when {
        count == 1 -> "1 street closure within 2 blocks of your car this week."
        count > 1 -> "$count street closures within 2 blocks of your car this week."
        dataUsable -> "No street closures near your car this week — but new ones are permitted all the time."
        else -> "Park couldn't check for street closures near your car just now."
    }
}

/** Whether closure data last synced at [lastSyncMillis] is recent enough to say "checked". */
fun closureDataIsUsable(lastSyncMillis: Long?, nowMillis: Long): Boolean =
    lastSyncMillis != null && nowMillis - lastSyncMillis <= CLOSURE_DATA_MAX_AGE_MILLIS

/** What the banner shows about closures for one parked car. */
sealed interface ClosureStatus {
    data class Affected(val hit: ClosureHit) : ClosureStatus
    /** Checked with usable data; nothing to show. */
    object Clear : ClosureStatus
    /** No usable data: the app can't say whether the block is affected. Never shown as "clear". */
    object Unchecked : ClosureStatus
}

/**
 * The banner's closure status. A BLOCKED_IN closure within [CLOSURE_BANNER_HORIZON_MILLIS] wins; else a
 * NEARBY one within the alert lead time. A real hit is shown even from old data (it's still real);
 * with no hit, old or missing data reads as Unchecked, never as Clear.
 */
fun closureStatusFor(
    hits: List<ClosureHit>,
    dataUsable: Boolean,
    nowMillis: Long,
    leadMillis: Long = CLOSURE_ALERT_LEAD_MILLIS
): ClosureStatus {
    val shown = hits.firstOrNull {
        it.impact == ClosureImpact.BLOCKED_IN && it.closure.startMillis <= nowMillis + maxOf(CLOSURE_BANNER_HORIZON_MILLIS, leadMillis)
    } ?: hits.firstOrNull {
        it.impact == ClosureImpact.NEARBY && it.closure.startMillis <= nowMillis + leadMillis
    }
    return when {
        shown != null -> ClosureStatus.Affected(shown)
        dataUsable -> ClosureStatus.Clear
        else -> ClosureStatus.Unchecked
    }
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/**
 * The closure's time window for display: "Sat, Sep 26 at 8:00 AM – 8:00 PM" (same day), or with the
 * end date when it runs past midnight. Once started: "until 8:00 PM" / "until Mon, Sep 28 at 6:00 AM".
 */
fun formatClosureWindow(closure: StreetClosure, nowMillis: Long): String {
    val start = Instant.ofEpochMilli(closure.startMillis).atZone(SF_ZONE)
    val end = Instant.ofEpochMilli(closure.endMillis).atZone(SF_ZONE)
    val endText = if (end.toLocalDate() == start.toLocalDate()) end.format(TIME_ONLY) else end.format(DAY_TIME)
    if (closure.startMillis <= nowMillis) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(SF_ZONE).toLocalDate()
        return "until " + if (end.toLocalDate() == today) end.format(TIME_ONLY) else end.format(DAY_TIME)
    }
    return "${start.format(DAY_TIME)} – $endText"
}

/** Short place label: "PHELPS ST (MCKINNON AVE to NEWCOMB AVE)", or just the street, or "Your block". */
fun closurePlaceLabel(closure: StreetClosure): String {
    val street = closure.street ?: return "Your block"
    return if (closure.fromStreet != null && closure.toStreet != null) "$street (${closure.fromStreet} to ${closure.toStreet})" else street
}

/** One banner line for [status], or null when there's nothing to show. Never says "move" or "towed". */
fun closureBannerText(status: ClosureStatus?, nowMillis: Long): String? = when (status) {
    is ClosureStatus.Affected -> {
        val c = status.hit.closure
        val window = formatClosureWindow(c, nowMillis)
        when (status.hit.impact) {
            ClosureImpact.BLOCKED_IN ->
                if (c.startMillis <= nowMillis) "⚠ Block closed $window — may not be able to drive out"
                else "⚠ Block closes $window — may not be able to drive out"
            ClosureImpact.NEARBY -> "Street closure nearby, $window — check signs"
        }
    }
    ClosureStatus.Unchecked -> "Street-closure check unavailable"
    ClosureStatus.Clear, null -> null
}

/** Which closure line the collapsed banner shows when several cars have one: lower wins. */
fun closureBannerRank(status: ClosureStatus?): Int = when (status) {
    is ClosureStatus.Affected -> if (status.hit.impact == ClosureImpact.BLOCKED_IN) 0 else 1
    ClosureStatus.Unchecked -> 2
    ClosureStatus.Clear, null -> 3
}

/** Notification title and text for a BLOCKED_IN alert. [atSavedLocation]: a garage/driveway, where the exit may be blocked. */
fun closureAlertContent(carName: String, closure: StreetClosure, atSavedLocation: Boolean, nowMillis: Long): Pair<String, String> {
    val window = formatClosureWindow(closure, nowMillis)
    val started = closure.startMillis <= nowMillis
    val title = if (started) "$carName: your block is closed" else "$carName: your block will be closed"
    val consequence = if (atSavedLocation) "You may not be able to move your car for a while."
    else "You may not be able to drive out until it reopens."
    val what = closure.caseName?.let { " for $it" } ?: ""
    val text = "${closurePlaceLabel(closure)} is closed$what, $window. $consequence"
    return title to text
}

// --- Android side ---

/** The banner status for one parked car, from stored data only (no network). */
suspend fun resolveClosureStatus(context: Context, parked: ParkedState, lastSyncMillis: Long?, leadMillis: Long): ClosureStatus {
    val now = System.currentTimeMillis()
    val hits = findClosuresForParkedCar(context, parked, now)
    return closureStatusFor(hits, closureDataIsUsable(lastSyncMillis, now), now, leadMillis)
}

/** [closureOfferSummary] for a parked car, from stored data (call after its park-time check has finished). */
suspend fun closureOfferSummaryFor(context: Context, carId: Long): String {
    val parked = AppDatabase.getInstance(context).parkedStateDao().getForCar(carId)
        ?: return closureOfferSummary(emptyList(), dataUsable = false, nowMillis = System.currentTimeMillis())
    val now = System.currentTimeMillis()
    val hits = findClosuresForParkedCar(context, parked, now)
    val usable = closureDataIsUsable(SettingsRepository(context).closuresLastSyncMillis.first(), now)
    return closureOfferSummary(hits, usable, now)
}

/** The closure lead time currently set, in ms. */
suspend fun closureLeadMillis(context: Context): Long =
    SettingsRepository(context).closureAlertLeadHours.first() * 60 * 60_000L

private fun closureAlertPendingIntent(context: Context, carId: Long, parkedAtMillis: Long?): PendingIntent {
    val intent = Intent(context, ClosureAlertReceiver::class.java).apply {
        if (parkedAtMillis != null) {
            putExtra("carId", carId)
            putExtra("parkedAtMillis", parkedAtMillis)
        }
    }
    return PendingIntent.getBroadcast(
        context, NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_ALERT), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Cancels the closure alarm AND any closure notification on screen for a car — the "not parked here any more" path. */
fun cancelClosureAlert(context: Context, carId: Long) {
    context.getSystemService(AlarmManager::class.java).cancel(closureAlertPendingIntent(context, carId, null))
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_ALERT))
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_NEARBY))
}

/**
 * Arms (or fires, or cancels) the closure alert for one parked car from the stored closure data.
 * Called only from armParkedState, under its mutex, so two runs can't post the same alert twice.
 * Never cancels a closure notification the user can see: the alert is about a real event either way.
 * The one exception is [enabled] false (every closure feature switched off in Settings): then the
 * alarm AND any closure notification go, the same as before closures existed.
 */
internal suspend fun armClosureAlert(context: Context, parked: ParkedState, carName: String, enabled: Boolean, leadMillis: Long) {
    if (!enabled) {
        cancelClosureAlert(context, parked.carId)
        return
    }
    val now = System.currentTimeMillis()
    val hits = findClosuresForParkedCar(context, parked, now)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    when (val plan = planClosureAlert(hits, parked.closureDeliveredForMillis, now, leadMillis)) {
        ClosureAlertPlan.None -> alarmManager.cancel(closureAlertPendingIntent(context, parked.carId, null))
        is ClosureAlertPlan.ScheduleAt -> {
            Log.d(TAG, "car ${parked.carId}: closure ${plan.closure.objectId} alert at ${Instant.ofEpochMilli(plan.triggerMillis)}")
            setAlarm(
                alarmManager, canScheduleExactAlarmsCompat(context), plan.triggerMillis,
                closureAlertPendingIntent(context, parked.carId, parked.parkedAtMillis), "closure alert"
            )
        }
        is ClosureAlertPlan.FireNow -> {
            alarmManager.cancel(closureAlertPendingIntent(context, parked.carId, null))
            val atSavedLocation = parked.segmentBlockSweepId == null
            val (title, text) = closureAlertContent(carName, plan.closure, atSavedLocation, now)
            if (postStandardNotice(context, parked.carId, NotificationIds.Purpose.CLOSURE_ALERT, title, text)) {
                AppDatabase.getInstance(context).parkedStateDao()
                    .markClosureDelivered(parked.carId, parked.parkedAtMillis, plan.closure.startMillis)
                // Arm the NEXT closure, if any (its alert waits until this one has ended — see planClosureAlert).
                val next = planClosureAlert(hits, plan.closure.startMillis, now, leadMillis)
                if (next is ClosureAlertPlan.ScheduleAt) {
                    setAlarm(
                        alarmManager, canScheduleExactAlarmsCompat(context), next.triggerMillis,
                        closureAlertPendingIntent(context, parked.carId, parked.parkedAtMillis), "closure alert (next)"
                    )
                }
            }
        }
    }
}

/** Posts on the normal (standard-tier) channel. Returns false if notifications are blocked, so the marker isn't recorded. */
internal fun postStandardNotice(
    context: Context,
    carId: Long,
    purpose: NotificationIds.Purpose,
    title: String,
    text: String
): Boolean {
    val notificationId = NotificationIds.forCar(carId, purpose)
    val contentIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("reminderCarId", carId)
    }
    val pendingIntent = PendingIntent.getActivity(
        context, notificationId, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_NORMAL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()
    val manager = NotificationManagerCompat.from(context)
    val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val channelOff = manager.getNotificationChannelCompat(NotificationHelper.CHANNEL_ID_NORMAL)?.importance == NotificationManagerCompat.IMPORTANCE_NONE
    if (!granted || !manager.areNotificationsEnabled() || channelOff) {
        Log.w(TAG, "notifications blocked — closure alert not shown (granted=$granted channelOff=$channelOff)")
        return false
    }
    manager.notify(notificationId, notification)
    return true
}

/**
 * Fires when a closure alert is due. It doesn't post anything itself: it re-arms the car, and the
 * re-arm sees the alert is due (planClosureAlert -> FireNow), posts it, records the marker and arms
 * the next one. So the alarm path and the "missed while the phone was off" path are the same code.
 * Declared in AndroidManifest.xml.
 */
class ClosureAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val carId = intent.getLongExtra("carId", -1L)
        val parkedAtMillis = intent.getLongExtra("parkedAtMillis", -1L)
        if (carId == -1L || parkedAtMillis == -1L) {
            Log.w(TAG, "ClosureAlertReceiver: missing extras — ignoring")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // No-op if the car was re-parked since this alarm was set: it belonged to the old spot.
                recomputeParkedSchedule(context.applicationContext, carId, expectedParkedAtMillis = parkedAtMillis)
            } catch (e: Exception) {
                Log.w(TAG, "ClosureAlertReceiver: re-arm failed for car $carId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * The park-time closure check: runs AFTER a park is saved (never delays the save), in its own
 * process-wide scope so leaving the map screen doesn't cancel it. One job per car; a newer park of the
 * same car cancels the older check.
 */
object ClosureCheckCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun start(context: Context, carId: Long, parkedAtMillis: Long) {
        val app = context.applicationContext
        jobs.remove(carId)?.cancel()
        jobs[carId] = scope.launch { runParkTimeClosureCheck(app, carId, parkedAtMillis) }
    }

    /** Lets a BroadcastReceiver (still inside goAsync) wait for the check it just started, up to [timeoutMillis]. */
    suspend fun awaitFor(carId: Long, timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) { jobs[carId]?.join() }
    }
}

/**
 * Refreshes closure data when it's more than [CLOSURE_PARK_TIME_REFETCH_AFTER_MILLIS] old (full
 * citywide feed, short timeout), then re-arms the car so the closure alert reflects it. On a failed or
 * timed-out fetch the stored data is used; if that is missing or too old the banner says the check is
 * unavailable (closureStatusFor), never that the block is clear. Ignores the Wi-Fi-only setting on
 * purpose: at the curb the phone is usually on cellular (spec §3).
 */
private suspend fun runParkTimeClosureCheck(context: Context, carId: Long, parkedAtMillis: Long) {
    val settings = SettingsRepository(context)
    // Both closure features off: nothing to do, exactly as before closures existed (the re-arm the
    // save already ran has cancelled any closure alert). Park-time check off but Tier 2 on: no
    // fetch here, but still re-arm and notify from the background-synced data.
    // Tow zones need one of these switches too (SettingsRepository.towEnabled), so this check covers both;
    // tow's own switch is checked by TowSource's refresh and notice.
    if (!settings.closuresEnabled()) return
    val fetchAllowed = settings.closureParkTimeCheck.first()
    if (fetchAllowed) {
        // Phase 1: every source's data at once (closures and tow zones today), each inside the same short
        // budget, so the Bluetooth receiver's wait (CLOSURE_CHECK_RECEIVER_WAIT_MILLIS) still covers the whole check.
        coroutineScope {
            CurbSources.all.map { source -> async { source.refreshForPark(context) } }.awaitAll()
        }
    }
    // Re-arm the car ONCE, with whatever the refresh brought in.
    recomputeParkedSchedule(context, carId, expectedParkedAtMillis = parkedAtMillis)
    // Phase 2: the once-per-park notices, in registry order (closure, then tow).
    for (source in CurbSources.all) {
        isolated("Park-time notice (${source.id}) for car $carId") { source.parkTimeNotice(context, carId, parkedAtMillis) }
    }
    BluetoothConnectionCenter.notifyParkedStateChanged() // redraw the banner with the result
}

/** Runs [refresh] (within the park-time budget) when the data last synced at [lastSync] is older than
 *  CLOSURE_PARK_TIME_REFETCH_AFTER_MILLIS. A failure or timeout just leaves the stored data in use. */
internal suspend fun refreshIfOlderThan(lastSync: Long?, what: String, refresh: suspend () -> Int) {
    if (lastSync != null && System.currentTimeMillis() - lastSync <= CLOSURE_PARK_TIME_REFETCH_AFTER_MILLIS) return
    try {
        withTimeout(CLOSURE_PARK_TIME_FETCH_TIMEOUT_MILLIS) { refresh() }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "park-time $what fetch timed out after ${CLOSURE_PARK_TIME_FETCH_TIMEOUT_MILLIS}ms — using stored data")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // a newer park replaced this check
    } catch (e: Exception) {
        Log.w(TAG, "park-time $what fetch failed — using stored data", e)
    }
}

/**
 * The one-per-park nearby notice. Only ever called from the park-time check, which runs once per
 * park, so it needs no delivery marker: re-arms (boot, app open, syncs) never repeat it.
 */
internal suspend fun notifyNearbyClosureOnPark(context: Context, carId: Long, parkedAtMillis: Long) {
    val db = AppDatabase.getInstance(context)
    val parked = db.parkedStateDao().getForCar(carId)?.takeIf { it.parkedAtMillis == parkedAtMillis } ?: return // re-parked meanwhile
    val carName = db.carDao().getAll().firstOrNull { it.id == carId }?.name ?: "Your car"
    val now = System.currentTimeMillis()
    val hits = findClosuresForParkedCar(context, parked, now)
    val nearby = pickNearbyToNotifyOnPark(hits, now, closureLeadMillis(context)) ?: return
    val (title, text) = closureNearbyContent(carName, nearby.closure, now)
    postStandardNotice(context, carId, NotificationIds.Purpose.CLOSURE_NEARBY, title, text)
    Log.d(TAG, "car $carId: nearby closure ${nearby.closure.objectId} notified at park time")
}
