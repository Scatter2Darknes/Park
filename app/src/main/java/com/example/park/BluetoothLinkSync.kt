package com.example.park

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first

/*
 * Keeps BluetoothConnectionCenter (which linked car's device is connected right now) in step with the
 * car <-> device LINKS. The center is normally updated only by connect/disconnect broadcasts, looked up by
 * device address, and once at cold start. A link changed while its device is already connected produced no
 * broadcast, so before this:
 *  - linking a device that was already connected left the car undetected (no "driving", no car in drive mode,
 *    and the auto-unpark that the connect would have run never happened);
 *  - unlinking (or moving the link to another car, or deleting the car) mid-drive left the old car stuck as
 *    "driving" until the app process restarted.
 */

/**
 * The devices linked to a car that the OS reports as connected right now: address -> (carId, device name).
 * Null when it can't be known (no BLUETOOTH_CONNECT permission, no adapter), so callers leave state alone
 * rather than clearing it on a guess.
 */
internal suspend fun connectedLinkedDevices(context: Context): Map<String, Pair<Long, String>>? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return null
    val bondedDevices = try {
        bluetoothManager.adapter?.bondedDevices ?: return null
    } catch (e: SecurityException) {
        return null
    }
    val cars = AppDatabase.getInstance(context).carDao().getAll()
    return bondedDevices.mapNotNull { device ->
        val car = cars.firstOrNull {
            it.bluetoothDeviceAddress?.equals(device.address, ignoreCase = true) == true
        } ?: return@mapNotNull null
        if (!isDeviceCurrentlyConnected(device)) return@mapNotNull null
        val name = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
        device.address to (car.id to name)
    }.toMap()
}

/**
 * There's no stable *public* API across Android versions for "is this bonded classic
 * Bluetooth device ACL-connected right now" — BluetoothDevice.isConnected() has existed on the
 * framework side for years but was only unhidden as public API recently, inconsistently
 * across compileSdk/OS combinations (two straight guesses at calling it directly both failed
 * to even compile here, which is exactly that inconsistency showing up). This reflects on the
 * hidden method instead — the same technique most third-party Bluetooth apps have used for
 * this for years — but it's still an unofficial, unsupported surface that could throw, return
 * the wrong thing, or vanish on a given OEM/OS build. If it's unreliable in practice, the safe
 * fallback is `return false`: that just brings back the gap it closes (no resync), nothing else
 * depends on it.
 */
private fun isDeviceCurrentlyConnected(device: BluetoothDevice): Boolean {
    return try {
        val method = device.javaClass.getMethod("isConnected")
        method.invoke(device) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }
}

/** The cars in [connected] that weren't connected before ([before]): a link just made to a device already on. */
internal fun newlyConnectedCars(before: Set<Long>, connected: Map<String, Pair<Long, String>>): List<Pair<Long, String>> =
    connected.values.filter { (carId, _) -> carId !in before }.distinctBy { it.first }

/**
 * Call after any change to which car a device is linked to (link, unlink, moving a link, deleting or
 * restoring a car). Re-reads the connected devices and replaces the center's connected set with them, so a
 * car that lost its link stops showing as "driving" (display only: nothing is parked), and a car that just
 * got a link to a device that's already connected shows as driving at once.
 *
 * [runMissedConnect]: for a car that just became connected this way, also run the connect handling the
 * device's real connect event missed (auto-unpark if that setting is on, the "driving" toast and widget line),
 * because the user is in that car right now. False for undoing a delete: restoring a car must not unpark it.
 *
 * Does nothing when Bluetooth auto-detect is off (the receivers are disabled then, so the center isn't kept
 * at all) or when the connected devices can't be read.
 */
suspend fun refreshBluetoothLinks(context: Context, runMissedConnect: Boolean = true) {
    if (!SettingsRepository(context).bluetoothAutoDetectEnabled.first()) return
    val connected = connectedLinkedDevices(context) ?: return
    val before = BluetoothConnectionCenter.connectedCarIdsNow()
    BluetoothConnectionCenter.resyncFromConnectedDevices(connected)
    if (!runMissedConnect) return
    val carsById = AppDatabase.getInstance(context).carDao().getAll().associateBy { it.id }
    for ((carId, deviceName) in newlyConnectedCars(before, connected)) {
        carsById[carId]?.let { simulateBluetoothReconnect(context, it, deviceName) }
    }
}
