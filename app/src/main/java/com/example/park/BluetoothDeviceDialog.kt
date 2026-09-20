package com.example.park

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Lets the user associate one paired Bluetooth device (typically a car's audio/hands-free
 * system) with a car profile. BluetoothDisconnectReceiver watches for that device
 * disconnecting and, when it does, attempts to auto-detect and save that car's parked spot
 * without any button press.
 */
@Composable
fun BluetoothDeviceDialog(
    car: Car,
    allCars: List<Car> = emptyList(),
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // The device->car mapping must stay one-to-one (BluetoothConnectionCenter's per-car
    // resolution assumes this) — without this check, assigning an already-linked device here
    // silently created two cars pointing at the same address, with no error anywhere.
    var reassignPrompt by remember { mutableStateOf<Pair<String, Car>?>(null) }

    var hasBluetoothPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasBluetoothPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasBluetoothPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    LaunchedEffect(hasBluetoothPermission) {
        if (hasBluetoothPermission) {
            devices = try {
                val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
                bluetoothManager?.adapter?.bondedDevices?.toList() ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a Bluetooth device to ${car.name}") },
        text = {
            Column {
                Text(
                    "When this device disconnects \u2014 e.g. your car's audio system when " +
                            "you turn off the engine \u2014 ${car.name} will be auto-detected as parked.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                when {
                    !hasBluetoothPermission -> Text("Bluetooth permission is needed to list paired devices.")
                    devices.isEmpty() -> Text("No paired devices found. Pair your car's Bluetooth in Android Settings first.")
                    else -> devices.forEach { device ->
                        val name = try { device.name ?: device.address } catch (e: SecurityException) { device.address }
                        val isLinked = car.bluetoothDeviceAddress?.equals(device.address, ignoreCase = true) == true
                        val linkedElsewhere = allCars.firstOrNull {
                            it.id != car.id && it.bluetoothDeviceAddress?.equals(device.address, ignoreCase = true) == true
                        }
                        TextButton(onClick = {
                            if (linkedElsewhere != null) {
                                reassignPrompt = device.address to linkedElsewhere
                            } else {
                                onSave(device.address)
                            }
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isLinked) {
                                    Icon(Icons.Filled.Check, contentDescription = "Linked", modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(name + (linkedElsewhere?.let { " (linked to ${it.name})" } ?: ""))
                            }
                        }
                    }
                }

                if (car.bluetoothDeviceAddress != null) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onSave(null) }) { Text("Unlink") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    reassignPrompt?.let { (address, otherCar) ->
        AlertDialog(
            onDismissRequest = { reassignPrompt = null },
            title = { Text("Already linked to ${otherCar.name}") },
            text = {
                Text(
                    "This device is currently linked to ${otherCar.name}. Linking it to " +
                            "${car.name} instead will unlink it from ${otherCar.name} \u2014 a " +
                            "device can only be linked to one car at a time."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    reassignPrompt = null
                    onSave(address) // caller is responsible for clearing otherCar's address — see call site
                }) { Text("Reassign") }
            },
            dismissButton = {
                TextButton(onClick = { reassignPrompt = null }) { Text("Cancel") }
            }
        )
    }
}