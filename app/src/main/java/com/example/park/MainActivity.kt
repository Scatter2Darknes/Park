package com.example.park

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

enum class Screen { Map, ManageCars, Settings, SavedLocations }

class MainActivity : ComponentActivity() {
    // Held at the Activity level (not inside setContent's remember blocks) so onNewIntent —
    // which fires outside Compose's world — can push a new intent in and have the already-
    // running Composable pick it up, covering both cold-start (read via onCreate's intent)
    // and warm-start (tapping a notification while the app is already open) the same way.
    private var pendingIntentExtras by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntentExtras = intent

        setContent {
            val context = LocalContext.current
            val settingsRepo = remember { SettingsRepository(context) }

            // Read synchronously (not via LaunchedEffect) specifically so this is known
            // before any of the rest of this function's composables are ever reached — if
            // the crash that produced this log is deterministic (the exact "crashes every
            // launch" scenario this exists to debug), a LaunchedEffect wouldn't run until
            // after the first composition pass already attempted the very code that's about
            // to crash again, which could mean this dialog never gets a turn to actually
            // show before the app dies a second time. Everything below is gated on this
            // being null specifically so a pending, unshown crash log always wins the race.
            var crashLogText by remember { mutableStateOf(readLastCrashLog(context)) }
            if (crashLogText != null) {
                val log = crashLogText!!
                AlertDialog(
                    onDismissRequest = {}, // requires an explicit tap, so it can't be swiped away and lost
                    title = { Text("Park crashed last time") },
                    text = {
                        Box(modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            Text(log, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Park crash log")
                                putExtra(Intent.EXTRA_TEXT, log)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share crash log"))
                        }) { Text("Share") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            clearLastCrashLog(context)
                            crashLogText = null
                        }) { Text("Dismiss") }
                    }
                )
                return@setContent
            }

            // "Color Theme" (renamed from "Map Style") now governs the WHOLE app, not just
            // the map — collectAsState (not a one-shot read) means changing it in Settings
            // takes effect immediately across every screen, not just on next screen mount
            // like most other settings in this app. The map still separately layers its own
            // tunnel-detection override on top of this for tiles specifically.
            val colorThemeMode by settingsRepo.mapStyleMode.collectAsState(initial = SettingsDefaults.MAP_STYLE_MODE)
            val systemInDarkTheme = isSystemInDarkTheme()

            var timeOfDayTick by remember { mutableStateOf(0) }
            LaunchedEffect(colorThemeMode) {
                if (colorThemeMode == "AUTO_TIME") {
                    while (true) {
                        delay(15 * 60_000L)
                        timeOfDayTick++
                    }
                }
            }

            val isDarkTheme = remember(colorThemeMode, systemInDarkTheme, timeOfDayTick) {
                resolveIsDarkColorTheme(colorThemeMode, systemInDarkTheme)
            }

            // Freezes the resolved theme for the duration of Driving Mode — see
            // DrivingModeState's doc comment for why. Deliberately independent of whichever
            // exact cause produces a mid-drive flip; this stops it from ever reaching the
            // screen while driving, regardless of cause.
            val drivingModeActive by DrivingModeState.isActive.collectAsState()
            var frozenIsDarkTheme by remember { mutableStateOf(isDarkTheme) }
            LaunchedEffect(drivingModeActive, isDarkTheme) {
                if (!drivingModeActive) {
                    frozenIsDarkTheme = isDarkTheme
                }
                // While driving: intentionally not updated, holding whatever it was the
                // instant driving mode engaged, however many times isDarkTheme itself changes.
            }
            val effectiveIsDarkTheme = frozenIsDarkTheme

            // enableEdgeToEdge() (above, called once in onCreate) picks the status bar icon
            // color from the SYSTEM's dark/light setting at launch — it has no way to know
            // about this app's own "Color Theme" setting (Day/Night/Automatic-time-of-day),
            // which can resolve to a different value than the system's and can change later
            // without a system-level config change (e.g. AUTO_TIME flipping at 7pm, or the
            // person picking NIGHT while their phone itself is in Light mode). Meanwhile the
            // blank area behind the status bar was never explicitly painted, so it fell back
            // to the raw window background (white) regardless of either theme. Combined, a
            // system-dark phone could show white status bar icons over that still-white
            // blank space — invisible. Driving both the icon color and that background off
            // the same resolved value (effectiveIsDarkTheme) keeps them permanently in sync
            // with each other, independent of what the system itself is doing.
            LaunchedEffect(effectiveIsDarkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                    !effectiveIsDarkTheme
            }

