package com.example.park

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three ways a saved location's point can be set, chosen in AddSavedLocationDialog. */
sealed class SaveLocationMethod {
    data class CurrentLocation(val name: String) : SaveLocationMethod()
    data class Address(val name: String, val address: String) : SaveLocationMethod()
    data class PickFromMap(val name: String) : SaveLocationMethod()
}

/**
 * Geocodes a free-text address to coordinates using Android's built-in Geocoder — no API
 * key needed, consistent with keeping this app's dependencies minimal. Uses the deprecated
 * synchronous overload (still functional on all API levels, unlike the newer callback-based
 * one which only exists from API 33) wrapped in Dispatchers.IO so it doesn't block the
 * caller. Reliability depends on the device having Google's geocoding backend available,
 * which is virtually always true on phones with Play Services, and may be absent on
 * AOSP-only builds.
 */
suspend fun geocodeAddress(context: Context, address: String): LatLng? = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        val results = android.location.Geocoder(context).getFromLocationName(address, 1)
        results?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: Exception) {
        // Geocoder can throw IOException (no network/backend available) or
        // IllegalArgumentException (malformed input) — both mean "couldn't geocode this."
        null
    }
}

private suspend fun getCurrentLocationOrNull(context: Context): LatLng? {
    val locationManager = context.getSystemService(LocationManager::class.java)
    return try {
        val fix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        fix?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: SecurityException) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedLocationsScreen(onBack: () -> Unit, onPickFromMap: (name: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var locations by remember { mutableStateOf<List<SavedLocation>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var customizingLocation by remember { mutableStateOf<SavedLocation?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    fun reload() {
        scope.launch { locations = AppDatabase.getInstance(context).savedLocationDao().getAll() }
    }
    LaunchedEffect(Unit) { reload() }

    // Same snackbar-with-undo pattern as deleting a car \u2014 a saved location is just one small
    // row with no cascading references, so restoring it on Undo is even simpler than a car.
    fun handleDelete(location: SavedLocation) {
        scope.launch {
            AppDatabase.getInstance(context).savedLocationDao().delete(location)
            // If this was safe-tagged, any car parked unmanaged because of it needs
            // re-evaluating now that the location vouching for it is gone — see
            // SavedLocationRecompute.kt's doc comment.
            if (location.isSafeFromSweeping == true) reevaluateCarsFormerlySafeAt(context, location.id)
            reload()
            val result = snackbarHostState.showSnackbar(
                message = "Deleted ${location.name}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                // Room's autoincrement hands this a NEW id — it's not literally the same row
                // as before the delete, so treat it as a fresh "became safe" location rather
                // than assuming the recompute above never happened.
                val restoredId = AppDatabase.getInstance(context).savedLocationDao().insert(location.copy(id = 0))
                if (location.isSafeFromSweeping == true) {
                    convertCarsNowSafeAt(context, location.copy(id = restoredId))
                }
                reload()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (!hasLocationPermission) {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                showAddDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add saved location")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Quick-pick spots (like Home or Work) for the parking flow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (locations.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No saved locations yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap + to save your first spot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                return@Scaffold
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(locations) { location ->
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LocationAvatar(location, size = 28.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(location.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "%.5f, %.5f".format(location.lat, location.lng),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = { customizingLocation = location }) { Text("Edit") }
                            TextButton(
                                onClick = { handleDelete(location) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Delete") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddSavedLocationDialog(
            onConfirm = { method ->
                showAddDialog = false
                when (method) {
                    is SaveLocationMethod.CurrentLocation -> {
                        scope.launch {
                            val point = getCurrentLocationOrNull(context)
                            if (point != null) {
                                AppDatabase.getInstance(context).savedLocationDao().insert(
                                    SavedLocation(name = method.name, lat = point.lat, lng = point.lng)
                                )
                                statusMessage = null
                                reload()
                            } else {
                                statusMessage = "No GPS fix available \u2014 try again once you have a location lock."
                            }
                        }
                    }
                    is SaveLocationMethod.Address -> {
                        scope.launch {
                            val point = geocodeAddress(context, method.address)
                            if (point != null) {
                                AppDatabase.getInstance(context).savedLocationDao().insert(
                                    SavedLocation(name = method.name, lat = point.lat, lng = point.lng)
                                )
                                statusMessage = null
                                reload()
                            } else {
                                statusMessage = "Couldn't find that address \u2014 try including city and state."
                            }
                        }
                    }
                    is SaveLocationMethod.PickFromMap -> {
                        onPickFromMap(method.name)
                    }
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }

    customizingLocation?.let { location ->
        LocationStyleDialog(
            location = location,
            onSave = { name, colorHex, iconEmoji, photoPath, isSafeFromSweeping, isOffStreet ->
                scope.launch {
                    val wasSafe = location.isSafeFromSweeping == true
                    val wasOffStreet = location.isOffStreet == true
                    val updated = location.copy(
                        name = name, colorHex = colorHex, iconEmoji = iconEmoji, photoPath = photoPath,
                        isSafeFromSweeping = isSafeFromSweeping, isOffStreet = isOffStreet
                    )
                    AppDatabase.getInstance(context).savedLocationDao().update(updated)
                    // The location's lat/lng never change in this dialog, so a recompute is
                    // only needed when the safe flag itself flipped — see
                    // SavedLocationRecompute.kt's doc comment for what each direction does.
                    android.util.Log.d("SavedLocationRecompute", "onSave: '${location.name}' wasSafe=$wasSafe -> isSafeFromSweeping=$isSafeFromSweeping")
                    when {
                        wasSafe && !isSafeFromSweeping -> reevaluateCarsFormerlySafeAt(context, location.id)
                        !wasSafe && isSafeFromSweeping -> convertCarsNowSafeAt(context, updated)
                    }
                    // Only a still-safe location keeps cars parked "at" it; the branches above already
                    // re-armed everything when the safe flag itself flipped.
                    if (wasSafe && isSafeFromSweeping && wasOffStreet != isOffStreet) {
                        recheckOffStreetForCarsAt(context, location.id)
                    }
                    customizingLocation = null
                    reload()
                }
            },
            onDismiss = { customizingLocation = null }
        )
    }
}

@Composable
private fun AddSavedLocationDialog(onConfirm: (SaveLocationMethod) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("current") } // "current" | "address" | "map"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save a location") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Home, Work)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("How do you want to set the point?", style = MaterialTheme.typography.labelMedium)

                MethodOption("Use current location", method == "current") { method = "current" }
                MethodOption("Enter an address", method == "address") { method = "address" }
                if (method == "address") {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth().padding(start = 32.dp)
                    )
                }
                MethodOption("Pick from map", method == "map") { method = "map" }
            }
        },
        confirmButton = {
            val enabled = name.isNotBlank() && (method != "address" || address.isNotBlank())
            TextButton(
                enabled = enabled,
                onClick = {
                    when (method) {
                        "current" -> onConfirm(SaveLocationMethod.CurrentLocation(name))
                        "address" -> onConfirm(SaveLocationMethod.Address(name, address))
                        "map" -> onConfirm(SaveLocationMethod.PickFromMap(name))
                    }
                }
            ) { Text(if (method == "map") "Continue" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MethodOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}