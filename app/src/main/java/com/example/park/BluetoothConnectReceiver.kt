package com.example.park

import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


/**
 * Listens for Bluetooth ACL reconnects and, if the reconnected device is linked to a car
 * that's currently parked, clears that parked state — reconnecting to a car's audio system
 * strongly implies you're back in the car and about to drive away, making the saved spot
 * stale. This is the natural complement to BluetoothDisconnectReceiver's park-on-disconnect:
 * if this fires prematurely (e.g. the person just got in to reposition the car slightly, not
 * actually leaving), the disconnect receiver simply re-parks on the next disconnect, so the
 * overall behavior self-corrects either way.
 *
 * Same manifest requirements as BluetoothDisconnectReceiver — ACL_CONNECTED is exempt from
 * Android 8+ background-broadcast restrictions for the same documented reason
 * ACL_DISCONNECTED is (developer.android.com/guide/components/broadcast-exceptions), so this
 * must be a manifest-registered receiver, not a dynamically-registered one, to keep working
 * when the app isn't running. See the comment block at the bottom of this file for the exact
 * manifest entry needed — and BLUETOOTH_AUTO_DETECT_LOG_TAG (defined in
 * BluetoothDisconnectReceiver.kt) for the Logcat tag shared by both receivers.
 */
class BluetoothConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] onReceive: action=${intent.action}")
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device: BluetoothDevice? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        } catch (e: SecurityException) {
            Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] onReceive: SecurityException reading device extra", e)
            null
        }
        val address = device?.address
        if (address == null) {
            Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] onReceive: no device address in intent, aborting")
            return
        }
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] onReceive: connected device address=$address")

        val deviceName = try {
            device.name ?: address
        } catch (e: SecurityException) {
            address
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleReconnect(context, address, deviceName)
            } catch (e: Exception) {
                Log.e(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] handleReconnect threw", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleReconnect(context: Context, deviceAddress: String, deviceName: String) {
        val db = AppDatabase.getInstance(context)
        val car = db.carDao().getAll().firstOrNull {
            it.bluetoothDeviceAddress?.equals(deviceAddress, ignoreCase = true) == true
        }
        if (car == null) {
            Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] handleReconnect: no car linked to $deviceAddress")
            return
        }
        simulateBluetoothReconnect(context, car, deviceName)
    }
}

/**
 * Every side effect a real ACL reconnect applies for [car]: the unpark decision itself, plus
 * the BluetoothConnectionCenter update, the widget's transient event, and a widget refresh.
 * Same rationale as simulateBluetoothDisconnect in BluetoothDisconnectReceiver.kt — lets the
 * "Test Bluetooth Hooks" buttons in Settings exercise the exact same path a real reconnect
 * would, including the map's pill/banner/toast, not just the unpark decision and notification.
 */
suspend fun simulateBluetoothReconnect(context: Context, car: Car, deviceName: String = "Test device") {
    performAutoDetectUnpark(context, car)

    // Display-only — does not affect the park/unpark decision above, which already ran
    // unconditionally. See BluetoothConnectionCenter for why these are kept separate.
    BluetoothConnectionCenter.onCarDeviceConnected(car.id, deviceName)
    SettingsRepository(context).recordTransientConnectionEvent("DRIVING", car.id)
    enqueueWidgetRefresh(context)
}

/**
 * The actual "car reconnected, clear its parked state" logic, pulled out to a top-level
 * function so it's callable from the real receiver above (given a device address) and the
 * "Test Bluetooth Hooks" buttons in Settings (given a car directly) — same rationale as
 * performAutoDetectPark in BluetoothDisconnectReceiver.kt.
 */
suspend fun performAutoDetectUnpark(context: Context, car: Car) {
    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] performAutoDetectUnpark: car=${car.name} (id=${car.id})")

    if (!SettingsRepository(context).bluetoothAutoUnparkOnReconnect.first()) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] performAutoDetectUnpark: setting is off, skipping")
        return
    }

    val db = AppDatabase.getInstance(context)
    if (db.parkedStateDao().getForCar(car.id) == null) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] performAutoDetectUnpark: car wasn't parked, nothing to clear")
        return
    }

    unsubscribeParking(context, car.id) // cancels reminders, clears parked_state, updates the widget
    BluetoothConnectionCenter.notifyParkedStateChanged()
    showUnparkedNotification(
        context, car,
        "Reconnected to its linked Bluetooth — cleared the saved parking spot.",
        SettingsRepository(context).informationalNotificationTimeoutMillis()
    )
    Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] performAutoDetectUnpark: cleared parked state, notification sent")
}

/**
 * Posts a quiet "car X unparked" notice whose tap opens MainActivity, instead of calling
 * startActivity directly — a BroadcastReceiver-originated startActivity doesn't reliably bring
 * the app to the foreground on modern Android (background-activity-launch restrictions apply
 * even to work done via goAsync()), while a genuine notification tap always does. Not private:
 * DismissReminderReceiver's "I moved my car" CLEAR_AND_OPEN_MAP behavior reuses this for the
 * exact same situation (a car's parked state was just cleared in the background), passing its
 * own [text] rather than this file's Bluetooth-specific wording.
 */
fun showUnparkedNotification(context: Context, car: Car, text: String, timeoutAfterMillis: Long?) {
    // Purely informational (no action needed, unlike the AMBIGUOUS/NO_MATCH park case) — if
    // the map screen is visible, its own live toast already said this, so skip the duplicate.
    if (MapScreenVisibility.isVisible.value) {
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] showUnparkedNotification: map screen visible, skipping (already shown live in-app)")
        return
    }

    val launchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val notificationId = NotificationIds.forCar(car.id, NotificationIds.Purpose.BLUETOOTH_AUTO_UNPARK)
    val pendingIntent = PendingIntent.getActivity(
        context, notificationId, launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Quiet "Parking status" channel: this used to set PRIORITY_LOW on the loud reminders
    // channel, but on API 26+ the channel's importance wins over the notification's priority,
    // so it still heads-up popped. It also removes itself after the Settings-chosen timeout.
    val builder = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_STATUS)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("${car.name} unparked")
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
    if (timeoutAfterMillis != null && timeoutAfterMillis > 0) builder.setTimeoutAfter(timeoutAfterMillis)
    val notification = builder.build()

    if (ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        Log.d(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] showUnparkedNotification: posted (id=$notificationId)")
    } else {
        Log.w(BLUETOOTH_AUTO_DETECT_LOG_TAG, "[connect] showUnparkedNotification: POST_NOTIFICATIONS not granted, skipped")
    }
}
