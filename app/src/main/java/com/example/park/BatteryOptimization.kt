package com.example.park

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Whether this app is currently exempt from Android's battery optimization (Doze/App
 * Standby). Being exempt is what keeps manifest-registered receivers (BluetoothConnectReceiver,
 * BluetoothDisconnectReceiver) and exact alarms reliable even when the app isn't running —
 * some OEM battery managers (Samsung's included) are aggressive enough to interfere with
 * these otherwise.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/**
 * Opens the system dialog to request exemption directly, rather than sending the user to dig
 * through Android's general battery settings themselves. Requires the
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission in the manifest (a normal, non-runtime
 * permission — just needs declaring, not requesting at runtime).
 */
fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

/**
 * Opens this app's System Settings page. Android has no API for an app to programmatically
 * RE-ENABLE battery optimization on itself — ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * only works in the "become exempt" direction. This is the standard fallback: it gets the
 * user to the app's info page, from which "Battery" lets them manually switch back to
 * "Optimized"/"Restricted" (exact wording varies by Android version and OEM).
 */
fun openAppSettingsForBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

/**
 * Whether this app currently holds ACCESS_BACKGROUND_LOCATION ("Allow all the time"), as
 * opposed to just ACCESS_FINE_LOCATION ("Allow only while using the app"). The Bluetooth
 * disconnect/connect receivers run from a system broadcast while the app isn't in the
 * foreground — Android treats that as background access regardless of how briefly the
 * location read takes, so without this grant, getFreshOrLastKnownLocation silently gets
 * nothing back (no exception in practice, just an empty/failed read) even though the exact
 * same code works fine from inside the app's own UI.
 */
fun hasBackgroundLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Opens this app's System Settings page for the user to switch Location to "Allow all the
 * time" themselves. Same ACTION_APPLICATION_DETAILS_SETTINGS pattern as
 * openAppSettingsForBatteryOptimization above, for the same reason: on Android 11+, the OS
 * does not allow requesting ACCESS_BACKGROUND_LOCATION through a normal in-app permission
 * dialog at all — Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> Permissions -> Location is
 * the only path, regardless of API level, so this doesn't attempt the (Android 10-only, and
 * frequently auto-denied by Play-Store-adjacent policy even there) direct runtime request.
 */
fun openAppSettingsForBackgroundLocation(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

/**
 * Opens this app's notification settings page directly (Settings.ACTION_APP_NOTIFICATION_
 * SETTINGS, API 26+) rather than the generic app-info page — Android has had a dedicated
 * intent for exactly this since Oreo, and minSdk 29 is already above that, so there's no
 * lower-API fallback needed here the way the battery/background-location settings above
 * still have to route through ACTION_APPLICATION_DETAILS_SETTINGS for other reasons.
 */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}
