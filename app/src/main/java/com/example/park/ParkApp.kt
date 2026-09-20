package com.example.park

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Seeds BluetoothConnectionCenter from whatever's already ACL-connected at cold start —
 * covers the "app launched while already mid-drive" gap noted in
 * BluetoothConnectionCenter.resyncFromConnectedDevices's doc comment. Without this, that case
 * silently waited for the next connect/disconnect broadcast before the map's pill/banner/ring
 * caught up to reality, even though the car was connected the whole time.
 */
private suspend fun resyncBluetoothConnectionState(context: Context) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) return

    val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
    val bondedDevices = try {
        bluetoothManager.adapter?.bondedDevices ?: return
    } catch (e: SecurityException) {
        return
    }

    val cars = AppDatabase.getInstance(context).carDao().getAll()
    val connected = bondedDevices.mapNotNull { device ->
        val car = cars.firstOrNull {
            it.bluetoothDeviceAddress?.equals(device.address, ignoreCase = true) == true
        } ?: return@mapNotNull null
        if (!isDeviceCurrentlyConnected(device)) return@mapNotNull null
        val name = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
        device.address to (car.id to name)
    }.toMap()

    if (connected.isNotEmpty()) {
        BluetoothConnectionCenter.resyncFromConnectedDevices(connected)
    }
}

/**
 * There's no stable *public* API across Android versions for "is this bonded classic
 * Bluetooth device ACL-connected right now" — BluetoothDevice.isConnected() has existed on the
 * framework side for years but was only unhidden as public API recently, inconsistently
 * across compileSdk/OS combinations (two straight guesses at calling it directly both failed
 * to even compile here, which is exactly that inconsistency showing up). This reflects on the
 * hidden method instead — the same technique most third-party Bluetooth apps have used for
 * this for years — but it's still an unofficial, unsupported surface that could throw, return
 * the wrong thing, or vanish on a given OEM/OS build.
 *
 * HIGHER-RISK UNVERIFIED than the rest of this file's UNVERIFIED items — actually connect and
 * disconnect a linked device, kill the app, and relaunch it on the S25 before trusting this.
 * If it's unreliable in practice, the safe fallback is deleting this function's body down to
 * `return false` — that just brings back the pre-existing gap (no cold-start resync), not a
 * regression, since nothing else in the app depends on this succeeding.
 */
