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
        // Settings' "Test urgent"/"Test RPP urgent" buttons post a real notification under
        // NotificationIds.TEST_CAR_ID, which deliberately isn't a real Car row (so a test tap
        // can't touch real parked-state data) — tapping "I moved my car" on one of those can
        // only ever land here. Test the actual behavior on a real parked car's reminder instead.
        val note = if (carId == NotificationIds.TEST_CAR_ID) " (this is the Settings test-notification placeholder car, not a real one — test with a real parked car instead)" else ""
        Log.w("Park", "DismissReminderReceiver: no car with id=$carId, aborting$note")
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

    // NOTE ON FOREGROUNDING: none of the three branches below ever calls context.startActivity()
    // directly. A BroadcastReceiver — even one still doing work via goAsync() on a background
    // thread — doesn't reliably bring the app to the foreground on modern Android:
    // background-activity-launch restrictions can silently drop the call, so nothing visibly
    // happens even though the Activity's Intent extras (and thus its state) may still land
    // once the app is next opened some other way. This bit us: CLEAR_AND_OPEN_MAP and
    // FORCE_PARKING_DIALOG both used to call startActivity directly and neither reliably
    // surfaced anything. Every OTHER background-triggered UI hand-off in this app (Bluetooth
    // auto-detect's showAutoDetectNotification, showUnparkedNotification) instead posts a
    // notification and waits for an actual tap, which the platform always allows to start an
    // Activity — so these branches now follow the same pattern instead of a new one.
    val timeoutMillis = SettingsRepository(context).informationalNotificationTimeoutMillis()
    when (SettingsRepository(context).movedCarAction.first()) {
        MovedCarAction.CLEAR_AND_OPEN_MAP -> {
            Log.d("Park", "DismissReminderReceiver: CLEAR_AND_OPEN_MAP for car $carId")
            showUnparkedNotification(context, car, "Cleared its parked spot and reminders.", timeoutMillis)
        }

        MovedCarAction.SILENT_AUTO_REPARK -> {
            Log.d("Park", "DismissReminderReceiver: SILENT_AUTO_REPARK for car $carId")
            // postNoMatchNotification=false: this action's own documented NO_MATCH fallback
            // ("falls back to FORCE_PARKING_DIALOG") needs its own wording below, not the
            // generic Bluetooth "Did X just park?" text performAutoDetectPark would otherwise
            // post itself.
            when (val result = performAutoDetectPark(context, car, postNoMatchNotification = false)) {
                is AutoParkResult.Subscribed -> {
                    // Saved + notified inside performAutoDetectPark, but that function (unlike
                    // simulateBluetoothDisconnect, its other caller) doesn't refresh the widget
                    // itself — the earlier refresh above only reflected the just-cleared state.
                    enqueueWidgetRefresh(context)
                }
                is AutoParkResult.NeedsConfirmation -> showAutoDetectNotification(
                    context = context,
                    car = car,
                    title = "Confirm ${car.name}'s new spot",
                    text = "Couldn't detect it automatically — tap to confirm or correct.",
                    autoDetectCarId = result.carId,
                    autoDetectPoint = result.point
                    // No timeout: same reasoning as the Bluetooth NO_MATCH case — this is the
                    // only way into the manual confirm flow for a spot that wasn't saved at all.
                )
                AutoParkResult.NoLocationFix -> {
                    // No GPS point at all to pre-fill the confirm dialog with (worse than
                    // NO_MATCH, which at least has a point) — same notice as CLEAR_AND_OPEN_MAP.
                    Log.w("Park", "DismissReminderReceiver: SILENT_AUTO_REPARK got no location fix for car $carId")
                    showUnparkedNotification(context, car, "Couldn't detect your new spot — tap to park manually.", timeoutMillis)
                }
            }
        }

        MovedCarAction.FORCE_PARKING_DIALOG -> {
            Log.d("Park", "DismissReminderReceiver: FORCE_PARKING_DIALOG for car $carId")
            val point = getFreshOrLastKnownLocation(context)?.let { LatLng(it.latitude, it.longitude) }
            if (point != null) {
                showAutoDetectNotification(
                    context = context,
                    car = car,
                    title = "Confirm ${car.name}'s new spot",
                    text = "Tap to confirm where you parked.",
                    autoDetectCarId = carId,
                    autoDetectPoint = point
                )
            } else {
                Log.w("Park", "DismissReminderReceiver: FORCE_PARKING_DIALOG got no location fix for car $carId")
                showUnparkedNotification(context, car, "Couldn't get a location fix — tap to park manually.", timeoutMillis)
            }
        }
    }
}
