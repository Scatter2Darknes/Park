package com.example.park

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// Shared across both Bluetooth receiver files — filter Logcat on this tag to see exactly
// where the flow succeeds or breaks down when diagnosing "why didn't this fire".
const val BLUETOOTH_AUTO_DETECT_LOG_TAG = "ParkBluetooth"

// Offset so these notifications' IDs never collide with the parking-reminder IDs
// (carId and carId + 1,000,000) used elsewhere.
private const val AUTO_DETECT_NOTIFICATION_ID_OFFSET = 2_000_000

/**
 * Enables or disables BluetoothConnectReceiver/BluetoothDisconnectReceiver at the
 * PackageManager level — not just an internal flag these receivers check on their own.
 *
 * Both are manifest-registered (required — see the class doc comments above and in
 * BluetoothConnectReceiver.kt for why), which means Android wakes this app's process for
 * EVERY Bluetooth ACL connect/disconnect on the whole device, for any device, not just one
 * linked to a car — the receiver only finds out it has nothing to do once it's already
 * running and queried the DB. For someone who never links a car to a Bluetooth device at all,
 * that's a pure, avoidable background wakeup with zero benefit, and exactly the pattern
 * Android's own battery/app-standby stats flag apps for. Actually disabling the components
 * (rather than only gating inside onReceive, which still costs a process start) stops the OS
 * from delivering the broadcast to this app at all.
 *
 * DONT_KILL_APP: this can be called while the app is running (from the Settings toggle) —
 * without this flag, changing a component's enabled state kills and restarts the whole app
 * process, which would be a jarring surprise right after tapping a toggle.
 */
fun applyBluetoothAutoDetectComponentState(context: Context, enabled: Boolean) {
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    val pm = context.packageManager
    listOf(
        ComponentName(context, BluetoothConnectReceiver::class.java),
        ComponentName(context, BluetoothDisconnectReceiver::class.java)
    ).forEach { component ->
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}

/**
 * Listens for Bluetooth ACL disconnects and, if the disconnected device is linked to a car
 * profile (via Manage Cars), attempts to auto-detect and save that car's parked spot —
 * removing the need to open the app and press "I'm Parked" at all for the common case.
 *
 * ACTION_ACL_DISCONNECTED is one of the few implicit broadcasts exempted from Android 8+'s
 * background-broadcast restrictions (confirmed against Android's own documentation — see
 * developer.android.com/guide/components/broadcast-exceptions), so a manifest-registered
 * receiver for it keeps working even when the app isn't running. This is why it MUST be
 * declared statically in AndroidManifest.xml rather than registered dynamically from an
 * Activity — see the note at the bottom of this file for the exact manifest entry needed.
 *
 * If this isn't firing in practice despite that exemption, the two most likely causes are (a)
 * the manifest entry below wasn't actually applied yet, or (b) an OEM's own battery
 * management layer on top of stock Android (Samsung's "Sleeping apps" list is a known
 * example, separate from the standard Android battery-optimization exemption) is interfering
 * — see the "Background reliability" section in Settings for both.
 *
 * Per the "infer + confirm, not infer silently" principle from the original design doc (GPS
 * accuracy alone isn't reliable enough to guess a spot with certainty): a CONFIDENT match
 * auto-saves immediately, since removing the button-press requirement is the entire point of
 * this feature, but an AMBIGUOUS or NO_MATCH result never guesses — it opens the app directly
 * into the same manual picker the button-press flow already uses.
 */
class BluetoothDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "onReceive: action=${intent.action}")
        if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return

        val device: BluetoothDevice? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        } catch (e: SecurityException) {
            Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "onReceive: SecurityException reading device extra", e)
            null
        }
        val address = device?.address
        if (address == null) {
            Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "onReceive: no device address in intent, aborting")
            return
        }
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "onReceive: disconnected device address=$address")

        // onReceive must return quickly, but the work here (Room queries, a save, a
        // notification) needs more time than that — goAsync() extends the receiver's
        // lifetime for background work, as long as pendingResult.finish() is always called.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleDisconnect(context, address)
            } catch (e: Exception) {
                Log.e(BLUETOOTH_AUTO_DETECT_LOG_TAG, "handleDisconnect threw", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDisconnect(context: Context, deviceAddress: String) {
        val db = AppDatabase.getInstance(context)
        val car = db.carDao().getAll().firstOrNull {
            it.bluetoothDeviceAddress?.equals(deviceAddress, ignoreCase = true) == true
        }
        if (car == null) {
            Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "handleDisconnect: no car linked to $deviceAddress")
            return
        }
        simulateBluetoothDisconnect(context, car)
    }
}

