package com.example.park

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a link change does to the "who's driving" state (BluetoothLinkSync.kt). The OS side (which devices
 * are connected) needs a phone; this covers what the app does with the answer.
 */
class BluetoothLinkSyncTest {
    @After
    fun reset() = runBlocking { BluetoothConnectionCenter.resyncFromConnectedDevices(emptyMap()) }

    @Test
    fun unlinkingTheConnectedCar_clearsDriving() = runBlocking {
        BluetoothConnectionCenter.onCarDeviceConnected(1, "Civic audio") // connected while linked
        assertEquals(CarLinkState.Resolved(1), BluetoothConnectionCenter.linkState.value)

        BluetoothConnectionCenter.resyncFromConnectedDevices(emptyMap()) // after unlink: no linked device connected

        assertEquals(CarLinkState.None, BluetoothConnectionCenter.linkState.value)
        assertEquals(emptySet<Long>(), BluetoothConnectionCenter.connectedCarIdsNow())
    }

    @Test
    fun movingTheLinkToAnotherCar_showsThatCarInstead() = runBlocking {
        BluetoothConnectionCenter.onCarDeviceConnected(1, "Head unit")

        BluetoothConnectionCenter.resyncFromConnectedDevices(mapOf("AA:BB" to (2L to "Head unit")))

        assertEquals(CarLinkState.Resolved(2), BluetoothConnectionCenter.linkState.value)
    }

    @Test
    fun linkingADeviceThatIsAlreadyConnected_isTheOnlyNewlyConnectedCar() {
        val before = setOf(3L) // another car already connected
        val connected = mapOf("AA:BB" to (1L to "Civic audio"), "CC:DD" to (3L to "Van"))
        assertEquals(listOf(1L to "Civic audio"), newlyConnectedCars(before, connected))
        assertEquals(emptyList<Pair<Long, String>>(), newlyConnectedCars(setOf(1L, 3L), connected))
    }
}
