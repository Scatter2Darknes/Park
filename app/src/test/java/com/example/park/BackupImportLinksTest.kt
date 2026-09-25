package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Test

/** An import must never leave two cars linked to one Bluetooth device (DataBackup.withoutTakenBluetoothLinks). */
class BackupImportLinksTest {
    private fun car(name: String, address: String?) = Car(name = name, bluetoothDeviceAddress = address)

    @Test
    fun aDeviceAlreadyLinkedHere_staysWithTheExistingCar() {
        val existing = listOf(car("Civic", "AA:BB:CC:00:11:22"))
        val result = withoutTakenBluetoothLinks(existing, listOf(car("Civic (backup)", "aa:bb:cc:00:11:22")))
        assertEquals(listOf<String?>(null), result.map { it.bluetoothDeviceAddress })
    }

    @Test
    fun twoImportedCarsWithTheSameDevice_onlyTheFirstKeepsIt() {
        val result = withoutTakenBluetoothLinks(emptyList(), listOf(car("A", "11:11"), car("B", "11:11"), car("C", "22:22")))
        assertEquals(listOf("11:11", null, "22:22"), result.map { it.bluetoothDeviceAddress })
    }

    @Test
    fun unlinkedCarsAndFreeDevicesAreUntouched() {
        val imported = listOf(car("A", null), car("B", "33:33"))
        assertEquals(imported, withoutTakenBluetoothLinks(listOf(car("Civic", "44:44"), car("Van", null)), imported))
    }
}