            ParkAppTheme(darkTheme = effectiveIsDarkTheme) {
                var currentScreen by remember { mutableStateOf(Screen.Map) }
                // Set by ManageCarsScreen's "See Location" action, consumed once by MapScreen.
                var pendingMapCenter by remember { mutableStateOf<LatLng?>(null) }
                // Set when a Bluetooth auto-detect notification (ambiguous/no-match case) is
                // tapped, consumed once by MapScreen.
                var pendingAutoDetect by remember { mutableStateOf<PendingAutoDetect?>(null) }
                // Set by SavedLocationsScreen's "Pick from map" option, consumed once by MapScreen.
                var pendingSaveLocationName by remember { mutableStateOf<String?>(null) }
                // Set by the sync dialog's "Configure API key in Settings" (deep-links straight
                // to DATA_SYNC instead of the hub), consumed once by SettingsScreen.
                var pendingSettingsCategory by remember { mutableStateOf<SettingsCategory?>(null) }

                // Extracts whichever extras are present on the current pending intent — either
                // a plain "center the map here" (confident auto-detect, already saved) or a
                // "finish matching this car at this point" (ambiguous auto-detect) request.
                LaunchedEffect(pendingIntentExtras) {
                    val extras = pendingIntentExtras ?: return@LaunchedEffect

                    val centerLat = extras.getDoubleExtra("centerLat", Double.NaN)
                    val centerLng = extras.getDoubleExtra("centerLng", Double.NaN)
                    if (!centerLat.isNaN() && !centerLng.isNaN()) {
                        pendingMapCenter = LatLng(centerLat, centerLng)
                        currentScreen = Screen.Map
                    }

                    val autoDetectCarId = extras.getLongExtra("autoDetectCarId", -1L)
                    val autoDetectLat = extras.getDoubleExtra("autoDetectLat", Double.NaN)
                    val autoDetectLng = extras.getDoubleExtra("autoDetectLng", Double.NaN)
                    if (autoDetectCarId > 0 && !autoDetectLat.isNaN() && !autoDetectLng.isNaN()) {
                        pendingAutoDetect = PendingAutoDetect(autoDetectCarId, LatLng(autoDetectLat, autoDetectLng))
                        currentScreen = Screen.Map
                    }

                    // Tapping a parking-reminder notification's body (not its Snooze/"I moved
                    // my car" action buttons) — resolved at tap time via a fresh DB lookup
                    // rather than baking a lat/lng into the notification back when it was
                    // built, since showReminder's other call sites (the alarm-triggered path,
                    // the immediate-fire path, Settings' test buttons) don't all have a
                    // location on hand anyway, and tap-time is also just more likely to be
                    // accurate if anything about the parked spot changed since scheduling.
                    // Falls back to just opening the Map screen with no specific center if
                    // the car's parked state is somehow already gone by tap time (e.g. a
                    // Bluetooth reconnect auto-unparked it in the meantime) — still more
                    // useful than a notification tap that silently does nothing.
                    val reminderCarId = extras.getLongExtra("reminderCarId", -1L)
                    if (reminderCarId > 0) {
                        val parked = AppDatabase.getInstance(context).parkedStateDao().getForCar(reminderCarId)
                        if (parked != null) {
                            pendingMapCenter = LatLng(
                                parked.exactPinLat ?: parked.parkedLat,
                                parked.exactPinLng ?: parked.parkedLng
                            )
                        }
                        currentScreen = Screen.Map
                    }

                    pendingIntentExtras = null // consumed
                }

                // Street-data sync status, shown above whichever screen is active (not floating
                // on top of it, to avoid colliding with each screen's own top bar/buttons) so
                // it persists across every screen instead of only existing on the map. Rendered
                // once here rather than duplicated per-screen. Suppressed specifically on the
                // Map screen — that screen has its own local sync pill (right on the map,
                // stacked under the priority banner) with the same information, so showing
                // both at once was a redundant duplicate rather than useful reinforcement.
                val isDataFullySynced by StreetDataSyncCenter.isFullySynced.collectAsState()
                val isSyncDialogDismissed by StreetDataSyncCenter.dialogDismissed.collectAsState()

                Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (!isDataFullySynced && currentScreen != Screen.Map) {
                        SyncStatusBar(onClick = { StreetDataSyncCenter.reopenDialog() })
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        // Crossfade rather than a raw `when` swap — screens still fully
                        // dispose/recreate on navigation exactly as before (MapScreen's own
                        // GPS/MapView lifecycle is untouched), this just animates the visual
                        // handoff between them instead of an instant, jarring cut.
                        Crossfade(targetState = currentScreen, label = "screenTransition") { screen ->
                            when (screen) {
                                Screen.Map -> MapScreen(
                                    onNavigateToManageCars = { currentScreen = Screen.ManageCars },
                                    onNavigateToSettings = { currentScreen = Screen.Settings },
                                    onNavigateToSavedLocations = { currentScreen = Screen.SavedLocations },
                                    initialCenter = pendingMapCenter,
                                    onInitialCenterConsumed = { pendingMapCenter = null },
                                    pendingAutoDetect = pendingAutoDetect,
                                    onPendingAutoDetectConsumed = { pendingAutoDetect = null },
                                    pendingSaveLocationName = pendingSaveLocationName,
                                    onPendingSaveLocationConsumed = { pendingSaveLocationName = null },
                                    isDarkTheme = effectiveIsDarkTheme
                                )
                                Screen.ManageCars -> ManageCarsScreen(
                                    onBack = { currentScreen = Screen.Map },
                                    onSeeCarLocation = { point ->
                                        pendingMapCenter = point
                                        currentScreen = Screen.Map
                                    }
                                )
                                Screen.Settings -> SettingsScreen(
                                    onBack = { currentScreen = Screen.Map },
                                    initialCategory = pendingSettingsCategory,
                                    onInitialCategoryConsumed = { pendingSettingsCategory = null }
                                )
                                Screen.SavedLocations -> SavedLocationsScreen(
                                    onBack = { currentScreen = Screen.Map },
                                    onPickFromMap = { name ->
                                        pendingSaveLocationName = name
                                        currentScreen = Screen.Map
                                    }
                                )
                            }
                        }
                    }
                }

                if (!isDataFullySynced && !isSyncDialogDismissed) {
                    SyncStatusDialog(
                        onDismiss = { StreetDataSyncCenter.dismissDialog() },
                        onGoToSettings = {
                            pendingSettingsCategory = SettingsCategory.DATA_SYNC
                            currentScreen = Screen.Settings
                            StreetDataSyncCenter.dismissDialog()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentExtras = intent
    }
}