/**
 * Every side effect a real ACL disconnect applies for [car]: the park decision itself, plus
 * the BluetoothConnectionCenter update, the widget's transient event, and a widget refresh.
 * Pulled out so the real receiver above and the "Test Bluetooth Hooks" buttons in Settings run
 * the exact same path — previously the test buttons called performAutoDetectPark directly and
 * skipped everything after it, so they could confirm the park decision and notification fired
 * but couldn't catch a bug in the map's pill/banner/toast or the widget's transient text, since
 * none of that code ever ran.
 */
suspend fun simulateBluetoothDisconnect(context: Context, car: Car) {
    // Captured BEFORE onCarDeviceDisconnected below, which is what actually clears/recomputes
    // this state \u2014 need to know whether this car was the one being displayed as "driving"
    // at the moment its device dropped, not after.
    val wasActiveDriver = (BluetoothConnectionCenter.linkState.value as? CarLinkState.Resolved)?.carId == car.id

    val result = performAutoDetectPark(context, car)

    // Display-only — does not affect the park decision above, which already ran
    // unconditionally regardless of how many other devices are connected.
    BluetoothConnectionCenter.onCarDeviceDisconnected(car.id)

    // Driving mode's heading-lock/zoom/rotation only makes sense tied to an actual drive —
    // once the linked car's Bluetooth drops, that's over, and leaving it on until someone
    // remembers to tap "Stop" is the more likely failure mode than turning it off unwanted.
    // Only acts if THIS car was the one actually being displayed as driving (a disconnect
    // from some other, non-active linked car shouldn't touch it), and only if driving mode is
    // even on right now \u2014 DrivingModeState.setActive(false) is harmless either way, but
    // this avoids it firing as a no-op event for every ordinary disconnect.
    if (wasActiveDriver && DrivingModeState.isActive.value &&
        SettingsRepository(context).autoStopDrivingModeOnDisconnect.first()
    ) {
        DrivingModeState.setActive(false)
    }

    when (result) {
        is AutoParkResult.Subscribed ->
            SettingsRepository(context).recordTransientConnectionEvent("PARKED", car.id)
        is AutoParkResult.NeedsConfirmation ->
            SettingsRepository(context).recordTransientConnectionEvent("NEEDS_CONFIRMATION", result.carId, result.point)
        AutoParkResult.NoLocationFix -> {
            // Nothing was saved and no notification was even shown — the widget
            // shouldn't claim anything happened. Deliberately not recording any
            // transient event here; this is exactly the case that used to produce a
            // false "Just parked" with no pin and no alarm anywhere.
        }
    }
    enqueueWidgetRefresh(context)
}

/**
 * The actual "car disconnected, try to auto-park it" logic, pulled out to a top-level
 * function so it's callable from two places: the real receiver above (given a device
 * address it resolves to a car) and the "Test Bluetooth Hooks" buttons in Settings (given a
 * car directly, to test the app-side logic in isolation from whether the OS actually
 * delivers the broadcast — if the test button works but a real disconnect doesn't, that
 * narrows the problem down to broadcast delivery, not this logic).
 */
/** What performAutoDetectPark actually did — the receiver uses this to decide what (if
 *  anything) the widget should claim happened, instead of assuming "disconnect = parked". */
sealed class AutoParkResult {
    data class Subscribed(val corridor: String) : AutoParkResult() // CONFIDENT, or AMBIGUOUS with a best-guess save
    // Carries carId/point so the widget's transient-event tap can deep-link straight into the
    // same confirm flow the notification for this event already opens, instead of just
    // launching the app generically.
    data class NeedsConfirmation(val carId: Long, val point: LatLng) : AutoParkResult() // NO_MATCH — notified, nothing saved
    object NoLocationFix : AutoParkResult() // couldn't even attempt a match
}

