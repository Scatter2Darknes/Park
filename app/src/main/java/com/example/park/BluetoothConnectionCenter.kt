package com.example.park

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Tracks which registered cars currently have their linked Bluetooth device connected, and
 * resolves that into a single "who's driving" carId for display (map banner/icon, widget).
 *
 * This is deliberately separate from ParkedState/parked-state DAO: per-car Parked/Unparked is
 * decided unconditionally by BluetoothConnectReceiver/BluetoothDisconnectReceiver as each
 * device connects or disconnects, independent of how many OTHER devices are connected — that
 * logic is safety-relevant (drives notification scheduling) and untouched by anything here.
 * Everything in this file is purely cosmetic: which car's name/icon gets shown as "active"
 * when more than one linked car is connected at once. Getting this wrong means a wrong label
 * for a while, not a missed or wrongly-fired reminder.
 *
 * In-memory only (not Room/DataStore-backed) — this is live hardware state, not something
 * meaningful to persist across process death. On cold start it starts empty; the first
 * connect/disconnect broadcast (or a future explicit resync) repopulates it. See
 * resyncFromSystemBluetoothState() below for filling this in immediately at app start instead
 * of waiting for the next ACL event.
 */
sealed class CarLinkState {
    object None : CarLinkState()
    data class Resolved(val carId: Long) : CarLinkState()
    data class Ambiguous(val connectedCarIds: Set<Long>, val suggestedCarId: Long) : CarLinkState()
}

object BluetoothConnectionCenter {
    // Ground truth: device addresses the OS currently reports as ACL-connected, restricted to
    // ones actually linked to a car (unlinked devices never enter this set — they're outside
    // the app's concern entirely).
    private val _connectedCarIds = MutableStateFlow<Set<Long>>(emptySet())

    // The resolved "who's driving" state MapScreen/widget should display.
    private val _linkState = MutableStateFlow<CarLinkState>(CarLinkState.None)
    val linkState = _linkState.asStateFlow()

    // Device name shown in "Currently connected to: ..." — keyed by carId since a car's
    // linked device is fixed while connected. Cleared when that car's device disconnects.
    private val _connectedDeviceNames = MutableStateFlow<Map<Long, String>>(emptyMap())
    val connectedDeviceNames = _connectedDeviceNames.asStateFlow()

    // Which ambiguity prompt (if any) the user has already dismissed, so it doesn't reappear
    // until the underlying connected set actually changes again.
    private var dismissedForSet: Set<Long>? = null

    // Bumped every time a Bluetooth event actually changes ParkedState (park or unpark) —
    // MapScreen collects this to redraw pins/overlays live, the same "observe a Flow from
    // inside composition" pattern the widget fix already established, rather than requiring
    // the user to leave and re-enter the map for a background BT event to become visible.
    private val _parkedStateVersion = MutableStateFlow(0L)
    val parkedStateVersion = _parkedStateVersion.asStateFlow()

    /** The cars whose linked device is connected right now, as far as this center knows. */
    fun connectedCarIdsNow(): Set<Long> = _connectedCarIds.value

    fun notifyParkedStateChanged() {
        _parkedStateVersion.value = System.currentTimeMillis()
    }

    suspend fun onCarDeviceConnected(carId: Long, deviceName: String) {
        _connectedDeviceNames.value = _connectedDeviceNames.value + (carId to deviceName)
        val updated = _connectedCarIds.value + carId
        _connectedCarIds.value = updated
        recompute(updated, mostRecentEventCarId = carId)
    }

    suspend fun onCarDeviceDisconnected(carId: Long) {
        _connectedDeviceNames.value = _connectedDeviceNames.value - carId
        val updated = _connectedCarIds.value - carId
        _connectedCarIds.value = updated

        // If the car that just disconnected was the one being displayed as active, it can no
        // longer hold that spot — clear it before recomputing so a stale "driving" label
        // never lingers on a car that just left, even for one frame.
        val wasActive = (_linkState.value as? CarLinkState.Resolved)?.carId == carId
        recompute(updated, mostRecentEventCarId = null, forceUnresolve = wasActive)
    }

    /** User tapped "Did you switch?" and confirmed — commits the suggested/chosen car as active. */
    fun confirmActive(carId: Long) {
        _linkState.value = CarLinkState.Resolved(carId)
        dismissedForSet = _connectedCarIds.value
    }

    /** User dismissed the ambiguity banner without switching — stays dismissed until the connected set changes. */
    fun dismissAmbiguity() {
        dismissedForSet = _connectedCarIds.value
    }

    private fun recompute(connectedCarIds: Set<Long>, mostRecentEventCarId: Long?, forceUnresolve: Boolean = false) {
        val current = if (forceUnresolve) CarLinkState.None else _linkState.value

        _linkState.value = when {
            connectedCarIds.isEmpty() -> CarLinkState.None
            connectedCarIds.size == 1 -> CarLinkState.Resolved(connectedCarIds.first())
            current is CarLinkState.Resolved && current.carId in connectedCarIds ->
                // Already-confirmed active car is still connected — a second device joining
                // doesn't silently steal the display; the ambiguity banner still surfaces
                // (see MapScreen) via the state below, but only as an offer, never an auto-switch.
                CarLinkState.Ambiguous(
                    connectedCarIds,
                    suggestedCarId = mostRecentEventCarId ?: current.carId
                )
            else -> CarLinkState.Ambiguous(
                connectedCarIds,
                suggestedCarId = mostRecentEventCarId ?: connectedCarIds.first()
            )
        }

        // A genuinely new connected set invalidates any earlier dismissal.
        if (dismissedForSet != connectedCarIds) {
            // leave dismissedForSet as-is; MapScreen compares against the live set itself to
            // decide whether to show the banner (see shouldShowAmbiguityBanner below).
        }
    }

    fun shouldShowAmbiguityBanner(state: CarLinkState): Boolean {
        if (state !is CarLinkState.Ambiguous) return false
        return dismissedForSet != state.connectedCarIds
    }

    /**
     * Call once at app start (ParkApp.onCreate) to seed connected state from whatever's
     * already connected, rather than waiting for the next ACL event — covers the
     * "already-multiple-connected on cold start" case. Requires BLUETOOTH_CONNECT on API 31+.
     * Not wired up automatically here since it needs a permission check at the call site;
     * see ParkApp.kt for where to add the call.
     */
    suspend fun resyncFromConnectedDevices(connectedAddressToCarId: Map<String, Pair<Long, String>>) {
        _connectedDeviceNames.value = connectedAddressToCarId.values.associate { (carId, name) -> carId to name }
        val carIds = connectedAddressToCarId.values.map { it.first }.toSet()
        _connectedCarIds.value = carIds
        recompute(carIds, mostRecentEventCarId = null)
    }
}