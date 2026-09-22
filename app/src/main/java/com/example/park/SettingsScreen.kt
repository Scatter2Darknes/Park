package com.example.park

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity

// Top-level groupings for the Settings screen. Previously all 12 sections lived in one
// long flat scroll; grouping them behind a hub screen keeps each sub-screen short and
// scannable, and matches the app's existing enum-based screen-switching pattern (see
// MainActivity) rather than pulling in Navigation Compose for just this. Not private —
// MainActivity needs it to deep-link (e.g. "Configure API key in Settings" jumping
// straight to DATA_SYNC instead of dumping someone back at the hub).
enum class SettingsCategory(val title: String, val description: String, val icon: ImageVector) {
    PARKING_NOTIFICATIONS("Parking & Notifications", "Reminders, urgent alerts, test notifications", Icons.Filled.Notifications),
    MAP("Map", "Colors, driving mode, zoom, radius, theme", Icons.Filled.Map),
    DATA_SYNC("Data & Sync", "Refresh schedule, offline maps", Icons.Filled.CloudSync),
    BLUETOOTH_BACKGROUND("Bluetooth & Background", "Auto-detect, battery optimization, diagnostics", Icons.Filled.Bluetooth),
    BACKUP("Backup", "Export and import your data", Icons.Filled.SettingsBackupRestore)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    initialCategory: SettingsCategory? = null,
    onInitialCategoryConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    var alwaysAskCar by remember { mutableStateOf(false) }
    var parkingConfirmationStyle by remember { mutableStateOf(SettingsDefaults.PARKING_CONFIRMATION_STYLE) }
    var notificationOffsetMinutes by remember { mutableStateOf(SettingsDefaults.NOTIFICATION_OFFSET_MINUTES) }
    var urgentReminderEnabled by remember { mutableStateOf(SettingsDefaults.URGENT_REMINDER_ENABLED) }
    var urgentOffsetMinutes by remember { mutableStateOf(SettingsDefaults.URGENT_OFFSET_MINUTES) }
    var soonThresholdDays by remember { mutableStateOf(SettingsDefaults.SOON_THRESHOLD_DAYS) }
    var imminentThresholdDays by remember { mutableStateOf(SettingsDefaults.IMMINENT_THRESHOLD_DAYS) }
    var statusColors by remember { mutableStateOf(SweepStatusColors()) }
    var refreshIntervalHours by remember { mutableStateOf(SettingsDefaults.REFRESH_INTERVAL_HOURS) }
    var drivingModeZoom by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_ZOOM) }
    var drivingModeAutoCenter by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_AUTO_CENTER) }
    var drivingModeAutoZoom by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_AUTO_ZOOM) }
    var defaultMapZoom by remember { mutableStateOf(SettingsDefaults.DEFAULT_MAP_ZOOM) }
    var segmentRadiusMeters by remember { mutableStateOf(SettingsDefaults.MAP_SEGMENT_RADIUS_METERS) }
    var mapStyleMode by remember { mutableStateOf(SettingsDefaults.MAP_STYLE_MODE) }
    var mapStyleMenuExpanded by remember { mutableStateOf(false) }
    var wifiOnlyRefresh by remember { mutableStateOf(SettingsDefaults.WIFI_ONLY_REFRESH) }
    var bluetoothAutoDetectEnabled by remember { mutableStateOf(SettingsDefaults.BLUETOOTH_AUTO_DETECT_ENABLED) }
    var bluetoothAutoDropPin by remember { mutableStateOf(SettingsDefaults.BLUETOOTH_AUTO_DROP_PIN) }
    var bluetoothAutoUnparkOnReconnect by remember { mutableStateOf(SettingsDefaults.BLUETOOTH_AUTO_UNPARK_ON_RECONNECT) }
    var autoStopDrivingModeOnDisconnect by remember { mutableStateOf(SettingsDefaults.AUTO_STOP_DRIVING_MODE_ON_DISCONNECT) }
    var showImminentCountdown by remember { mutableStateOf(SettingsDefaults.SHOW_IMMINENT_COUNTDOWN) }
    var tunnelAutoDimEnabled by remember { mutableStateOf(SettingsDefaults.TUNNEL_AUTO_DIM_ENABLED) }
    var showRppZoneLabels by remember { mutableStateOf(SettingsDefaults.SHOW_RPP_ZONE_LABELS) }
    var showMeterBadges by remember { mutableStateOf(SettingsDefaults.SHOW_METER_BADGES) }
    var tileCacheMaxMb by remember { mutableStateOf(SettingsDefaults.TILE_CACHE_MAX_MB) }
    var confirmingClearCache by remember { mutableStateOf(false) }
    var stadiaApiKeyOverride by remember { mutableStateOf("") }
    var dataSfAppTokenOverride by remember { mutableStateOf("") }
    var apiKeyStatusMessage by remember { mutableStateOf<String?>(null) }
    var batteryOptimizationExempt by remember { mutableStateOf(false) }
    var hasBackgroundLocation by remember { mutableStateOf(false) }
    var hasExactAlarmPermission by remember { mutableStateOf(canScheduleExactAlarmsCompat(context)) }
    var reminderHealth by remember { mutableStateOf(currentReminderHealth(context)) }
    var hasNotificationPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }
    var bluetoothLinkedCars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var testBluetoothStatus by remember { mutableStateOf<String?>(null) }
    var cacheSizeBytes by remember { mutableStateOf(0L) }
    var lastRefreshMillis by remember { mutableStateOf<Long?>(null) }
    val isSyncBusy by StreetDataSyncCenter.isBusy.collectAsState()
    val syncStatusMessage by StreetDataSyncCenter.statusMessage.collectAsState()
    var offsetMenuExpanded by remember { mutableStateOf(false) }
    var urgentOffsetMenuExpanded by remember { mutableStateOf(false) }
    var informationalTimeoutMinutes by remember { mutableStateOf(SettingsDefaults.INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES) }
    var informationalTimeoutMenuExpanded by remember { mutableStateOf(false) }

    suspend fun reloadAllSettings() {
        alwaysAskCar = settings.alwaysAskCar.first()
        parkingConfirmationStyle = settings.parkingConfirmationStyle.first()
        notificationOffsetMinutes = settings.notificationOffsetMinutes.first()
        urgentReminderEnabled = settings.urgentReminderEnabled.first()
        urgentOffsetMinutes = settings.urgentOffsetMinutes.first()
        informationalTimeoutMinutes = settings.informationalNotificationTimeoutMinutes.first()
        soonThresholdDays = settings.soonThresholdDays.first()
        imminentThresholdDays = settings.imminentThresholdDays.first()
        statusColors = settings.sweepStatusColorsSnapshot()
        refreshIntervalHours = settings.refreshIntervalHours.first()
        drivingModeZoom = settings.drivingModeZoom.first()
        drivingModeAutoCenter = settings.drivingModeAutoCenter.first()
        drivingModeAutoZoom = settings.drivingModeAutoZoom.first()
        defaultMapZoom = settings.defaultMapZoom.first()
        segmentRadiusMeters = settings.mapSegmentRadiusMeters.first()
        mapStyleMode = settings.mapStyleMode.first()
        wifiOnlyRefresh = settings.wifiOnlyRefresh.first()
        bluetoothAutoDetectEnabled = settings.bluetoothAutoDetectEnabled.first()
        bluetoothAutoDropPin = settings.bluetoothAutoDropPin.first()
        bluetoothAutoUnparkOnReconnect = settings.bluetoothAutoUnparkOnReconnect.first()
        autoStopDrivingModeOnDisconnect = settings.autoStopDrivingModeOnDisconnect.first()
        showImminentCountdown = settings.showImminentCountdown.first()
        tunnelAutoDimEnabled = settings.tunnelAutoDimEnabled.first()
        showRppZoneLabels = settings.showRppZoneLabels.first()
        showMeterBadges = settings.showMeterBadges.first()
        tileCacheMaxMb = settings.tileCacheMaxMb.first()
        cacheSizeBytes = tileCacheSizeBytes()
        lastRefreshMillis = settings.lastRefreshMillis.first()
        stadiaApiKeyOverride = settings.stadiaApiKeyOverride.first()
        dataSfAppTokenOverride = settings.dataSfAppTokenOverride.first()
    }

    LaunchedEffect(Unit) {
        reloadAllSettings()
        bluetoothLinkedCars = AppDatabase.getInstance(context).carDao().getAll()
            .filter { it.bluetoothDeviceAddress != null }
    }

    // Manual refresh now runs through StreetDataSyncCenter (app-scoped) rather than this
    // screen's own coroutine, so it survives navigating away from Settings mid-refresh. That
    // means this screen needs to explicitly re-read "last synced" once a refresh finishes
    // while it's open, rather than setting it directly in the button's own callback.
    LaunchedEffect(isSyncBusy) {
        if (!isSyncBusy) {
            lastRefreshMillis = settings.lastRefreshMillis.first()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationExempt = isIgnoringBatteryOptimizations(context)
                hasBackgroundLocation = hasBackgroundLocationPermission(context)
                hasExactAlarmPermission = canScheduleExactAlarmsCompat(context)
                reminderHealth = currentReminderHealth(context)
                hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var backupStatusMessage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupStatusMessage = try {
                    val json = exportBackupJson(context)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    "Backup exported."
                } catch (e: Exception) {
                    "Export failed: ${e.message}"
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupStatusMessage = try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (json == null) {
                        "Could not read the selected file."
                    } else {
                        importBackupJson(context, json).fold(
                            onSuccess = { reloadAllSettings(); "Backup imported." },
                            onFailure = { e -> "Import failed: ${e.message}" }
                        )
                    }
                } catch (e: Exception) {
                    "Import failed: ${e.message}"
                }
            }
        }
    }

    fun updateStatusColor(slot: SweepColorSlot, newHex: String) {
        val updated = swapAssignment(statusColors, slot, newHex)
        statusColors = updated
        scope.launch { settings.setSweepStatusColors(updated) }
    }

    // null = category hub; non-null = viewing that category's sub-screen. Seeded from
    // initialCategory (e.g. the sync dialog's "Configure API key" jumping straight to
    // DATA_SYNC) rather than always starting at the hub — consumed once via LaunchedEffect
    // below so a later, ordinary navigation into Settings (gear icon, back button) doesn't
    // replay a stale deep-link from a previous visit.
    var currentCategory by remember { mutableStateOf(initialCategory) }
    LaunchedEffect(Unit) { onInitialCategoryConsumed() }

    @Composable
    fun ParkingNotificationsSection() {
        SectionLabel("Parking")

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Ask which car every time", modifier = Modifier.weight(1f))
            Switch(
                checked = alwaysAskCar,
                onCheckedChange = { checked ->
                    alwaysAskCar = checked
                    scope.launch { settings.setAlwaysAskCar(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        Text("Parking confirmation", style = MaterialTheme.typography.labelMedium)
        DescriptionToggle(
            "Cautious double-checks the street and side before saving, and separately asks " +
                    "about an exact pin — the extra step exists to catch a wrong guess before " +
                    "it's saved. Simple collapses that into one dialog for fewer taps, trading " +
                    "away that checkpoint."
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = parkingConfirmationStyle == "CAUTIOUS",
                onClick = {
                    parkingConfirmationStyle = "CAUTIOUS"
                    scope.launch { settings.setParkingConfirmationStyle("CAUTIOUS") }
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) { Text("Cautious") }
            SegmentedButton(
                selected = parkingConfirmationStyle == "SIMPLE",
                onClick = {
                    parkingConfirmationStyle = "SIMPLE"
                    scope.launch { settings.setParkingConfirmationStyle("SIMPLE") }
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) { Text("Simple") }
        }

        Spacer(Modifier.height(16.dp))

        Text("Early reminder (dismissible)", style = MaterialTheme.typography.labelMedium)

        val currentOffsetLabel = NOTIFICATION_OFFSET_PRESETS
            .firstOrNull { it.first == notificationOffsetMinutes }?.second
            ?: "$notificationOffsetMinutes minutes before"

        ExposedDropdownMenuBox(
            expanded = offsetMenuExpanded,
            onExpandedChange = { offsetMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = currentOffsetLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Notify me") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = offsetMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = offsetMenuExpanded,
                onDismissRequest = { offsetMenuExpanded = false }
            ) {
                NOTIFICATION_OFFSET_PRESETS.forEach { (minutes, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            notificationOffsetMinutes = minutes
                            offsetMenuExpanded = false
                            // Urgent must always fire with LESS time left than the early
                            // reminder — otherwise the persistent, harder-to-dismiss alert
                            // could go off before the dismissible heads-up ever did, which
                            // reads as backwards. Picking a shorter early offset than
                            // whatever urgent is currently set to auto-corrects urgent down
                            // to the largest still-valid preset, rather than silently
                            // leaving an inverted pair of settings in place.
                            val correctedUrgent = if (urgentOffsetMinutes >= minutes) {
                                URGENT_OFFSET_PRESETS.map { it.first }.filter { it < minutes }.maxOrNull()
                            } else null
                            if (correctedUrgent != null) urgentOffsetMinutes = correctedUrgent
                            scope.launch {
                                settings.setNotificationOffsetMinutes(minutes)
                                correctedUrgent?.let { settings.setUrgentOffsetMinutes(it) }
                                rescheduleAllActiveReminders(context)
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Urgent reminder", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Persistent \u2014 stays until you tap \u201cI moved my car.\u201d " +
                            "Always fires sooner than the early reminder above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = urgentReminderEnabled,
                onCheckedChange = { checked ->
                    urgentReminderEnabled = checked
                    scope.launch {
                        settings.setUrgentReminderEnabled(checked)
                        rescheduleAllActiveReminders(context)
                    }
                }
            )
        }

        if (urgentReminderEnabled) {
            Spacer(Modifier.height(4.dp))
            val currentUrgentLabel = URGENT_OFFSET_PRESETS
                .firstOrNull { it.first == urgentOffsetMinutes }?.second
                ?: "$urgentOffsetMinutes minutes before"

            ExposedDropdownMenuBox(
                expanded = urgentOffsetMenuExpanded,
                onExpandedChange = { urgentOffsetMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = currentUrgentLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Move it now") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = urgentOffsetMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = urgentOffsetMenuExpanded,
                    onDismissRequest = { urgentOffsetMenuExpanded = false }
                ) {
                    // Only offers presets strictly less than the early reminder's own offset
                    // — the reverse of the auto-correction above, so it's impossible to pick
                    // an invalid (urgent >= early) combination from this menu in the first
                    // place, not just corrected after the fact.
                    URGENT_OFFSET_PRESETS.filter { it.first < notificationOffsetMinutes }.forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                urgentOffsetMinutes = minutes
                                urgentOffsetMenuExpanded = false
                                scope.launch {
                                    settings.setUrgentOffsetMinutes(minutes)
                                    rescheduleAllActiveReminders(context)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Can Park's reminders actually be SHOWN? Alarms can be perfect and Android still drops every
        // notification if they're blocked — total silence, so this sits above the exact-alarm row.
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Notifications")
        DescriptionToggle(
            "Park's reminders are notifications. If Android is blocking them — for the whole app or " +
                    "just the reminders channel — the alarms still go off but nothing is shown, so a " +
                    "parked car gets no reminder at all. Android can't turn them on from inside the " +
                    "app; the button opens the system page where you do."
        )
        Spacer(Modifier.height(8.dp))
        if (reminderHealth.isHealthy) {
            StatusOkRow("Notifications: Allowed")
            if (reminderHealth.statusChannelBlocked) {
                Spacer(Modifier.height(8.dp))
                DescriptionToggle(
                    "The quiet \"${reminderChannelName(NotificationHelper.CHANNEL_ID_STATUS)}\" channel is off, " +
                            "so \"parked automatically\" style notices won't show. Reminders are not affected."
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppNotificationSettings(context) }) {
                Text("Open Notification Settings")
            }
        } else {
            Text(
                "Notifications: Blocked — " + reminderHealthMessage(reminderHealth),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openReminderHealthSettings(context, reminderHealth) }) {
                Text(
                    if (reminderHealth.health == ReminderHealth.REMINDER_CHANNEL_BLOCKED)
                        "Open ${reminderChannelName(reminderHealth.blockedChannelId)} Settings"
                    else "Allow Notifications"
                )
            }
        }

        // Below Android 12 exact alarms need no permission, so there's nothing to show.
        if (exactAlarmPermissionApplies()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionLabel("Exact alarms")
            DescriptionToggle(
                "Reminders are timed with exact alarms, which Android 12+ treats as a special " +
                        "permission (\"Alarms & reminders\"). Android 14+ leaves it off on a fresh " +
                        "install. Without it, reminders still fire but use a less precise alarm " +
                        "that Android may delay by several minutes — not what you want when a " +
                        "reminder is meant to beat the street sweeper. Android can't grant this " +
                        "from inside the app; the button opens the system page where you switch it on."
            )
            Spacer(Modifier.height(8.dp))
            if (hasExactAlarmPermission) {
                StatusOkRow("Exact alarms allowed")
                Spacer(Modifier.height(8.dp))
                DescriptionToggle(
                    "To turn this back off, use the same page — Android doesn't let an app " +
                            "revoke its own permission. (Turning it off makes Android stop the " +
                            "app; reminders are restored, as less precise ones, next time it opens.)"
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openExactAlarmSettings(context) }) {
                    Text("Open Alarms & Reminders Settings")
                }
            } else {
                Text(
                    "Not allowed — reminders may arrive late",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openExactAlarmSettings(context) }) {
                    Text("Allow Exact Alarms")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Test notifications")
        Text(
            "Fires immediately so you can check how each one looks and behaves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val exampleSweepMillis = System.currentTimeMillis() + 2 * 60 * 60_000L
                val exampleTime = formatSweepDateTime(
                    java.time.Instant.ofEpochMilli(exampleSweepMillis).atZone(SF_ZONE)
                )
                NotificationHelper.showReminder(
                    context = context,
                    notificationId = TEST_NORMAL_NOTIFICATION_ID,
                    kind = ReminderKind.NORMAL,
                    title = "Move Test Car soon",
                    text = "Street cleaning is coming up on Test St \u2014 $exampleTime",
                    carId = TEST_NOTIFICATION_CAR_ID,
                    carName = "Test Car",
                    corridor = "Test St",
                    nextSweepAtMillis = exampleSweepMillis
                )
            }) { Text("Test normal") }

            Button(onClick = {
                val exampleSweepMillis = System.currentTimeMillis() + 15 * 60_000L
                val exampleTime = formatSweepDateTime(
                    java.time.Instant.ofEpochMilli(exampleSweepMillis).atZone(SF_ZONE)
                )
                NotificationHelper.showReminder(
                    context = context,
                    notificationId = TEST_URGENT_NOTIFICATION_ID,
                    kind = ReminderKind.URGENT,
                    title = "Move Test Car now",
                    text = "Street cleaning on Test St starts $exampleTime",
                    carId = TEST_NOTIFICATION_CAR_ID,
                    carName = "Test Car",
                    corridor = "Test St",
                    nextSweepAtMillis = exampleSweepMillis
                )
            }) { Text("Test urgent") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val exampleDeadlineMillis = System.currentTimeMillis() + 2 * 60 * 60_000L
                val exampleTime = formatSweepDateTime(
                    java.time.Instant.ofEpochMilli(exampleDeadlineMillis).atZone(SF_ZONE)
                )
                NotificationHelper.showReminder(
                    context = context,
                    notificationId = TEST_RPP_NORMAL_NOTIFICATION_ID,
                    kind = ReminderKind.RPP_NORMAL,
                    title = "Move Test Car soon",
                    text = "Non-permit time limit coming up in RPP Zone A — $exampleTime",
                    carId = TEST_NOTIFICATION_CAR_ID,
                    carName = "Test Car",
                    corridor = "RPP Zone A",
                    nextSweepAtMillis = exampleDeadlineMillis
                )
            }) { Text("Test RPP normal") }

            Button(onClick = {
                val exampleDeadlineMillis = System.currentTimeMillis() + 15 * 60_000L
                val exampleTime = formatSweepDateTime(
                    java.time.Instant.ofEpochMilli(exampleDeadlineMillis).atZone(SF_ZONE)
                )
                NotificationHelper.showReminder(
                    context = context,
                    notificationId = TEST_RPP_URGENT_NOTIFICATION_ID,
                    kind = ReminderKind.RPP_URGENT,
                    title = "Move Test Car now",
                    text = "The non-permit time limit in RPP Zone A is up $exampleTime",
                    carId = TEST_NOTIFICATION_CAR_ID,
                    carName = "Test Car",
                    corridor = "RPP Zone A",
                    nextSweepAtMillis = exampleDeadlineMillis
                )
            }) { Text("Test RPP urgent") }
        }
    }
    @Composable
    fun MapSettingsSection() {
        SectionLabel("Map colors")

        Text(
            "Soon (yellow) starts ${"%.1f".format(soonThresholdDays)} days before sweeping",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = soonThresholdDays,
            onValueChange = { newValue ->
                // Soon must stay above Imminent or the two colors would invert/overlap.
                soonThresholdDays = newValue.coerceAtLeast(imminentThresholdDays + 0.5f)
            },
            onValueChangeFinished = {
                scope.launch { settings.setSoonThresholdDays(soonThresholdDays) }
            },
            valueRange = 1f..7f
        )

        Text(
            "Imminent (red) starts ${"%.1f".format(imminentThresholdDays)} days before sweeping",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = imminentThresholdDays,
            onValueChange = { newValue ->
                imminentThresholdDays = newValue.coerceAtMost(soonThresholdDays - 0.5f).coerceAtLeast(0f)
            },
            onValueChangeFinished = {
                scope.launch { settings.setImminentThresholdDays(imminentThresholdDays) }
            },
            valueRange = 0f..6f
        )

        Spacer(Modifier.height(16.dp))

        Text("Highlight colors", style = MaterialTheme.typography.labelMedium)
        DescriptionToggle(
            "Each status must be a different color; picking one already in use swaps it " +
                    "with the status that had it."
        )
        Spacer(Modifier.height(4.dp))
        StatusColorRow("Not soon (safe)", statusColors.safeHex) { updateStatusColor(SweepColorSlot.SAFE, it) }
        StatusColorRow("Soon", statusColors.soonHex) { updateStatusColor(SweepColorSlot.SOON, it) }
        StatusColorRow("Imminent", statusColors.imminentHex) { updateStatusColor(SweepColorSlot.IMMINENT, it) }
        StatusColorRow("Currently sweeping", statusColors.activeHex) { updateStatusColor(SweepColorSlot.ACTIVE, it) }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Driving mode")
        Text(
            "How far in the map zooms when \u201cI'm Parking Right Now\u201d is active",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Zoom level: ${"%.1f".format(drivingModeZoom)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = drivingModeZoom,
            onValueChange = { drivingModeZoom = it },
            onValueChangeFinished = {
                scope.launch { settings.setDrivingModeZoom(drivingModeZoom) }
            },
            valueRange = 17f..20f,
            steps = 5 // 0.5 increments: 17.0, 17.5, 18.0 ... 20.0
        )

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-center map", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Keep the map following your location while driving mode is active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = drivingModeAutoCenter,
                onCheckedChange = { checked ->
                    drivingModeAutoCenter = checked
                    scope.launch { settings.setDrivingModeAutoCenter(checked) }
                }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-zoom map", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "Zoom to the level above when driving mode starts, and back to your " +
                            "previous zoom when it ends \u2014 independent of auto-center, in " +
                            "case you want rotation/following without a forced zoom change."
                )
            }
            Switch(
                checked = drivingModeAutoZoom,
                onCheckedChange = { checked ->
                    drivingModeAutoZoom = checked
                    scope.launch { settings.setDrivingModeAutoZoom(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // "Show time-left countdown" moved here from Driving mode \u2014 the countdown labels
        // it controls show on the map generally, not just while driving mode is active, so
        // it belongs with the other general map-display controls rather than implying it's
        // driving-mode-specific.
        SectionLabel("Map display")
        Text("Zoom level when the map first opens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Default zoom: ${"%.1f".format(defaultMapZoom)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = defaultMapZoom,
            onValueChange = { defaultMapZoom = it },
            onValueChangeFinished = {
                scope.launch { settings.setDefaultMapZoom(defaultMapZoom) }
            },
            valueRange = 14f..19f,
            steps = 9 // 0.5 increments
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "How far around your location to show colored streets",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Radius: $segmentRadiusMeters m", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = segmentRadiusMeters.toFloat(),
            onValueChange = { segmentRadiusMeters = it.toInt() },
            onValueChangeFinished = {
                scope.launch { settings.setMapSegmentRadiusMeters(segmentRadiusMeters) }
            },
            valueRange = 300f..1200f,
            steps = 8 // 100 m increments
        )

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show time-left countdown", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "Shows a small color-matched countdown label (e.g. \"45m\") on nearby " +
                            "imminent or currently-sweeping streets \u2014 useful for short stops."
                )
            }
            Switch(
                checked = showImminentCountdown,
                onCheckedChange = { checked ->
                    showImminentCountdown = checked
                    scope.launch { settings.setShowImminentCountdown(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-dim map in tunnels", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "While Driving Mode is on, dims the map when GPS goes silent for about 20 " +
                            "seconds while you were moving (like a tunnel), and brightens it again " +
                            "when the signal returns. Sitting still at a red light never counts. " +
                            "Turn this off if the map dims when it shouldn't."
                )
            }
            Switch(
                checked = tunnelAutoDimEnabled,
                onCheckedChange = { checked ->
                    tunnelAutoDimEnabled = checked
                    scope.launch { settings.setTunnelAutoDimEnabled(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show RPP zone labels", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "Shows a small \"RPP A\" label on nearby blocks currently under an active " +
                            "residential-permit time limit — only while that limit is " +
                            "actually in effect, not for every RPP-regulated block regardless " +
                            "of day or time."
                )
            }
            Switch(
                checked = showRppZoneLabels,
                onCheckedChange = { checked ->
                    showRppZoneLabels = checked
                    scope.launch { settings.setShowRppZoneLabels(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show meter badges", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "Shows a \"$ Metered\" badge on nearby currently-enforced parking meters " +
                            "— separate from street cleaning, since a curb can be both swept " +
                            "and metered at once."
                )
            }
            Switch(
                checked = showMeterBadges,
                onCheckedChange = { checked ->
                    showMeterBadges = checked
                    scope.launch { settings.setShowMeterBadges(checked) }
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Color Theme")
        val mapStyleOptions = listOf(
            "DAY" to "Day",
            "NIGHT" to "Night",
            "AUTO" to "Automatic (matches system)",
            "AUTO_TIME" to "Automatic (time of day)"
        )
        val mapStyleLabel = mapStyleOptions.firstOrNull { it.first == mapStyleMode }?.second ?: "Day"

        DescriptionToggle(
            "Applies across the whole app \u2014 not just the map. While driving, the map " +
                    "may also switch to dark on its own if GPS signal drops (e.g. in a tunnel)."
        )
        Spacer(Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = mapStyleMenuExpanded,
            onExpandedChange = { mapStyleMenuExpanded = it }
        ) {
            OutlinedTextField(
                value = mapStyleLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Color Theme") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mapStyleMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = mapStyleMenuExpanded,
                onDismissRequest = { mapStyleMenuExpanded = false }
            ) {
                mapStyleOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            mapStyleMode = value
                            mapStyleMenuExpanded = false
                            scope.launch { settings.setMapStyleMode(value) }
                        }
                    )
                }
            }
        }
    }
    @Composable
    fun DataSyncSection() {
        SectionLabel("API Keys")
        DescriptionToggle(
            "Only needed if you want to use your own Stadia Maps / DataSF quota instead of " +
                    "whichever key is built into this copy of the app \u2014 useful if this " +
                    "was shared with you to test. Leave blank to keep using the built-in " +
                    "default; saving takes effect immediately, no restart needed."
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = stadiaApiKeyOverride,
            onValueChange = { stadiaApiKeyOverride = it },
            label = { Text("Your Stadia Maps API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = dataSfAppTokenOverride,
            onValueChange = { dataSfAppTokenOverride = it },
            label = { Text("Your DataSF app token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val newStadiaKey = stadiaApiKeyOverride.trim()
                val newDataSfToken = dataSfAppTokenOverride.trim()
                // Updates the live in-memory value first (so the very next tile/data
                // request already uses it), then persists — same ordering as everywhere
                // else in this screen that pairs a local var with a DataStore write.
                ApiKeys.setStadiaMapsKeyOverride(newStadiaKey)
                ApiKeys.setDataSfAppTokenOverride(newDataSfToken)
                scope.launch {
                    settings.setStadiaApiKeyOverride(newStadiaKey)
                    settings.setDataSfAppTokenOverride(newDataSfToken)
                }
                apiKeyStatusMessage = "Saved."
            }) { Text("Save Keys") }
            TextButton(onClick = {
                stadiaApiKeyOverride = ""
                dataSfAppTokenOverride = ""
                ApiKeys.setStadiaMapsKeyOverride(null)
                ApiKeys.setDataSfAppTokenOverride(null)
                scope.launch {
                    settings.setStadiaApiKeyOverride("")
                    settings.setDataSfAppTokenOverride("")
                }
                apiKeyStatusMessage = "Cleared \u2014 back to the built-in default."
            }) { Text("Reset to Default") }
        }
        apiKeyStatusMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Offline maps")
        Text(
            "Cached tile storage: ${formatBytes(cacheSizeBytes)}",
            style = MaterialTheme.typography.bodyMedium
        )
        DescriptionToggle(
            "Includes both normally-browsed tiles and anything downloaded for offline " +
                    "use (from the map screen's Offline button)."
        )
        Spacer(Modifier.height(8.dp))
        Text("Max cache size: $tileCacheMaxMb MB", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = tileCacheMaxMb.toFloat(),
            onValueChange = { tileCacheMaxMb = it.toInt() },
            onValueChangeFinished = {
                scope.launch {
                    settings.setTileCacheMaxMb(tileCacheMaxMb)
                    val maxBytes = tileCacheMaxMb * 1024L * 1024L
                    org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheMaxBytes = maxBytes
                    org.osmdroid.config.Configuration.getInstance().tileFileSystemCacheTrimBytes = (maxBytes * 0.9).toLong()
                }
            },
            valueRange = 50f..1000f,
            steps = 18 // 50 MB increments
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { confirmingClearCache = true }) { Text("Clear Cached Tiles") }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Data")

        Text(
            "Refresh sweeping data every $refreshIntervalHours hours",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = refreshIntervalHours.toFloat(),
            onValueChange = { refreshIntervalHours = it.toInt() },
            onValueChangeFinished = {
                scope.launch {
                    settings.setRefreshIntervalHours(refreshIntervalHours)
                    // REPLACE (not KEEP) so the new interval takes effect immediately
                    // rather than waiting for the currently-scheduled period to elapse.
                    scheduleSweepingRefresh(
                        context, refreshIntervalHours.toLong(), ExistingPeriodicWorkPolicy.REPLACE,
                        wifiOnly = wifiOnlyRefresh
                    )
                }
            },
            valueRange = 6f..168f,
            steps = 10
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Text("Wi-Fi only for background refresh", modifier = Modifier.weight(1f))
            Switch(
                checked = wifiOnlyRefresh,
                onCheckedChange = { checked ->
                    wifiOnlyRefresh = checked
                    scope.launch {
                        settings.setWifiOnlyRefresh(checked)
                        scheduleSweepingRefresh(
                            context, refreshIntervalHours.toLong(), ExistingPeriodicWorkPolicy.REPLACE,
                            wifiOnly = checked
                        )
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        val lastSyncedText = lastRefreshMillis?.let { formatRelativeTime(it) } ?: "Never synced yet"
        Text("Last synced: $lastSyncedText", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { StreetDataSyncCenter.triggerManualRefresh() },
            enabled = !isSyncBusy
        ) {
            Text(if (isSyncBusy) "Refreshing\u2026" else "Refresh Data Now")
        }
        syncStatusMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))

        DescriptionToggle(
            "Note: DataSF's schedule can lag what's actually posted on the street \u2014 " +
                    "for example, a block might be signed \"every Monday\" while the database " +
                    "still says \"2nd & 4th Monday.\" If a spot's real signage doesn't match " +
                    "what's shown here, trust the sign \u2014 tap the block on the map and use " +
                    "\"Doesn't match the sign? Fix it\" to correct that block specifically."
        )
    }
    @Composable
    fun BluetoothBackgroundSection() {
        SectionLabel("Bluetooth auto-detect")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Bluetooth auto-detect", style = MaterialTheme.typography.labelMedium)
                DescriptionToggle(
                    "Auto-parks/unparks a linked car when its Bluetooth disconnects/reconnects, " +
                            "even while the app isn't open. Turning this off, not just leaving " +
                            "no car linked, is what actually saves battery: it disables the two " +
                            "system-level Bluetooth receivers so Android stops waking this app " +
                            "for every Bluetooth connect/disconnect on the phone, for any device " +
                            "\u2014 not just a linked one. Only worth turning off if you don't " +
                            "plan on linking a car to a Bluetooth device at all."
                )
            }
            Switch(
                checked = bluetoothAutoDetectEnabled,
                onCheckedChange = { checked ->
                    bluetoothAutoDetectEnabled = checked
                    scope.launch { settings.setBluetoothAutoDetectEnabled(checked) }
                }
            )
        }

        if (bluetoothAutoDetectEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Drop an exact pin", style = MaterialTheme.typography.labelMedium)
                    DescriptionToggle(
                        "When a linked car's Bluetooth disconnects and the spot is a confident " +
                                "match, also drop a pin at your exact location \u2014 not just " +
                                "highlight the street."
                    )
                }
                Switch(
                    checked = bluetoothAutoDropPin,
                    onCheckedChange = { checked ->
                        bluetoothAutoDropPin = checked
                        scope.launch { settings.setBluetoothAutoDropPin(checked) }
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Unpark on reconnect", style = MaterialTheme.typography.labelMedium)
                    DescriptionToggle(
                        "When a linked car's Bluetooth reconnects, clear its saved parking " +
                                "spot \u2014 assumes you're back in the car and about to drive away."
                    )
                }
                Switch(
                    checked = bluetoothAutoUnparkOnReconnect,
                    onCheckedChange = { checked ->
                        bluetoothAutoUnparkOnReconnect = checked
                        scope.launch { settings.setBluetoothAutoUnparkOnReconnect(checked) }
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Stop Driving Mode on disconnect", style = MaterialTheme.typography.labelMedium)
                    DescriptionToggle(
                        "When the linked car currently shown as \u201cdriving\u201d disconnects, " +
                                "also turn off Driving Mode \u2014 its heading-lock/zoom/rotation " +
                                "stop being meaningful once the drive is actually over. Only acts " +
                                "on the car currently displayed as driving, not just any linked car."
                    )
                }
                Switch(
                    checked = autoStopDrivingModeOnDisconnect,
                    onCheckedChange = { checked ->
                        autoStopDrivingModeOnDisconnect = checked
                        scope.launch { settings.setAutoStopDrivingModeOnDisconnect(checked) }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            val currentTimeoutLabel = INFORMATIONAL_TIMEOUT_PRESETS
                .firstOrNull { it.first == informationalTimeoutMinutes }?.second
                ?: "$informationalTimeoutMinutes minutes"
            ExposedDropdownMenuBox(
                expanded = informationalTimeoutMenuExpanded,
                onExpandedChange = { informationalTimeoutMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = currentTimeoutLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Clear \"parked\" / \"unparked\" notices after") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = informationalTimeoutMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = informationalTimeoutMenuExpanded,
                    onDismissRequest = { informationalTimeoutMenuExpanded = false }
                ) {
                    INFORMATIONAL_TIMEOUT_PRESETS.forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                informationalTimeoutMinutes = minutes
                                informationalTimeoutMenuExpanded = false
                                scope.launch { settings.setInformationalNotificationTimeoutMinutes(minutes) }
                            }
                        )
                    }
                }
            }
            DescriptionToggle(
                "How long the quiet \"parked automatically\" and \"unparked\" notifications stay " +
                        "before removing themselves. Only these two — street-cleaning and time-limit " +
                        "reminders never time out, and neither does the \"Did you just park?\" prompt when " +
                        "the spot couldn't be pinned down at all (an unsure guess lapses after 4 hours)."
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Notifications")
        DescriptionToggle(
            "Parking reminders (both tiers) need this to show up at all. Since this moved out " +
                    "of the automatic prompt at first launch, it's here for anyone who dismissed " +
                    "that or wants to double-check it's still on."
        )
        Spacer(Modifier.height(8.dp))
        if (hasNotificationPermission) {
            StatusOkRow("Notifications enabled")
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "Android doesn't allow an app to re-disable this on itself \u2014 this opens the " +
                        "app's settings page, where Notifications lets you turn it back off manually."
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppNotificationSettings(context) }) {
                Text("Open Notification Settings")
            }
        } else {
            Text(
                "Not enabled \u2014 you won't see parking reminders",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("Enable Notifications") }
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "If tapping that does nothing, Android has likely already permanently denied " +
                        "it from an earlier prompt \u2014 this opens the app's settings page instead, " +
                        "where Notifications lets you turn it on manually."
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { openAppNotificationSettings(context) }) {
                Text("Open Notification Settings Instead")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Background reliability")
        DescriptionToggle(
            "For Bluetooth auto-detect and reminders to keep working even when the app " +
                    "isn't open, this app should be exempt from battery optimization. Some " +
                    "phone makers (Samsung included) are aggressive about restricting " +
                    "background apps by default."
        )
        Spacer(Modifier.height(8.dp))
        if (batteryOptimizationExempt) {
            StatusOkRow("Exempt from battery optimization")
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "Android doesn't allow an app to re-enable this on itself directly \u2014 " +
                        "this opens the app's settings page, where Battery lets you switch " +
                        "it back manually."
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                openAppSettingsForBatteryOptimization(context)
            }) { Text("Re-enable Battery Optimization") }
        } else {
            Text(
                "Not yet exempt \u2014 background reliability may be reduced",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                requestIgnoreBatteryOptimizations(context)
            }) { Text("Disable Battery Optimization") }
        }

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "Samsung phones also have their own separate battery management (Settings " +
                        "\u2192 Battery \u2192 Background usage limits) on top of Android's own. " +
                        "Worth checking Park isn't listed under \"Sleeping apps\" there too, " +
                        "since the toggle above doesn't cover it."
            )
        }

        Spacer(Modifier.height(16.dp))

        DescriptionToggle(
            "Bluetooth auto-detect also needs Location set to \"Allow all the time,\" not just " +
                    "\"while using the app\" \u2014 the disconnect/connect receivers run from a " +
                    "system broadcast, which Android treats as background access even though " +
                    "it only takes a moment. Without this, auto-detect silently gets no GPS fix " +
                    "at all when the app isn't open, even though everything else about it works."
        )
        Spacer(Modifier.height(8.dp))
        if (hasBackgroundLocation) {
            StatusOkRow("Location set to Allow all the time")
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "Android doesn't allow switching this back to \"while using the app\" from " +
                        "within the app either \u2014 this opens the same settings page, where " +
                        "Permissions > Location lets you change it back manually."
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppSettingsForBackgroundLocation(context) }) {
                Text("Open Location Settings")
            }
        } else {
            Text(
                "Currently set to \"while using the app\" \u2014 auto-detect won't work in " +
                        "the background until this is changed",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            DescriptionToggle(
                "Android doesn't allow requesting this permission through a normal in-app " +
                        "prompt \u2014 this opens the app's settings page, where Permissions > " +
                        "Location lets you switch it to \"Allow all the time\" manually."
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openAppSettingsForBackgroundLocation(context) }) {
                Text("Open Location Settings")
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Test Sync Setup UI")
        DescriptionToggle(
            "The sync setup bar/dialog only shows itself while street data has never fully " +
                    "synced, and disappears for good once it has \u2014 nothing else brings it back. " +
                    "This re-shows it without touching the actual data, so pressing \"Sync Now\" " +
                    "on it afterward re-syncs for real and it settles back to hidden again."
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { StreetDataSyncCenter.forceShowSetupUiForTesting() }) {
            Text("Show Sync Setup UI Again")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Test Bluetooth Hooks")
        if (!bluetoothAutoDetectEnabled) {
            Text(
                "Bluetooth auto-detect is turned off above \u2014 enable it to test these hooks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            DescriptionToggle(
                "Runs the same logic a real Bluetooth disconnect/reconnect would trigger, " +
                        "without needing to actually disconnect anything \u2014 useful for telling " +
                        "apart \"this logic is broken\" from \"Android isn't delivering the " +
                        "broadcast in the background\" (check Logcat, tag \"$BLUETOOTH_AUTO_DETECT_LOG_TAG\", either way)."
            )
            Spacer(Modifier.height(8.dp))
            if (bluetoothLinkedCars.isEmpty()) {
                Text(
                    "No cars have a linked Bluetooth device yet \u2014 set one up in Manage Cars first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                bluetoothLinkedCars.forEach { car ->
                    Text(car.name, style = MaterialTheme.typography.labelMedium)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                simulateBluetoothDisconnect(context, car)
                                testBluetoothStatus = "Simulated disconnect for ${car.name} \u2014 check for a notification, and the map's pill/banner if it's open."
                            }
                        }) { Text("Simulate Disconnect (Park)") }
                        TextButton(onClick = {
                            scope.launch {
                                simulateBluetoothReconnect(context, car)
                                testBluetoothStatus = "Simulated reconnect for ${car.name} \u2014 check for a notification, and the map's pill/banner if it's open."
                            }
                        }) { Text("Simulate Reconnect (Unpark)") }
                    }
                }
                testBluetoothStatus?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    @Composable
    fun BackupSection() {
        SectionLabel("Backup")
        DescriptionToggle(
            "Exports car profiles, saved locations, schedule corrections, and settings " +
                    "to a file you choose \u2014 useful before a factory reset or phone swap. " +
                    "Importing adds to what's already here; it won't delete or replace anything."
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                exportLauncher.launch("park-backup-${System.currentTimeMillis()}.json")
            }) { Text("Export Backup") }
            Button(onClick = {
                importLauncher.launch(arrayOf("application/json"))
            }) { Text("Import Backup") }
        }
        backupStatusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentCategory?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentCategory != null) currentCategory = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .verticalScroll(scrollState)
            ) {
                Crossfade(targetState = currentCategory, label = "settingsCategoryTransition") { category ->
                    // Crossfade lays out its content like a Box (overlapping), not a Column
                    // (stacked) — the section functions below emit several sibling composables
                    // with no Column of their own, relying entirely on whatever container
                    // they're placed in to stack them vertically. Without this wrapping
                    // Column, Crossfade collapsed every row on top of itself into one small
                    // overlapping block instead of a normal scrollable list.
                    Column {
                        when (category) {
                            null -> SettingsHub(onSelect = { currentCategory = it })
                            SettingsCategory.PARKING_NOTIFICATIONS -> ParkingNotificationsSection()
                            SettingsCategory.MAP -> MapSettingsSection()
                            SettingsCategory.DATA_SYNC -> DataSyncSection()
                            SettingsCategory.BLUETOOTH_BACKGROUND -> BluetoothBackgroundSection()
                            SettingsCategory.BACKUP -> BackupSection()
                        }
                    }
                }
            }
            VerticalScrollbar(scrollState, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
        }
    }

    // Clearing the tile cache is permanent and immediate — both normally-browsed tiles and
    // anything downloaded via Offline Maps go with it, with no undo — so this confirms first,
    // same treatment as deleting a car or a saved location, rather than firing straight off
    // the button tap.
    if (confirmingClearCache) {
        AlertDialog(
            onDismissRequest = { confirmingClearCache = false },
            title = { Text("Clear cached tiles?") },
            text = {
                Text(
                    "This removes all cached map tiles, including anything downloaded for " +
                            "offline use. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearTileCache()
                    cacheSizeBytes = tileCacheSizeBytes()
                    confirmingClearCache = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearCache = false }) { Text("Cancel") }
            }
        )
    }
}

/** Tappable list of the 5 settings categories. Each row's own sub-screen is one flat,
 *  scrollable list of controls \u2014 same style as before, just far shorter per screen. */
@Composable
private fun SettingsHub(onSelect: (SettingsCategory) -> Unit) {
    Column {
        SettingsCategory.values().forEach { category ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(category) }
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(category.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }
        // Version footer — mainly for telling two builds apart when comparing notes with
        // someone else running this app (e.g. a tester on an older copy than yours), since
        // there's no in-app update mechanism to otherwise surface that. VERSION_NAME/CODE
        // come straight from the module's own defaultConfig, so this always matches
        // whatever was actually built, with nothing to keep in sync by hand.
        Text(
            "Park v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        )
    }
}

/**
 * Thin custom scrollbar track+thumb for a plain Column+verticalScroll, which (unlike a
 * LazyColumn) has no built-in scroll indicator of any kind \u2014 without this, a long
 * settings sub-screen gave no visual hint there was more below the fold. Draws nothing when
 * the content already fits without scrolling (maxValue == 0), so it's safe to drop onto the
 * short hub screen too.
 */
@Composable
private fun VerticalScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    if (scrollState.maxValue <= 0) return
    BoxWithConstraints(modifier = modifier.fillMaxHeight().width(4.dp)) {
        val viewportHeightPx = constraints.maxHeight.toFloat()
        if (viewportHeightPx <= 0f) return@BoxWithConstraints
        val totalContentHeightPx = viewportHeightPx + scrollState.maxValue
        val thumbHeightRatio = (viewportHeightPx / totalContentHeightPx).coerceIn(0.08f, 1f)
        val thumbHeightPx = viewportHeightPx * thumbHeightRatio
        val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
        val thumbOffsetPx = (viewportHeightPx - thumbHeightPx) * scrollFraction
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(4.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        )
    }
}

// Fixed IDs for the test buttons — all defined in NotificationIds, in the range reserved above any real car id.
private const val TEST_NORMAL_NOTIFICATION_ID = NotificationIds.TEST_NORMAL
private const val TEST_URGENT_NOTIFICATION_ID = NotificationIds.TEST_URGENT
private const val TEST_RPP_NORMAL_NOTIFICATION_ID = NotificationIds.TEST_RPP_NORMAL
private const val TEST_RPP_URGENT_NOTIFICATION_ID = NotificationIds.TEST_RPP_URGENT
// Positive, not -1 — still just as impossible for a real car to collide with (Room's
// autoincrement starts at 1 and would need roughly a billion cars first), but a positive
// value round-trips more predictably through the "reminderCarId" content-intent extra than
// a negative sentinel would.
private const val TEST_NOTIFICATION_CAR_ID = NotificationIds.TEST_CAR_ID

// 10 choices for 4 slots, so there's always genuine choice beyond just permuting the
// original 4 — includes the 4 defaults plus 6 more, all visually distinct from each other.
private val SWEEP_STATUS_COLOR_PALETTE: List<String> = listOf(
    "#4CAF50", // green (SAFE default)
    "#FFC107", // amber (SOON default)
    "#F44336", // red (IMMINENT default)
    "#2196F3", // blue (ACTIVE default)
    "#9C27B0", // purple
    "#FF9800", // orange
    "#009688", // teal
    "#795548", // brown
    "#607D8B", // blue-grey
    "#E91E63"  // pink
)

/** A small "X is currently enabled/granted" status line, used by the three permission/battery
 *  checks in BluetoothBackgroundSection — a checkmark icon rather than a "✓ " text prefix. */
@Composable
private fun StatusOkRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusColorRow(label: String, selectedHex: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SWEEP_STATUS_COLOR_PALETTE.forEach { hex ->
                val selected = hex.equals(selectedHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(hex)))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.LightGray,
                            shape = CircleShape
                        )
                        .clickable { onSelect(hex) }
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/**
 * Hides a setting's own explanatory paragraph behind a small tappable "Details" line,
 * expanding it in place when tapped. Added because every section's always-visible
 * explainer text (edge cases, "why this exists," Android-specific caveats) made each
 * screen feel dense before a person had touched a single control — this keeps that detail
 * available without it being the first thing anyone reads. Each toggle's expanded/collapsed
 * state is local to this composition (not persisted) — it isn't meant to remember which
 * explanations someone had open, just to declutter by default each time the screen opens.
 */
@Composable
private fun DescriptionToggle(text: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        if (expanded) "\u25B4 Hide details" else "\u25BE Details",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
    )
    if (expanded) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
        )
    }
}