/**
 * getLastKnownLocation() is a passive read of whatever the OS happens to have cached — it
 * does NOT request a new fix. Right after a drive, with the app not in the foreground, there's
 * a real chance nothing else has an active GPS session recently enough, leaving the cache
 * empty or stale — which is exactly the "widget said nothing happened, no pin, no alarm"
 * symptom this was added to fix. This checks the cache first (instant, free) and only falls
 * through to actively requesting a fresh fix if that cache is missing or too old to trust,
 * bounded by a timeout so a BroadcastReceiver's limited goAsync() window can't hang waiting
 * for a fix that never comes (e.g. parked in an underground garage with no GPS signal at all).
 */
private const val LOCATION_FRESHNESS_WINDOW_MILLIS = 2 * 60_000L
private const val FRESH_FIX_TIMEOUT_MILLIS = 8_000L

private suspend fun getFreshOrLastKnownLocation(context: Context): Location? {
    val locationManager = context.getSystemService(LocationManager::class.java)

    val cached = try {
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (e: SecurityException) {
        Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: no location permission", e)
        return null
    }

    if (cached != null && System.currentTimeMillis() - cached.time < LOCATION_FRESHNESS_WINDOW_MILLIS) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: using cached fix (${System.currentTimeMillis() - cached.time}ms old)")
        return cached
    }

    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: cache " +
            (if (cached == null) "empty" else "too stale") + ", requesting a fresh fix (timeout ${FRESH_FIX_TIMEOUT_MILLIS}ms)")
    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: GPS_PROVIDER enabled=${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)}, NETWORK_PROVIDER enabled=${locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")

    val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MILLIS) {
        try {
            requestSingleLocationFix(context, locationManager)
        } catch (e: Exception) {
            // Was SecurityException-only — widened to catch (and actually log) whatever this
            // really is, since the immediate ~6ms-later abort seen in testing rules out a
            // genuine 8s timeout and rules out an uncaught SecurityException specifically
            // (that log line wasn't in the trace either) — something else is failing fast,
            // and this makes sure it's visible instead of silently falling through to null.
            Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: fresh fix request threw ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    return fresh ?: cached.also {
        if (it != null) {
            Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "getFreshOrLastKnownLocation: fresh fix timed out, falling back to stale cache")
        }
    }
}

@Suppress("MissingPermission") // caller (getFreshOrLastKnownLocation) already handles SecurityException
private suspend fun requestSingleLocationFix(context: Context, locationManager: LocationManager): Location? =
    suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            cont.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                cancellationSignal,
                context.mainExecutor
            ) { location ->
                if (cont.isActive) cont.resume(location)
            }
        } else {
            // requestSingleUpdate predates getCurrentLocation (API 30) — needed since minSdk
            // here is 29 (Android 10), one below where getCurrentLocation exists. This isn't
            // just a hypothetical old-device path anymore: the S9 (the actual device that set
            // this floor) tops out at API 29, so it runs this exact branch, not the one above.
            // UNVERIFIED: I can't compile-test this pre-R path from here; if it doesn't build,
            // check LocationListener's exact abstract methods for your compileSdk
            // (onStatusChanged became default/optional in newer API levels but the interface
            // shape has shifted across Android versions historically).
            @Suppress("DEPRECATION")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
                override fun onProviderDisabled(provider: String) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Deprecated in Java, still required pre-API-29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
        }
    }

