package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms every parked car's reminder alarms after the phone reboots. AlarmManager forgets all
 * alarms when the device restarts, so without this a car parked overnight silently loses its
 * street-sweeping reminder the moment the phone reboots.
 *
 * Listens for BOOT_COMPLETED, not LOCKED_BOOT_COMPLETED: the Room database lives in
 * credential-encrypted storage, which isn't readable until the user has unlocked the phone once
 * after boot, and BOOT_COMPLETED is the broadcast delivered after that.
 *
 * Registered in AndroidManifest.xml (every receiver must be) with exported="false" — the system
 * itself is always allowed to send it this broadcast, other apps aren't.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d("Park", "BootReceiver: BOOT_COMPLETED — re-arming reminders")

        // goAsync() keeps the receiver (and its process) alive past onReceive() so the database
        // work below can finish; finish() in `finally` releases it even if that work throws.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rearmAllActiveReminders(context.applicationContext)
            } catch (e: Exception) {
                Log.w("Park", "BootReceiver: re-arm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
