package com.example.park

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Catches any otherwise-fatal uncaught exception, writes its full stack trace to a plain
 * text file in the app's private internal storage (context.filesDir — always available, no
 * storage permission needed on any API level, not visible in a general file manager but
 * readable back by this app), then hands off to whatever the previous handler would have
 * done anyway (the OS's own "app has stopped" dialog and process kill). This never changes
 * or suppresses the actual crash — it's a pure side effect for retrieving the trace later.
 *
 * Added specifically for debugging a startup crash on hardware that isn't convenient to
 * physically connect to a PC for adb logcat (an old Galaxy S9 in this case) — MainActivity
 * checks for this file on the very next launch after a crash and offers to share it through
 * the OS share sheet (email, messaging, notes, anything already on the phone), so getting
 * the actual stack trace off the device needs no cable, no adb, and no Android Studio.
 */
private const val CRASH_LOG_FILENAME = "last_crash.txt"

fun installCrashHandler(context: Context) {
    val appContext = context.applicationContext
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val text = "${Date()}\n\n${Log.getStackTraceString(throwable)}"
            File(appContext.filesDir, CRASH_LOG_FILENAME).writeText(text)
        } catch (e: Exception) {
            // If writing the crash log itself fails, there's nothing more useful to do here —
            // definitely don't let this handler throw, or the OS ends up reporting THIS
            // exception instead of the original crash.
        }
        previousHandler?.uncaughtException(thread, throwable)
    }
}

/** Whatever installCrashHandler last wrote, if anything — null if the app hasn't crashed
 *  since the last time this was cleared (or hasn't crashed at all). */
fun readLastCrashLog(context: Context): String? {
    val file = File(context.applicationContext.filesDir, CRASH_LOG_FILENAME)
    return if (file.exists()) file.readText() else null
}

fun clearLastCrashLog(context: Context) {
    File(context.applicationContext.filesDir, CRASH_LOG_FILENAME).delete()
}