private fun isDeviceCurrentlyConnected(device: android.bluetooth.BluetoothDevice): Boolean {
    return try {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}

/**
 * (Re)schedules the periodic DataSF refresh.
 *
 * @param policy KEEP (default) leaves an already-scheduled period alone — used at app
 *   startup so a normal cold start doesn't reset the timer. Settings passes REPLACE when
 *   the user changes the interval, so the new value takes effect immediately rather than
 *   waiting for the old period to elapse first.
 * @param wifiOnly When true, requires an unmetered connection (typically Wi-Fi) rather than
 *   any connection — for people who care more about cellular data usage than about getting
 *   the freshest possible schedule on the go.
 */
/**
 * Exported so MapScreen can observe this exact work's live WorkInfo (via
 * WorkManager.getWorkInfosForUniqueWorkFlow) to know when the very first sync is actually
 * running, rather than only inferring it from the segment count — a count-only signal can't
 * tell "still syncing" apart from "synced and genuinely found nothing," and updates in
 * page-sized jumps now that StreetSegmentRepository inserts incrementally, not just once at
 * the end.
 */
const val SWEEPING_REFRESH_WORK_NAME = "sweeping_data_refresh"

fun scheduleSweepingRefresh(
    context: Context,
    intervalHours: Long,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    wifiOnly: Boolean = false
) {
    val request = PeriodicWorkRequestBuilder<SweepingDataRefreshWorker>(
        intervalHours.coerceAtLeast(1), TimeUnit.HOURS
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            // Nothing depends on this running right when the phone is critically low —
            // StreetDataSyncCenter's own proactive re-trigger on the next app launch already
            // catches up quickly once it's charged again, so there's no reason to let a
            // routine, non-urgent 24h+ background sync compete for power in that state.
            .setRequiresBatteryNotLow(true)
            .build()
    ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SWEEPING_REFRESH_WORK_NAME,
        policy,
        request
    )
}

class ParkApp : Application(), DefaultLifecycleObserver {
    // Application-scoped, not screen-scoped — this specifically needs to survive past the
    // moment a rememberCoroutineScope()-backed coroutine would get cancelled (a screen being
    // torn down), since that's exactly the gap this is meant to cover. Lives as long as the
    // process does.
    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super<Application>.onCreate()
        // Installed before anything else in this method — a crash during the app's own
        // earliest init (Room, WorkManager scheduling, etc.) is exactly the kind of startup
        // crash this exists to catch, so it needs to be active before any of that runs.
        installCrashHandler(this)

        org.osmdroid.config.Configuration.getInstance().userAgentValue = packageName

        appScope.launch {
            // Patches the device's security provider with an up-to-date set of trusted CAs
            // via Google Play Services, without needing an OS update — the actual fix for
            // "Trust anchor for certification path not found," which is what an aging,
            // no-longer-updated device (confirmed: a Galaxy S9) throws once its built-in CA
            // trust store falls behind whichever certificate authority a server's cert now
            // chains to. First in this coroutine, before the DataSF network path (or
            // anything else that makes an HTTPS call) gets a chance to run and hit the same
            // failure. Best-effort: if Play Services is missing, outdated, or this fails for
            // any other reason, network calls simply fall back to the device's own
            // (potentially stale) trust store exactly as before this existed — never worth
            // crashing the app over.
            try {
                com.google.android.gms.security.ProviderInstaller.installIfNeeded(this@ParkApp)
            } catch (e: Exception) {
                android.util.Log.w(
                    "ParkApp",
                    "ProviderInstaller failed \u2014 falling back to the device's own trust store",
                    e
                )
            }

            val db = AppDatabase.getInstance(this@ParkApp)
            if (db.carDao().count() == 0) {
                db.carDao().insert(Car(name = "My Car", isDefault = true))
            }

            val settingsRepo = SettingsRepository(this@ParkApp)
            val intervalHours = settingsRepo.refreshIntervalHours.first()
            val wifiOnly = settingsRepo.wifiOnlyRefresh.first()
            scheduleSweepingRefresh(this@ParkApp, intervalHours.toLong(), wifiOnly = wifiOnly) // KEEP by default

            // Mirrors whatever's currently saved in Settings (if anything) into ApiKeys'
            // in-memory override before the map has any chance to request a tile — a build
            // shared with someone else, with their own key already saved from a previous
            // session, should never briefly fall back to the developer's baked-in key on
            // cold start while this hasn't run yet.
            ApiKeys.setStadiaMapsKeyOverride(settingsRepo.stadiaApiKeyOverride.first())
            ApiKeys.setDataSfAppTokenOverride(settingsRepo.dataSfAppTokenOverride.first())

            val maxCacheBytes = settingsRepo.tileCacheMaxMb.first() * 1024L * 1024L
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheMaxBytes = maxCacheBytes
            org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheTrimBytes = (maxCacheBytes * 0.9).toLong()
        }

        NotificationHelper.createChannel(this)

        StreetDataSyncCenter.initialize(this, appScope)

        appScope.launch {
            // Component enabled-state is applied fresh on every cold start (not just when the
            // Settings toggle changes) so it's correct even the very first time — the default
            // (true) needs to actually match COMPONENT_ENABLED_STATE_ENABLED, which the
            // manifest already implies but this makes explicit and self-correcting regardless.
            val autoDetectEnabled = SettingsRepository(this@ParkApp).bluetoothAutoDetectEnabled.first()
            applyBluetoothAutoDetectComponentState(this@ParkApp, autoDetectEnabled)
            if (autoDetectEnabled) {
                resyncBluetoothConnectionState(this@ParkApp)
            }
        }

        // CONFIRMED via Logcat: updateAll() reporting success doesn't mean provideGlance
        // actually ran — that work is scheduled asynchronously on Glance's own internal
        // session, and gets silently dropped if the app leaves the foreground before it gets
        // a turn to run. WorkManager's setExpedited() was the first attempt at a backstop, but
        // it has its own limited per-app quota and falls back to ordinary (non-guaranteed-
        // immediate) scheduling once exhausted — reintroducing the same problem one layer up.
        //
        // ProcessLifecycleOwner.onStop() fires exactly once the WHOLE app (every Activity, not
        // just one screen) has left the foreground — this is the real "exiting the app" moment
        // the user is describing, and it fires while the process is still guaranteed alive.
        // Calling updateParkWidget() directly here, in appScope (not a screen's scope, and not
        // routed through WorkManager's quota), gives it the best realistic chance of actually
        // completing before the process might later be killed as the user moves further away.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // Re-arms reminder alarms every time the app comes to the foreground — the backstop for
    // anything that wiped them without a boot (e.g. Force Stop) and for a missed boot broadcast.
    // Deliberately NOT in onCreate(): that runs on every cold process start, including the one
    // an alarm itself triggers, where re-arming would run in the middle of delivering a reminder.
    // ProcessLifecycleOwner's onStart fires once when the first Activity becomes visible, not
    // per-Activity, so this isn't repeated on every screen change. Re-arming is idempotent and
    // never cancels a notification, so running it on every foreground is safe.
    override fun onStart(owner: LifecycleOwner) {
        appScope.launch {
            try {
                rearmAllActiveReminders(this@ParkApp)
            } catch (e: Exception) {
                android.util.Log.w("ParkApp", "Foreground re-arm of reminders failed", e)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        android.util.Log.d("ParkWidget", "ParkApp: onStop fired (app backgrounded)")
        appScope.launch {
            updateParkWidget(this@ParkApp)
        }
    }
}