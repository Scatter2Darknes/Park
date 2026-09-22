package com.example.park

import android.content.Context
import android.util.Log

private const val TAG = "SavedLocationRecompute"

/**
 * Keeps already-parked cars in sync with a SavedLocation's safe-from-sweeping flag changing
 * after the fact — editing or deleting a safe-tagged location, or newly marking one safe, used
 * to leave every car's ParkedState exactly as it was, which could mean a car silently keeps no
 * sweep reminder (because it was parked via a location that's no longer safe) or silently keeps
 * one (because the location it's parked at just became safe). Both directions are handled here,
 * mirroring the existing recomputeSchedulesForSegment/recomputeParkedSchedule pattern in
 * NotificationScheduler.kt for a schedule-override change.
 */

/**
 * Location [locationId] just stopped being safe — edited off, or deleted (call this with the
 * about-to-be-deleted row's id right after the delete). Re-evaluates every car that was parked
 * unmanaged specifically because of it (ParkedState.parkedViaSafeLocationId == locationId —
 * never touches a car parked unmanaged some other way, e.g. the manual "not a street cleaning
 * risk spot" flow). Each affected car is re-matched against current segment data at its own
 * stored point: a confident or ambiguous match re-establishes a real park (reminders back on,
 * same as an auto-repark); no match at all leaves it unmanaged but clears the now-stale link.
 */
suspend fun reevaluateCarsFormerlySafeAt(context: Context, locationId: Long) {
    val db = AppDatabase.getInstance(context)
    val affected = db.parkedStateDao().getForSafeLocation(locationId)
    Log.d(TAG, "reevaluateCarsFormerlySafeAt: locationId=$locationId, ${affected.size} car(s) parked via it")
    for (parked in affected) {
        val car = db.carDao().getAll().firstOrNull { it.id == parked.carId } ?: continue
        // Guard against racing a fresh re-park of this same car mid-recompute — if it's moved
        // on since [affected] was loaded, this stale row's recompute is no longer relevant.
        if (db.parkedStateDao().getForCar(car.id)?.parkedAtMillis != parked.parkedAtMillis) continue

        val point = LatLng(parked.exactPinLat ?: parked.parkedLat, parked.exactPinLng ?: parked.parkedLng)
        val matches = findNearbySegmentMatches(context, point)
        val confidence = classifyMatch(matches)
        Log.d(TAG, "reevaluateCarsFormerlySafeAt: car ${car.id} at $point -> ${matches.size} matches, confidence=$confidence" +
                (matches.firstOrNull()?.let { ", closest=${it.distanceMeters}m" } ?: ""))
        val timeoutMillis = SettingsRepository(context).informationalNotificationTimeoutMillis()

        when (confidence) {
            MatchConfidence.CONFIDENT -> {
                val segment = matches.first().segment
                saveParkedState(context, car.id, segment, point, parked.exactPinLat, parked.exactPinLng)
                showAutoDetectNotification(
                    context = context,
                    car = car,
                    title = "Reminders re-enabled for ${car.name}",
                    text = "The saved location marking this spot safe changed — found street cleaning near ${segment.corridor}.",
                    centerPoint = point,
                    informational = true,
                    timeoutAfterMillis = timeoutMillis,
                    purpose = NotificationIds.Purpose.SAVED_LOCATION_RECOMPUTE
                )
            }
            MatchConfidence.AMBIGUOUS -> {
                val segment = matches.first().segment
                saveParkedState(context, car.id, segment, point, parked.exactPinLat, parked.exactPinLng, sideConfirmed = false)
                showAutoDetectNotification(
                    context = context,
                    car = car,
                    title = "Confirm ${car.name}'s spot",
                    text = "The saved location marking this spot safe changed — guessed near ${segment.corridor}, tap to confirm or correct.",
                    autoDetectCarId = car.id,
                    autoDetectPoint = point,
                    purpose = NotificationIds.Purpose.SAVED_LOCATION_RECOMPUTE
                    // No timeout — same reasoning as every other AMBIGUOUS/NO_MATCH confirm
                    // prompt in this app: this is the only way into the manual confirm flow.
                )
            }
            MatchConfidence.NO_MATCH -> {
                // Still no street data here — correctly stays unmanaged, but the location that
                // used to vouch for it is gone/changed, so re-save without a viaSafeLocationId
                // link rather than leaving one pointing at a location that's no longer safe.
                saveUnmanagedParkedState(context, car.id, point, parked.exactPinLat, parked.exactPinLng)
            }
        }
        BluetoothConnectionCenter.notifyParkedStateChanged()
        enqueueWidgetRefresh(context)
    }
}

/**
 * [location] just became safe — newly marked in LocationStyleDialog, or restored via "Undo"
 * after a delete. Converts any currently-managed parked car (a real segment, real sweep
 * reminder) sitting within SAFE_LOCATION_MATCH_RADIUS_METERS of it to unmanaged — this is a
 * direct, deliberate consequence of the user's own action, so unlike the "formerly safe"
 * direction above, nothing here is a guess and no confirm prompt is needed.
 */
suspend fun convertCarsNowSafeAt(context: Context, location: SavedLocation) {
    val db = AppDatabase.getInstance(context)
    val locationPoint = location.toLatLng()
    val timeoutMillis = SettingsRepository(context).informationalNotificationTimeoutMillis()

    val managedParked = db.parkedStateDao().getAll().filter { it.segmentBlockSweepId != null }
    Log.d(TAG, "convertCarsNowSafeAt: location '${location.name}' (id=${location.id}) at $locationPoint, ${managedParked.size} managed-parked car(s) to check")
    managedParked.forEach { parked ->
        val carPoint = LatLng(parked.exactPinLat ?: parked.parkedLat, parked.exactPinLng ?: parked.parkedLng)
        val distance = distanceMetersBetween(locationPoint, carPoint)
        Log.d(TAG, "convertCarsNowSafeAt: car ${parked.carId} at $carPoint is ${distance}m from the location (radius=$SAFE_LOCATION_MATCH_RADIUS_METERS)")
        if (distance > SAFE_LOCATION_MATCH_RADIUS_METERS) return@forEach

        val car = db.carDao().getAll().firstOrNull { it.id == parked.carId } ?: return@forEach
        // Same race guard as reevaluateCarsFormerlySafeAt.
        if (db.parkedStateDao().getForCar(car.id)?.parkedAtMillis != parked.parkedAtMillis) return@forEach

        saveUnmanagedParkedState(context, car.id, carPoint, parked.exactPinLat, parked.exactPinLng, viaSafeLocationId = location.id)
        showAutoDetectNotification(
            context = context,
            car = car,
            title = "Sweep reminders turned off for ${car.name}",
            text = "${location.name} is now marked safe from street cleaning.",
            centerPoint = carPoint,
            informational = true,
            timeoutAfterMillis = timeoutMillis,
            purpose = NotificationIds.Purpose.SAVED_LOCATION_RECOMPUTE
        )
        BluetoothConnectionCenter.notifyParkedStateChanged()
        enqueueWidgetRefresh(context)
    }
}
