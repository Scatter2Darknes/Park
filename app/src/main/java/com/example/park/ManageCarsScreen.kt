package com.example.park

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCarsScreen(onBack: () -> Unit, onSeeCarLocation: (LatLng) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var carsWithStatus by remember { mutableStateOf<List<CarWithStatus>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var customizingCar by remember { mutableStateOf<Car?>(null) }
    var linkingBluetoothCar by remember { mutableStateOf<Car?>(null) }
    var permittingCar by remember { mutableStateOf<Car?>(null) }
    var availableRppZones by remember { mutableStateOf<List<String>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun reload() {
        scope.launch { carsWithStatus = loadCarsWithStatus(context) }
    }
    LaunchedEffect(Unit) {
        reload()
        availableRppZones = loadDistinctRppZoneLetters(context)
    }

    // Deletes immediately rather than confirming first — the car's profile, parked state,
    // Bluetooth link, and reminders are all cheap to restore from a snapshot taken right
    // before the delete, so a snackbar-with-undo is a smoother pattern here than a blocking
    // "are you sure?" dialog. (Not every destructive action in this app gets this treatment —
    // e.g. clearing the tile cache in Settings stays a confirm dialog, since there's nothing
    // cheap to snapshot for a real undo there.)
    fun handleDelete(car: Car) {
        scope.launch {
            val snapshot = deleteCarCompletely(context, car)
            reload()
            val result = snackbarHostState.showSnackbar(
                message = "Deleted ${car.name}",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                undoDeleteCar(context, snapshot)
                reload()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Cars") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add car")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (carsWithStatus.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No car profiles yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap + to add your first car.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(carsWithStatus) { item ->
                // Cards (rather than flat rows separated by dividers) give each car its own
                // clearly bounded, tappable-feeling surface — a small, contained change that
                // reads as noticeably more "modern Material3" than a plain list.
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    CarRow(
                        item = item,
                        onSetDefault = { scope.launch { setDefaultCar(context, item.car.id); reload() } },
                        onCustomize = { customizingCar = item.car },
                        onLinkBluetooth = { linkingBluetoothCar = item.car },
                        onPermitZones = { permittingCar = item.car },
                        onDelete = { handleDelete(item.car) },
                        onUnsubscribe = { scope.launch { unsubscribeParking(context, item.car.id); reload() } },
                        onUnsetDefault = { scope.launch { unsetDefaultCar(context, item.car.id); reload() } },
                        onSeeLocation = {
                            item.parkedState?.let { parked ->
                                scope.launch {
                                    val point = resolveCarLocation(context, parked)
                                    onSeeCarLocation(point)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        SimpleTextDialog(
            title = "Add a car",
            initialValue = "",
            confirmLabel = "Add",
            onConfirm = { name ->
                scope.launch {
                    AppDatabase.getInstance(context).carDao().insert(Car(name = name))
                    showAddDialog = false
                    reload()
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }

    customizingCar?.let { car ->
        CarStyleDialog(
            car = car,
            onSave = { name, colorHex, iconEmoji, photoPath, widgetTextColorHex ->
                scope.launch {
                    AppDatabase.getInstance(context).carDao().update(
                        car.copy(
                            name = name,
                            colorHex = colorHex,
                            iconEmoji = iconEmoji,
                            photoPath = photoPath,
                            widgetTextColorHex = widgetTextColorHex
                        )
                    )
                    customizingCar = null
                    reload()
                    enqueueWidgetRefresh(context)
                }
            },
            onDismiss = { customizingCar = null }
        )
    }

    permittingCar?.let { car ->
        PermitZonesDialog(
            car = car,
            availableZones = availableRppZones,
            onSave = { zones ->
                scope.launch {
                    AppDatabase.getInstance(context).carDao().update(
                        car.copy(permitZoneLetters = zones.sorted().joinToString(","))
                    )
                    permittingCar = null
                    reload()
                }
            },
            onDismiss = { permittingCar = null }
        )
    }

    linkingBluetoothCar?.let { car ->
        BluetoothDeviceDialog(
            car = car,
            allCars = carsWithStatus.map { it.car },
            onSave = { address ->
                scope.launch {
                    val db = AppDatabase.getInstance(context)
                    // Enforce the one-device-one-car mapping: if this address was linked to a
                    // different car, clear it there first so the assignment stays injective.
                    if (address != null) {
                        carsWithStatus.map { it.car }
                            .firstOrNull {
                                it.id != car.id &&
                                        it.bluetoothDeviceAddress?.equals(address, ignoreCase = true) == true
                            }
                            ?.let { other -> db.carDao().update(other.copy(bluetoothDeviceAddress = null)) }
                    }
                    db.carDao().update(car.copy(bluetoothDeviceAddress = address))
                    linkingBluetoothCar = null
                    reload()
                }
            },
            onDismiss = { linkingBluetoothCar = null }
        )
    }
}

@Composable
private fun CarRow(
    item: CarWithStatus,
    onSetDefault: () -> Unit,
    onCustomize: () -> Unit,
    onLinkBluetooth: () -> Unit,
    onPermitZones: () -> Unit,
    onDelete: () -> Unit,
    onUnsubscribe: () -> Unit,
    onUnsetDefault: () -> Unit,
    onSeeLocation: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarAvatar(item.car, size = 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.car.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (item.car.isDefault) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Default car",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                TextButton(onClick = onUnsetDefault) { Text("Unset default") }
            } else {
                TextButton(onClick = onSetDefault) { Text("Set default") }
            }
        }

        if (item.car.bluetoothDeviceAddress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Bluetooth linked \u2014 auto-detects parking on disconnect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        PermitZonesSummaryRow(car = item.car, onClick = onPermitZones)

        if (item.parkedState != null) {
            val nextText = item.parkedState.nextSweepAtMillis?.let {
                val dt = java.time.Instant.ofEpochMilli(it).atZone(SF_ZONE)
                "Next cleaning: ${formatSweepDateTime(dt)}"
            } ?: "No cleaning schedule found"
            Text(text = "Parked \u2014 $nextText", style = MaterialTheme.typography.bodySmall)

            // Computed once already in loadCarsWithStatus (see CarWithStatus.rppDeadline) \u2014 no
            // separate lookup needed here. Shows nothing whenever there's simply nothing to
            // warn about (no RPP match here, or this car holds a permit for it), which is the
            // common case for most parked cars.
            item.rppDeadline?.let { deadline ->
                Text(
                    text = "${rppZoneLabel(deadline)} \u2014 " +
                            "move by ${formatSweepDateTime(deadline.moveByDateTime.atZone(SF_ZONE))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row {
                TextButton(onClick = onSeeLocation) { Text("See Location") }
                TextButton(onClick = onUnsubscribe) { Text("Unsubscribe") }
            }
        } else {
            Text(
                "Not currently parked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Icon-only toolbar rather than a row of text buttons — Permit Zones moved up into
        // PermitZonesSummaryRow above (as a tappable visual summary, not another text label
        // here), so this row stays exactly as compact as it was before permit zones existed.
        Row {
            IconButton(onClick = onCustomize) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${item.car.name}")
            }
            IconButton(onClick = onLinkBluetooth) {
                Icon(Icons.Filled.Bluetooth, contentDescription = "Link Bluetooth device")
            }
            // Tinted with the theme's error color — a visual cue that this one is destructive,
            // distinct from the routine actions next to it.
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${item.car.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * A tappable, glanceable summary of a car's RPP permit zones — small colored letter badges
 * (matching the color-swatch visual language CarStyleDialog already uses for other per-car
 * customization) when zones are set, or a subtle "add" affordance when none are. Replaces what
 * used to be just another "Permit Zones" text button in the action row below, so the car's
 * actual permit status is visible without opening the editor first.
 */
@Composable
private fun PermitZonesSummaryRow(car: Car, onClick: () -> Unit) {
    val zones = remember(car.permitZoneLetters) { car.permitZoneLetterSet().sorted() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        if (zones.isEmpty()) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Add RPP permit zone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "RPP permit:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            zones.forEach { letter ->
                PermitZoneBadge(letter)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun PermitZoneBadge(letter: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
private fun SimpleTextDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Car name") })
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}