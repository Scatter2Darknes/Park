package com.example.park

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether this app may schedule exact alarms right now. Below Android 12 (API 31) exact alarms
 * need no permission at all; from 12 up the special "Alarms & reminders" app access is required,
 * and on a fresh install it starts out DENIED from Android 14 (API 34) on.
 *
 * The one place the `SDK_INT < S || canScheduleExactAlarms()` check lives, so the scheduler, the
 * Settings row, the map warning and the permission-change receiver can't disagree about it.
 */
fun canScheduleExactAlarmsCompat(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

/** True only where the permission can actually be missing (API 31+) — below that there's nothing
 *  for the user to grant, so the Settings row and warning banner have nothing to show. */
fun exactAlarmPermissionApplies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Opens the system "Alarms & reminders" page for this app, where the user grants the access.
 * Android can't grant it from inside the app, and there's no in-app prompt for it — this page is
 * the only route. Called only from an explicit button tap (Settings row, map warning), never
 * automatically.
 */
fun openExactAlarmSettings(context: Context) {
    if (!exactAlarmPermissionApplies()) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
