package com.example.park

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired when the user taps "I moved my car" on an urgent (ongoing) reminder — the only way
 * to clear it, since it's deliberately not swipe-dismissible.
 *
 * Always cancels the notification itself, then dispatches to whichever MovedCarAction the
 * user has chosen in Settings (default CLEAR_AND_OPEN_MAP) — see that enum's doc comment.
 */
class DismissReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Same reasoning as SnoozeReminderReceiver's logging — if this line never appears in
        // Logcat when "I moved my car" is tapped, the receiver isn't being invoked at all
        // (check AndroidManifest.xml's <receiver> entry for this class), as opposed to
        // running and the cancel() call itself having no visible effect for some other reason.
        Log.d("Park", "DismissReminderReceiver.onReceive fired")
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId != -1) {
            NotificationHelper.cancel(context, notificationId)
            Log.d("Park", "DismissReminderReceiver: cancelled notificationId=$notificationId")
        } else {
            Log.w("Park", "DismissReminderReceiver: no notificationId extra — nothing to cancel")
        }

        val carId = intent.getLongExtra("carId", -1L)
        if (carId == -1L) {
            Log.w("Park", "DismissReminderReceiver: no carId extra — can't process \"I moved my car\"")
            return
        }

        // onReceive must return quickly, but clearing ParkedState, cancelling alarms, possibly
        // matching GPS against segments, and posting a follow-up notification all need more
        // time than that — goAsync() extends the receiver's lifetime for background work, as
        // long as pendingResult.finish() is always called (same pattern as BluetoothDisconnectReceiver).
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleMovedCar(context, carId)
            } catch (e: Exception) {
                Log.e("Park", "DismissReminderReceiver: handleMovedCar threw", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private suspend fun handleMovedCar(context: Context, carId: Long) {
    val db = AppDatabase.getInstance(context)
    val car = db.carDao().getAll().firstOrNull { it.id == carId }
    if (car == null) {
        Log.w("Park", "DismissReminderReceiver: no car with id=$carId, aborting")
        return
    }

    // True regardless of which MovedCarAction runs next: the car is no longer at its old spot,
    // so that row and its alarms/notifications are stale the moment this fires. SILENT_AUTO_REPARK
    // and FORCE_PARKING_DIALOG both go on to save a fresh ParkedState of their own (or don't, if
    // nothing pans out) rather than leaving the old one in place either way.
    cancelParkingReminder(context, carId)
    db.parkedStateDao().clearForCar(carId)
    BluetoothConnectionCenter.notifyParkedStateChanged()
    enqueueWidgetRefresh(context)

    when (SettingsRepository(context).movedCarAction.first()) {
        MovedCarAction.CLEAR_AND_OPEN_MAP -> {
            Log.d("Park", "DismissReminderReceiver: CLEAR_AND_OPEN_MAP for car $carId")
            openMapScreen(context)
        }

        MovedCarAction.SILENT_AUTO_REPARK -> {
            Log.d("Park", "DismissReminderReceiver: SILENT_AUTO_REPARK for car $carId")
            // postNoMatchNotification=false: a NO_MATCH here should open the confirm dialog
            // directly (this action's own documented fallback to FORCE_PARKING_DIALOG's
            // behavior) instead of leaving the ordinary "Did X just park?" notification as the
            // only way in — "silent" isn't achievable with no plausible location either way.
            when (val result = performAutoDetectPark(context, car, postNoMatchNotification = false)) {
                is AutoParkResult.Subscribed -> {
                    // Saved + notified inside performAutoDetectPark, but that function (unlike
                    // simulateBluetoothDisconnect, its other caller) doesn't refresh the widget
                    // itself — the earlier refresh above only reflected the just-cleared state.
                    enqueueWidgetRefresh(context)
                }
                is AutoParkResult.NeedsConfirmation -> openForceParkingDialog(context, result.carId, result.point)
                AutoParkResult.NoLocationFix -> {
                    // No GPS point at all to pre-fill the confirm dialog with (worse than
                    // NO_MATCH, which at least has a point) — open the map instead.
                    Log.w("Park", "DismissReminderReceiver: SILENT_AUTO_REPARK got no location fix for car $carId")
                    openMapScreen(context)
                }
            }
        }

        MovedCarAction.FORCE_PARKING_DIALOG -> {
            Log.d("Park", "DismissReminderReceiver: FORCE_PARKING_DIALOG for car $carId")
            val point = getFreshOrLastKnownLocation(context)?.let { LatLng(it.latitude, it.longitude) }
            if (point != null) {
                openForceParkingDialog(context, carId, point)
            } else {
                Log.w("Park", "DismissReminderReceiver: FORCE_PARKING_DIALOG got no location fix for car $carId")
                openMapScreen(context)
            }
        }
    }
}

private fun openMapScreen(context: Context) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    context.startActivity(intent)
}

/** Opens MainActivity straight into the parking confirm flow via the same
 *  autoDetectCarId/Lat/Lng extras the ambiguous/no-match Bluetooth auto-detect notification
 *  already uses (see MainActivity's pendingAutoDetect handling and MapScreen's
 *  proceedToMatching call) — reusing that exact path rather than a new one. */
private fun openForceParkingDialog(context: Context, carId: Long, point: LatLng) {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("autoDetectCarId", carId)
        putExtra("autoDetectLat", point.lat)
        putExtra("autoDetectLng", point.lng)
    }
    context.startActivity(intent)
}