suspend fun performAutoDetectPark(context: Context, car: Car): AutoParkResult {
    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "performAutoDetectPark: car=${car.name} (id=${car.id})")

    val location = getFreshOrLastKnownLocation(context)
    if (location == null) {
        Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "performAutoDetectPark: no location fix available (cache empty and fresh fix timed out/denied), aborting")
        return AutoParkResult.NoLocationFix
    }

    val point = LatLng(location.latitude, location.longitude)
    val matches = findNearbySegmentMatches(context, point)
    val confidence = classifyMatch(matches)
    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "performAutoDetectPark: ${matches.size} nearby matches, confidence=$confidence")

    return when (confidence) {
        MatchConfidence.CONFIDENT -> {
            val segment = matches.first().segment
            val dropPin = SettingsRepository(context).bluetoothAutoDropPin.first()
            if (dropPin) {
                saveParkedState(context, car.id, segment, point, point.lat, point.lng)
            } else {
                saveParkedState(context, car.id, segment, point)
            }
            BluetoothConnectionCenter.notifyParkedStateChanged()
            showAutoDetectNotification(
                context = context,
                car = car,
                title = "Parked ${car.name} automatically",
                text = "Detected near ${segment.corridor}. Tap to view or correct.",
                centerPoint = point,
                suppressIfMapVisible = true
            )
            Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "performAutoDetectPark: saved to ${segment.corridor}, notification sent")
            AutoParkResult.Subscribed(segment.corridor)
        }
        MatchConfidence.AMBIGUOUS, MatchConfidence.NO_MATCH -> {
            // AMBIGUOUS always has a real candidate within 30m (that's exactly what
            // distinguishes it from NO_MATCH in classifyMatch) — it's just too close to a
            // second candidate to pick with full CONFIDENT certainty. Auto-saving the closer
            // of the two means a reminder subscription genuinely exists right away, instead
            // of silently registering nothing until the user happens to tap the notification
            // and confirm manually. sideConfirmed=false marks it as an unconfirmed guess
            // rather than a real confirmation, in case that distinction gets surfaced in the
            // UI later (currently unused elsewhere, but this is exactly what it was for).
            //
            // NO_MATCH has no candidate within 30m at all — there's nothing reasonable to
            // guess, so it stays notification-only. Returning NeedsConfirmation here (rather
            // than the receiver assuming "disconnect = parked" unconditionally) is what lets
            // the widget show something honest instead of a false "Just parked".
            val result = if (confidence == MatchConfidence.AMBIGUOUS) {
                val segment = matches.first().segment
                saveParkedState(context, car.id, segment, point, sideConfirmed = false)
                BluetoothConnectionCenter.notifyParkedStateChanged()
                AutoParkResult.Subscribed(segment.corridor)
            } else {
                AutoParkResult.NeedsConfirmation(car.id, point)
            }
            showAutoDetectNotification(
                context = context,
                car = car,
                title = "Did ${car.name} just park?",
                text = if (confidence == MatchConfidence.AMBIGUOUS)
                    "Guessed near ${matches.first().segment.corridor} \u2014 tap to confirm or correct."
                else
                    "Couldn't pin down the exact spot \u2014 tap to confirm.",
                autoDetectCarId = car.id,
                autoDetectPoint = point
            )
            Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "performAutoDetectPark: confidence=$confidence, notification sent" +
                    (if (confidence == MatchConfidence.AMBIGUOUS) " (best-guess subscription also saved)" else ""))
            result
        }
    }
}

fun showAutoDetectNotification(
    context: Context,
    car: Car,
    title: String,
    text: String,
    centerPoint: LatLng? = null,
    autoDetectCarId: Long? = null,
    autoDetectPoint: LatLng? = null,
    suppressIfMapVisible: Boolean = false
) {
    // Only the purely-informational CONFIDENT auto-park case opts into this — the
    // AMBIGUOUS/NO_MATCH "Did X just park?" notification is the only way to open the manual
    // confirm/correct picker, so it always posts regardless of what the map is showing;
    // suppressing that one would silently strand an unconfirmed or unsaved spot with no way
    // to fix it short of guessing where the confirm flow lives.
    if (suppressIfMapVisible && MapScreenVisibility.isVisible.value) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "showAutoDetectNotification: map screen visible, skipping (already shown live in-app)")
        return
    }

    val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        centerPoint?.let {
            putExtra("centerLat", it.lat)
            putExtra("centerLng", it.lng)
        }
        if (autoDetectCarId != null && autoDetectPoint != null) {
            putExtra("autoDetectCarId", autoDetectCarId)
            putExtra("autoDetectLat", autoDetectPoint.lat)
            putExtra("autoDetectLng", autoDetectPoint.lng)
        }
    }
    val notificationId = car.id.toInt() + AUTO_DETECT_NOTIFICATION_ID_OFFSET
    val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_NORMAL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title)
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    if (ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "showAutoDetectNotification: posted (id=$notificationId)")
    } else {
        Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "showAutoDetectNotification: POST_NOTIFICATIONS not granted, skipped")
    }
}
