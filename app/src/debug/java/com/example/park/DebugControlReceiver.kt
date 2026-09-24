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
 *
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.INJECT_TOW --el carId 1 --ei startInMinutes 2885 --ei durationMinutes 600 --ei days 1
 *   adb shell am broadcast -n com.example.park/.DebugControlReceiver -a com.example.park.debug.CLEAR_DEBUG_TOW
 *
 * INJECT_TOW puts a fake temporary tow zone on the parked car's block (its street segment's CNN; for a park with
 * no segment, the nearest street within TOW_UNCERTAIN_RADIUS_METERS, which the app then treats as an UNCERTAIN
 * match). Each day's window starts at the time of day startInMinutes from now and lasts durationMinutes (under
 * 24 h), for `days` days in a row, every weekday. Optional feedAgeDays (>= 0) also pretends the last tow sync ran
 * just now and the feed's newest permit is that many days old, to test the "data may be out of date" wording.
 * Fake rows have ids starting "debug-tow-"; CLEAR_DEBUG_TOW removes them.
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
                    ACTION_INJECT_TOW -> injectTow(
                        app, carId,
                        startInMinutes = intent.getIntExtra("startInMinutes", 3 * 24 * 60),
                        durationMinutes = intent.getIntExtra("durationMinutes", 10 * 60),
                        days = intent.getIntExtra("days", 1),
                        feedAgeDays = intent.getIntExtra("feedAgeDays", -1)
                    )
                    ACTION_CLEAR_DEBUG_TOW -> clearDebugTow(app)
                    ACTION_RESET_CLOSURE_OFFER -> {
                        SettingsRepository(app).resetClosureTier2OfferShown()
                        Log.i(TAG, "RESET_CLOSURE_OFFER: the one-time background-sync offer will show after the next manual park " +
                                "(if background sync is off)")
                    }
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
        val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
        // Same "where is the car" rule the closure matcher uses (closureMatchOrigin).
        val pin = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng) else null
        val curbSegment = segment?.takeIf { it.points.size >= 2 }
        val base = closureMatchOrigin(
            exactPin = pin,
            curbMidpoint = curbSegment?.let { midpointAlongPath(offsetPolylineForSide(it.points, it.cnnRightLeft)) },
            parkedPoint = LatLng(parked.parkedLat, parked.parkedLng),
            pinToCurbMeters = if (pin != null && curbSegment != null) pinDistanceFromCurbMeters(curbSegment, pin) else null
        )
        val halfBlockLng = 50.0 / (111320.0 * Math.cos(Math.toRadians(base.lat))) // ~50 m east-west
        // "nearby": a REAL street 60–190 m from the car's curb (inside the nearby radius, not its
        // block), so the fake closure is drawn where a real one could be. Only if none is in range, a
        // straight line ~120 m north (which can land between streets, e.g. in back yards).
        val nearbyStreet = if (kind == "nearby") {
            val box = 0.003
            db.streetSegmentDao().getNearby(base.lat - box, base.lat + box, base.lng - box, base.lng + box)
                .filter { it.points.size >= 2 && it.cnn != segment?.cnn }
                .map { it to distancePointToPolylineMeters(base, it.points) }
                .filter { (_, d) -> d in 60.0..190.0 }
                .minByOrNull { (_, d) -> d }?.first
        } else null
        val points = when {
            nearbyStreet != null -> nearbyStreet.points
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
            cnn = when {
                kind == "blocked" -> segment?.cnn ?: "debug-block"
                nearbyStreet != null -> nearbyStreet.cnn
                else -> "debug-nearby"
            },
            street = (if (kind == "nearby") nearbyStreet?.corridor else segment?.corridor) ?: "DEBUG ST",
            fromStreet = null, toStreet = null,
            vehicleImpact = "all-lanes-closed", startMillis = start, endMillis = start + durationMinutes * 60_000L,
            points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
        )
        db.streetClosureDao().insertAll(listOf(closure))
        recomputeParkedSchedule(context, carId)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        val status = resolveClosureStatus(context, parked, SettingsRepository(context).closuresLastSyncMillis.first(), closureLeadMillis(context))
        Log.i(TAG, "INJECT_CLOSURE: ${closure.objectId} kind=$kind street='${closure.street}' start=${fmt(start)} end=${fmt(closure.endMillis)} " +
                "-> banner: ${closureBannerText(status, System.currentTimeMillis())}")
    }

    private suspend fun clearDebugClosures(context: Context) {
        val removed = AppDatabase.getInstance(context).streetClosureDao().deleteDebugRows()
        rearmAllActiveReminders(context)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        Log.i(TAG, "CLEAR_DEBUG_CLOSURES: removed $removed fake closure(s), re-armed")
    }

    private suspend fun injectTow(context: Context, carId: Long, startInMinutes: Int, durationMinutes: Int, days: Int, feedAgeDays: Int) {
        val db = AppDatabase.getInstance(context)
        val parked = if (carId < 0) null else db.parkedStateDao().getForCar(carId)
        if (parked == null || durationMinutes !in 1 until 24 * 60 || days < 1) {
            Log.w(TAG, "INJECT_TOW rejected: need a PARKED carId (got $carId), durationMinutes 1..1439 (got $durationMinutes), days >= 1 (got $days)")
            return
        }
        val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
        // No segment: the nearest street, so the fake zone lands where findTowZonesForParkedCar looks (as UNCERTAIN).
        val point = LatLng(parked.exactPinLat ?: parked.parkedLat, parked.exactPinLng ?: parked.parkedLng)
        val street = segment ?: db.streetSegmentDao().getNearby(point.lat - 0.0005, point.lat + 0.0005, point.lng - 0.0005, point.lng + 0.0005)
            .filter { it.cnn.isNotBlank() }
            .minByOrNull { projectOntoPolyline(point, it.points)?.distanceMeters ?: Double.MAX_VALUE }
        if (street == null || street.cnn.isBlank()) {
            Log.w(TAG, "INJECT_TOW rejected: no street near car $carId to put a tow zone on")
            return
        }
        val now = System.currentTimeMillis()
        val start = Instant.ofEpochMilli(now + startInMinutes * 60_000L).atZone(SF_ZONE).toLocalDateTime()
        val startMinute = start.hour * 60 + start.minute
        val zone = TowZone(
            rowId = "$DEBUG_TOW_ROW_PREFIX$now", caseNumber = "DEBUG", permitNumber = null,
            cnns = towCnnList(listOf(street.cnn)), address = null, streetName = street.corridor,
            fromStreet = null, toStreet = null,
            startEpochDay = start.toLocalDate().toEpochDay(),
            endEpochDay = start.toLocalDate().plusDays(days - 1L).toEpochDay(),
            startMinute = startMinute, endMinute = (startMinute + durationMinutes) % (24 * 60),
            allDay = false, daysMask = ALL_DAYS_MASK, daysText = "Monday - Sunday (debug)",
            enteredMillis = now
        )
        db.towZoneDao().insertAll(listOf(zone))
        if (feedAgeDays >= 0) {
            val settings = SettingsRepository(context)
            settings.setTowLastSyncMillis(now)
            settings.setTowNewestEntryMillis(now - feedAgeDays * 24L * 60 * 60_000L)
        }
        recomputeParkedSchedule(context, carId)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        val match = findTowZonesForParkedCar(context, parked).firstOrNull { it.zone.rowId == zone.rowId }?.match
        val status = resolveTowStatus(context, parked, closureLeadMillis(context))
        Log.i(TAG, "INJECT_TOW: ${zone.rowId} street='${street.corridor}' cnn=${street.cnn} match=$match " +
                "first window ${start} for ${durationMinutes} min, $days day(s) " +
                "-> deadline=${fmt(resolveTowDeadlineMillis(context, parked))} banner: ${towBannerText(status, System.currentTimeMillis())}")
    }

    private suspend fun clearDebugTow(context: Context) {
        val removed = AppDatabase.getInstance(context).towZoneDao().deleteDebugRows()
        rearmAllActiveReminders(context)
        BluetoothConnectionCenter.notifyParkedStateChanged()
        Log.i(TAG, "CLEAR_DEBUG_TOW: removed $removed fake tow zone(s), re-armed")
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
            val closureStatus = resolveClosureStatus(context, parked, settings.closuresLastSyncMillis.first(), closureLeadMillis(context))
            Log.i(TAG, "  closures: parkTimeCheck=${settings.closureParkTimeCheck.first()} " +
                    "backgroundSync=${settings.closureBackgroundSync.first()} leadHours=${settings.closureAlertLeadHours.first()} " +
                    "tier2OfferShown=${settings.closureTier2OfferShown.first()} " +
                    "dataLastSynced=${fmt(settings.closuresLastSyncMillis.first())} " +
                    "banner='${if (settings.closuresEnabled()) closureBannerText(closureStatus, nowMillis) else "(closures off)"}'")

            val towDeadline = if (settings.towEnabled()) nextTowDeadline(confidentTowZones(context, parked, enabled = true), sfNow()) else null
            val towAdvance = if (settings.towEnabled()) towAdvanceTarget(confidentTowZones(context, parked, enabled = true), sfNow()) else null
            Log.i(TAG, "  tow: enabled=${settings.towEnabled()} matches=${findTowZonesForParkedCar(context, parked).joinToString { "${it.zone.rowId}/${it.match}" }} " +
                    "deadline=${fmt(towDeadline?.startMillis)} advanceFor=${fmt(towAdvance?.startMillis)} " +
                    "dataLastSynced=${fmt(settings.towLastSyncMillis.first())} newestPermit=${fmt(settings.towNewestEntryMillis.first())} " +
                    "markers(n/u/adv)=${fmt(parked.towNormalDeliveredForMillis)}/${fmt(parked.towUrgentDeliveredForMillis)}/${fmt(parked.towAdvanceDeliveredForMillis)} " +
                    "banner='${if (settings.towEnabled()) towBannerText(resolveTowStatus(context, parked, closureLeadMillis(context)), nowMillis) else "(tow off)"}'")
            towDeadline?.let { deadline ->
                expectedAlarms += expect("car ${parked.carId} tow reminder", deadline.startMillis - offsetMillis, nowMillis)
                if (urgentMillis != null) expectedAlarms += expect("car ${parked.carId} tow URGENT reminder", deadline.startMillis - urgentMillis, nowMillis)
                expectedAlarms += expect("car ${parked.carId} tow roll-forward", deadline.startMillis, nowMillis)
            }
            towAdvance?.let { expectedAlarms += expect("car ${parked.carId} tow ADVANCE alert", it.startMillis - closureLeadMillis(context), nowMillis) }

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
        const val ACTION_RESET_CLOSURE_OFFER = "com.example.park.debug.RESET_CLOSURE_OFFER"
        const val ACTION_INJECT_TOW = "com.example.park.debug.INJECT_TOW"
        const val ACTION_CLEAR_DEBUG_TOW = "com.example.park.debug.CLEAR_DEBUG_TOW"
    }
}
