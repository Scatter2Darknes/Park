package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * DEBUG BUILDS ONLY. Lets a script drive and inspect the app over adb without tapping through the UI, so emulator
 * scenarios can run unattended (park -> check alarms -> reboot -> check again). Every result is written to
 * Logcat under the tag "ParkDebug".
 *
 * WHY THIS CANNOT EXIST IN A RELEASE BUILD: this file lives in app/src/debug/, which Gradle only compiles into
 * the debug variant, and the manifest entry that makes it reachable is in app/src/debug/AndroidManifest.xml,
 * which likewise only merges into debug. scripts/check_release_manifest.py verifies that a release APK contains
 * neither. (It is exported so `adb shell am broadcast` can reach it - which is exactly why it must never ship.)
 *
 * Try it (emulator; car ids are shown by DUMP_STATE). lat/lng are sent as strings (--es) because `am broadcast --ed`
 * doesn't exist on API 29; numeric extras are still accepted:
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.DUMP_STATE
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.PARK --el carId 1 --es lat 37.7749 --es lng -122.4194
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.UNPARK --el carId 1
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.REARM
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.INJECT_CLOSURE --el carId 1 --es kind blocked --ei startInMinutes 5 --ei durationMinutes 60
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.CLEAR_DEBUG_CLOSURES
 * then read the answer with:  adb logcat -d -s ParkDebug
 *
 * INJECT_CLOSURE puts a fake street closure on the parked car's block (kind=blocked, the default) or ~120 m
 * away (kind=nearby), starting startInMinutes from now (default 3 days, i.e. before the 2-day alert point) and
 * lasting durationMinutes (default 12 h), then re-arms that car. Fake rows have ids starting "debug-"; a real
 * closure sync removes them as unseen, and CLEAR_DEBUG_CLOSURES removes them directly.
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext
        val carId = intent.getLongExtra("carId", -1L)
        // lat/lng arrive as strings (--es) because `am broadcast --ed` doesn't exist on API 29; numbers still work.
        val extras = intent.extras
        @Suppress("DEPRECATION") // Bundle.get is the only way to accept a String or a Double under the same key
        val lat = DebugCoordinate.parse(extras?.get("lat"))
        @Suppress("DEPRECATION")
        val lng = DebugCoordinate.parse(extras?.get("lng"))

        // Same pattern as the app's other receivers: goAsync() keeps the process alive while the coroutine runs.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_DUMP_STATE -> dumpState(app)
                    ACTION_PARK -> park(app, carId, lat, lng)
                    ACTION_UNPARK -> unpark(app, carId)
                    ACTION_REARM -> rearm(app)
                    ACTION_INJECT_CLOSURE -> injectClosure(
                        app, carId,
                        kind = intent.getStringExtra("kind") ?: "blocked",
                        startInMinutes = intent.getIntExtra("startInMinutes", 3 * 24 * 60),
                        durationMinutes = intent.getIntExtra("durationMinutes", 12 * 60)
                    )
                    ACTION_CLEAR_DEBUG_CLOSURES -> clearDebugClosures(app)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (e: Exception) {
                Log.e(TAG, "$action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun park(context: Context, carId: Long, latExtra: DebugCoordinate, lngExtra: DebugCoordinate) {
        // "PARK rejected: ..." is what scripts/debug_hooks.py turns into a non-zero exit.
        val problems = listOfNotNull(
            if (carId < 0) "carId is missing (send --el carId <id>)" else null,
            latExtra.problem("lat"),
            lngExtra.problem("lng"),
        )
        if (problems.isNotEmpty()) {
            Log.w(TAG, "PARK rejected: ${problems.joinToString("; ")}")
            return
        }
        val lat = (latExtra as DebugCoordinate.Value).degrees
        val lng = (lngExtra as DebugCoordinate.Value).degrees
        val point = LatLng(lat, lng)
        val matches = findNearbySegmentMatches(context, point)
        val confidence = classifyMatch(matches)
        if (matches.isEmpty() || confidence == MatchConfidence.NO_MATCH) {
            Log.w(TAG, "PARK: no street segment within 30 m of $lat,$lng (confidence=$confidence) - nothing saved")
            return
        }
        val segment = matches.first().segment
        // The normal path: exactly what the Bluetooth auto-park and the Parked button end up calling.
        saveParkedState(context, carId, segment, point)
        Log.i(TAG, "PARK: car $carId saved on ${segment.corridor} (${segment.limits}), segment=${segment.blockSweepId}, " +
                "curb=${CurbSchedule.curbKey(segment)}, confidence=$confidence, ${matches.size} candidate curb(s)")
    }

    private suspend fun unpark(context: Context, carId: Long) {
        if (carId < 0) {
            Log.w(TAG, "UNPARK rejected: carId is missing (send --el carId <id>)")
            return
        }
        unsubscribeParking(context, carId)
        Log.i(TAG, "UNPARK: car $carId cleared (reminders cancelled, parked state removed)")
    }

    private suspend fun injectClosure(context: Context, carId: Long, kind: String, startInMinutes: Int, durationMinutes: Int) {
        val db = AppDatabase.getInstance(context)
        val parked = if (carId < 0) null else db.parkedStateDao().getForCar(carId)
        if (parked == null || kind !in setOf("blocked", "nearby") || durationMinutes <= 0) {
            Log.w(TAG, "INJECT_CLOSURE rejected: need a PARKED carId (got $carId), kind blocked|nearby (got $kind), durationMinutes > 0")
            return
        }
        val base = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng)
        else LatLng(parked.parkedLat, parked.parkedLng)
        val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
        val halfBlockLng = 50.0 / (111320.0 * Math.cos(Math.toRadians(base.lat))) // ~50 m east-west
        val points = when {
            kind == "nearby" -> {
                val north = base.lat + 120.0 / 111320.0 // ~120 m north: inside the nearby radius, not on the block
                listOf(LatLng(north, base.lng - halfBlockLng), LatLng(north, base.lng + halfBlockLng))
            }
            segment != null && segment.points.size >= 2 -> segment.points
            else -> listOf(LatLng(base.lat, base.lng - halfBlockLng), LatLng(base.lat, base.lng + halfBlockLng))
        }
        val now = System.currentTimeMillis()
        val start = now + startInMinutes * 60_000L
        val closure = StreetClosure(
            objectId = "debug-$now", caseNum = null, caseName = "Debug closure", type = "Special Event",
            cnn = if (kind == "blocked") segment?.cnn ?: "debug-block" else "debug-nearby",
            street = segment?.corridor ?: "DEBUG ST", fromStreet = null, toStreet = null,
            vehicleImpact = "all-lanes-closed", startMillis = start, endMillis = start + durationMinutes * 60_000L,
            points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
        )
        db.streetClosureDao().insertAll(listOf(closure))
        recomputeParkedSchedule(context, carId)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        val status = resolveClosureStatus(context, parked, SettingsRepository(context).closuresLastSyncMillis.first())
        Log.i(TAG, "INJECT_CLOSURE: ${closure.objectId} kind=$kind start=${fmt(start)} end=${fmt(closure.endMillis)} " +
                "-> banner: ${closureBannerText(status, System.currentTimeMillis())}")
    }

    private suspend fun clearDebugClosures(context: Context) {
        val removed = AppDatabase.getInstance(context).streetClosureDao().deleteDebugRows()
        rearmAllActiveReminders(context)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        Log.i(TAG, "CLEAR_DEBUG_CLOSURES: removed $removed fake closure(s), re-armed")
    }

    private suspend fun rearm(context: Context) {
        // The same function BootReceiver and the app-foreground hook call.
        rearmAllActiveReminders(context)
        Log.i(TAG, "REARM: rearmAllActiveReminders finished")
    }

    /**
     * Everything the app believes about its parked cars and the alarms it should therefore have, so it can be
     * compared with the UI and with `dumpsys alarm` (scripts/alarms.py). "Expected alarms" is recomputed here
     * from the stored data with the same offsets the scheduler uses; it is what the app THINKS it scheduled,
     * not a query of the system's alarm list.
     */
    private suspend fun dumpState(context: Context) {
        val db = AppDatabase.getInstance(context)
        val settings = SettingsRepository(context)
        val nowMillis = System.currentTimeMillis()
        val offsetMillis = settings.notificationOffsetMinutes.first() * 60_000L
        val urgentMillis = if (settings.urgentReminderEnabled.first()) settings.urgentOffsetMinutes.first() * 60_000L else null

        Log.i(TAG, "DUMP_STATE begin: now=${fmt(nowMillis)} exactAlarmsAllowed=${canScheduleExactAlarmsCompat(context)} " +
                "reminderOffsetMin=${offsetMillis / 60_000} urgentOffsetMin=${urgentMillis?.let { it / 60_000 }}")

        // Can the reminders be SHOWN? (alarms can be perfect and Android still drops the notifications)
        val notifManager = androidx.core.app.NotificationManagerCompat.from(context)
        fun importance(channelId: String) = notifManager.getNotificationChannelCompat(channelId)?.importance
        Log.i(TAG, "notifications: notificationsEnabled=${notifManager.areNotificationsEnabled()} " +
                "remindersChannelImportance=${importance(NotificationHelper.CHANNEL_ID_NORMAL)} " +
                "urgentChannelImportance=${importance(NotificationHelper.CHANNEL_ID_URGENT)} " +
                "statusChannelImportance=${importance(NotificationHelper.CHANNEL_ID_STATUS)} " +
                "reminderHealth=${currentReminderHealth(context).health}")

        val cars = db.carDao().getAll()
        Log.i(TAG, "cars: " + cars.joinToString("; ") { "id=${it.id} name='${it.name}' permitZones=${it.permitZoneLetters}" })

        val parkedStates = db.parkedStateDao().getAll()
        Log.i(TAG, "parked states: ${parkedStates.size}")
        var expectedAlarms = 0
        for (parked in parkedStates) {
            val car = cars.firstOrNull { it.id == parked.carId }
            val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
            val curbRows = segment?.let { loadCurbRows(context, it) }
            val recomputed = curbRows?.let { CurbSchedule.nextSweepDateTime(it) }
                ?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()
            Log.i(TAG, "PARKED car=${parked.carId} '${car?.name}' segment=${parked.segmentBlockSweepId} " +
                    "curb=${segment?.let { CurbSchedule.curbKey(it) }} curbRows=${curbRows?.size} " +
                    "corridor='${segment?.corridor}' sideConfirmed=${parked.sideConfirmed} parkedAt=${fmt(parked.parkedAtMillis)}")
            Log.i(TAG, "  sweep: stored=${fmt(parked.nextSweepAtMillis)} recomputedNow=${fmt(recomputed)} " +
                    "notificationScheduled=${parked.notificationScheduled}")
            Log.i(TAG, "  delivered-for-deadline markers: normal=${fmt(parked.normalDeliveredForMillis)} " +
                    "urgent=${fmt(parked.urgentDeliveredForMillis)} rppNormal=${fmt(parked.rppNormalDeliveredForMillis)} " +
                    "rppUrgent=${fmt(parked.rppUrgentDeliveredForMillis)} closure=${fmt(parked.closureDeliveredForMillis)}")
            val closureStatus = resolveClosureStatus(context, parked, settings.closuresLastSyncMillis.first())
            Log.i(TAG, "  closures: dataLastSynced=${fmt(settings.closuresLastSyncMillis.first())} " +
                    "banner='${closureBannerText(closureStatus, nowMillis)}'")

            parked.nextSweepAtMillis?.let { deadline ->
                expectedAlarms += expect("car ${parked.carId} sweep reminder", deadline - offsetMillis, nowMillis)
                if (urgentMillis != null) expectedAlarms += expect("car ${parked.carId} sweep URGENT reminder", deadline - urgentMillis, nowMillis)
                expectedAlarms += expect("car ${parked.carId} sweep roll-forward", deadline, nowMillis)
            }

            val regulation = parked.rppRegulationId?.let { db.rppZoneRegulationDao().getById(it) }
            if (regulation == null) {
                Log.i(TAG, "  rpp: none (regulationId=${parked.rppRegulationId})")
            } else if (car != null) {
                val parkedSince = Instant.ofEpochMilli(parked.parkedAtMillis).atZone(SF_ZONE).toLocalDateTime()
                val warning = nextRppDeadline(regulation, car, parkedSince, sfNow())
                Log.i(TAG, "  rpp: regulation=${regulation.objectId} zone=${regulation.zoneLetters} days=${regulation.days} " +
                        "${regulation.hrsBegin}-${regulation.hrsEnd} hrLimit=${regulation.hrLimit} limitAssumed=${regulation.limitAssumed} " +
                        "deadline=${warning?.moveByDateTime} markers(n/u)=${fmt(parked.rppNormalDeliveredForMillis)}/${fmt(parked.rppUrgentDeliveredForMillis)}")
                if (warning != null) {
                    val moveBy = warning.moveByDateTime.atZone(SF_ZONE).toInstant().toEpochMilli()
                    expectedAlarms += expect("car ${parked.carId} RPP reminder", moveBy - offsetMillis, nowMillis)
                    if (urgentMillis != null) expectedAlarms += expect("car ${parked.carId} RPP URGENT reminder", moveBy - urgentMillis, nowMillis)
                    expectedAlarms += expect("car ${parked.carId} RPP roll-forward", rppWindowEndMillis(regulation, warning.moveByDateTime), nowMillis)
                }
            }
        }
        Log.i(TAG, "DUMP_STATE end: the app expects $expectedAlarms future alarm(s) for ${parkedStates.size} parked car(s)")
    }

    /** Logs one expected alarm and returns 1 if it is still in the future (i.e. should be a live alarm), else 0. */
    private fun expect(label: String, triggerMillis: Long, nowMillis: Long): Int {
        val future = triggerMillis > nowMillis
        Log.i(TAG, "  expected alarm: $label at ${fmt(triggerMillis)}${if (future) "" else "  (already in the past - fires immediately or is skipped)"}")
        return if (future) 1 else 0
    }

    private fun fmt(millis: Long?): String =
        if (millis == null) "null" else "${Instant.ofEpochMilli(millis).atZone(SF_ZONE).toLocalDateTime()} PT ($millis)"

    companion object {
        const val TAG = "ParkDebug"
        const val ACTION_DUMP_STATE = "com.example.park.debug.DUMP_STATE"
        const val ACTION_PARK = "com.example.park.debug.PARK"
        const val ACTION_UNPARK = "com.example.park.debug.UNPARK"
        const val ACTION_REARM = "com.example.park.debug.REARM"
        const val ACTION_INJECT_CLOSURE = "com.example.park.debug.INJECT_CLOSURE"
        const val ACTION_CLEAR_DEBUG_CLOSURES = "com.example.park.debug.CLEAR_DEBUG_CLOSURES"
    }
}
