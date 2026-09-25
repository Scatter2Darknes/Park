package com.example.park

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// Vertical space reserved at the top of the map for the Settings/overflow icons (which sit at
// padding(24.dp) plus their own height), so the parked-cars banner never overlaps them
// regardless of banner content width or the icons' exact rendered size.
private val TOP_BANNER_CLEARANCE = 75.dp

// GPS bearing derived from movement direction is noisy/unreliable below walking speed —
// below this threshold, driving mode holds the last known good heading instead of rotating
// to a spurious one (e.g. stopped at a light, in slow traffic).
private const val BEARING_TRUST_SPEED_MPS = 1.0f

// Rough meters-per-degree-of-latitude, used only to convert the Settings radius (meters,
// user-facing) into the degree-based bounding box loadAndDrawSegments actually uses.
private const val METERS_PER_DEGREE = 111_320.0

// How often onRawLocation is allowed to actually trigger a segment reload while driving —
// a throttle, not a debounce: fixes arrive roughly every DRIVING_MIN_TIME_MS (1s), so a
// debounce (wait for fixes to STOP) would never fire during continuous motion at all. 2.5s
// is frequent enough that the colored streets keep pace with the car at ordinary city
// speeds, without re-querying Room and redrawing every single overlay on every 1s GPS tick.
private const val SEGMENT_RELOAD_THROTTLE_MS = 2500L

@Composable
fun MapScreen(
    onNavigateToManageCars: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSavedLocations: () -> Unit,
    initialCenter: LatLng? = null,
    onInitialCenterConsumed: () -> Unit = {},
    pendingAutoDetect: PendingAutoDetect? = null,
    onPendingAutoDetectConsumed: () -> Unit = {},
    pendingSaveLocationName: String? = null,
    onPendingSaveLocationConsumed: () -> Unit = {},
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    // Android 12+ lets the person grant only "Approximate" (COARSE) location. The map needs the
    // precise GPS fix, so that counts as not-granted here — tracked separately only so the prompt
    // can say so, instead of telling someone who just tapped "Allow" that location is missing.
    var hasApproximateLocationOnly by remember {
        mutableStateOf(
            !hasLocationPermission &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    fun refreshLocationPermission() {
        hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        hasApproximateLocationOnly = !hasLocationPermission &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshLocationPermission() }
    val requestLocationPermission = {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) requestLocationPermission()
    }

    // Re-check whenever the screen comes back to the foreground: the person may have just switched
    // location on in the system's app-settings page (opened by the prompt below), which doesn't
    // report back through the launcher above.
    val locationLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(locationLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLocationPermission()
        }
        locationLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { locationLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // POST_NOTIFICATIONS is no longer auto-requested here at first launch — deferred to
    // Settings (a toggle there now, mirroring the battery/background-location pattern) since
    // Android's own guidance discourages asking before the person has any context for why,
    // and this was stacking a second system permission dialog right on top of the location
    // one and the sync setup dialog on every cold start.
    //
    // The exact-alarm ("Alarms & reminders") access is NOT auto-prompted here either. It used to
    // be, from a LaunchedEffect(Unit) — but MapScreen remounts on every navigation, so someone
    // who declined got thrown back into the system settings page over and over. It's now a Settings
    // row plus the persistent warning banner below, both of which only open that page on a tap.
    // Re-read on every ON_RESUME so the banner disappears as soon as they grant it and come back.
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarmsCompat(context)) }
    // Same idea for notifications: alarms can fire perfectly and Android still drops every notification
    // if they're blocked (app-wide or just the reminders channel), which is total silence — worse than
    // late reminders. Re-read on ON_RESUME (not polled) so the banner clears right after the person
    // allows them in system settings and comes back.
    var reminderHealth by remember { mutableStateOf(currentReminderHealth(context)) }
    val exactAlarmLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(exactAlarmLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = canScheduleExactAlarmsCompat(context)
                reminderHealth = currentReminderHealth(context)
            }
        }
        exactAlarmLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { exactAlarmLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The one-time notification-permission ask at the first manual park (Permissions-banners-plan P1b): the
    // moment the person has just parked is when "Park wants to remind you" makes sense. A short explanation
    // comes first, then Android's own dialog. Asked at most once (SettingsRepository.notificationPermissionAsked).
    var notificationAskVisible by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        reminderHealth = currentReminderHealth(context)
        // A reminder that fell due while notifications were blocked wasn't recorded as delivered; re-arming
        // posts it now (e.g. parking where a sweep starts in 20 minutes).
        if (granted) scope.launch { rearmAllActiveReminders(context) }
    }


    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var locationOverlayRef by remember { mutableStateOf<MyLocationNewOverlay?>(null) }
    var locationProviderRef by remember { mutableStateOf<SingleSourceLocationProvider?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    var selectedSegment by remember { mutableStateOf<StreetSegment?>(null) }
    var tapHighlightJob by remember { mutableStateOf<Job?>(null) }
    // The navigator remembers the steps behind the current one, for "Back" (see ParkingFlowNavigator).
    // Reading/writing parkingFlowState goes through it, so existing assignments record history as-is.
    val parkingFlow = remember { ParkingFlowNavigator() }
    var parkingFlowState by parkingFlow
    var activeParkedCars by remember { mutableStateOf<List<CarWithStatus>>(emptyList()) }
    var parkedBannerExpanded by remember { mutableStateOf(false) }
    // Set from the priority banner's expanded row when a car with an active meter timer is
    // tapped to extend it — see the "Did you add more time?" dialog near the banner.
    var extendMeterTimerCar by remember { mutableStateOf<CarWithStatus?>(null) }
    // Keyed lookup for the Bluetooth connection chip — activeParkedCars only covers currently
    // parked cars, but a BT-connected car (i.e. currently being driven) is by definition not
    // parked, so its name/style has to come from the full car list instead.
    var allCarsById by remember { mutableStateOf<Map<Long, Car>>(emptyMap()) }
    val carLinkState by BluetoothConnectionCenter.linkState.collectAsState()
    val connectedDeviceNames by BluetoothConnectionCenter.connectedDeviceNames.collectAsState()
    // The single car (if any) currently resolved as "actually being driven" via Bluetooth —
    // shared by the current-location icon, the compact status pill, and the connect/disconnect
    // toast, so all three always agree on which car is active.
    val activeCar = (carLinkState as? CarLinkState.Resolved)?.carId?.let { allCarsById[it] }

    // Transient "Connected to X" / "X parked" toast — shown briefly right at the moment of a
    // connect/disconnect event, separate from the persistent (but much smaller) pill below,
    // which is the ongoing "still connected" indicator once the toast has faded.
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var pillExpanded by remember { mutableStateOf(false) }
    var previousActiveCar by remember { mutableStateOf<Car?>(null) }
    // Keyed on the full carLinkState (not just activeCar?.id): Ambiguous also makes
    // activeCar null since it isn't Resolved, but that's not a disconnect — a second car
    // connecting while one was already active used to fire a false "X parked" toast here,
    // even though X was still connected and driving. The ambiguity banner is the correct UI
    // for that transition, so it's explicitly a no-op below.
    LaunchedEffect(carLinkState) {
        when (val state = carLinkState) {
            is CarLinkState.Resolved -> {
                // Not just activeCar?.name here: allCarsById is populated by a separate,
                // async refreshActiveParkedCars() call, which can still be in flight the very
                // first time this fires (e.g. the cold-start Bluetooth resync in ParkApp can
                // resolve carLinkState before that load completes) — activeCar would read as
                // null and this toast printed the literal text "null active". Falling back to
                // a direct DB lookup for just this one car sidesteps the race instead of
                // depending on allCarsById's load having already finished.
                val carName = allCarsById[state.carId]?.name
                    ?: AppDatabase.getInstance(context).carDao().getAll()
                        .firstOrNull { it.id == state.carId }?.name
                    ?: "car"
                toastMessage = "Connected to ${connectedDeviceNames[state.carId] ?: "device"} \u2014 $carName active"
            }
            is CarLinkState.None ->
                if (previousActiveCar != null) toastMessage = "${previousActiveCar?.name} parked"
            is CarLinkState.Ambiguous -> {} // ambiguity banner handles this case instead
        }
        previousActiveCar = activeCar
        if (toastMessage != null) {
            delay(3000)
            toastMessage = null
        }
    }
    var pinDropCallback by remember { mutableStateOf<((GeoPoint) -> Unit)?>(null) }

    // Mode 1 ("I'm Parking Right Now"): rotates the map to align with travel direction and
    // zooms in closer, so the map orients like a driving-nav app while you're searching for
    // a spot. lastKnownHeading persists across low-speed GPS fixes so rotation holds steady
    // rather than snapping to a noisy bearing at a stoplight or in slow traffic.
    // Source of truth is DrivingModeState (app-level), not a local var — this needs to be
    // externally settable (e.g. auto-turning off on a Bluetooth disconnect, from a
    // BroadcastReceiver with no access to this composable's own state) as well as
    // toggle-from-the-map, and a one-way mirror out to DrivingModeState (the previous setup)
    // can't be pushed to from outside. collectAsState keeps this screen in sync with whichever
    // side changes it.
    val drivingModeActive by DrivingModeState.isActive.collectAsState()
    var lastKnownHeading by remember { mutableStateOf(0f) }
    var zoomBeforeDriving by remember { mutableStateOf<Double?>(null) }
    var drivingModeZoom by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_ZOOM) }
    var drivingModeAutoCenter by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_AUTO_CENTER) }
    var drivingModeAutoZoom by remember { mutableStateOf(SettingsDefaults.DRIVING_MODE_AUTO_ZOOM) }
    var defaultMapZoom by remember { mutableStateOf(SettingsDefaults.DEFAULT_MAP_ZOOM) }

    // Bounding-box radius (in degrees) used whenever segments are loaded/drawn around a
    // point — configurable in Settings as meters and converted here, since a degree isn't a
    // meaningful unit for the person setting it.
    var segmentRadiusDegrees by remember { mutableStateOf(SettingsDefaults.MAP_SEGMENT_RADIUS_METERS / METERS_PER_DEGREE) }
    var lastRefreshMillis by remember { mutableStateOf<Long?>(null) }

    // Reloads the full "who's parked where" summary — every car with a live parked state,
    // soonest-sweep-first — rather than tracking a single car. Called after every parking-flow
    // completion so the banner (and its expanded list) stay current.
    //
    // Also enqueues a widget refresh here, piggybacking on the exact same call sites and
    // timing the in-app banner already relies on to stay current — rather than requiring every
    // mutation path to separately remember its own enqueueWidgetRefresh() call (a car-edit
    // save in Manage Cars once missed exactly that). This is a belt-and-suspenders addition on
    // top of the calls already inside saveParkedState/unsubscribeParking/deleteCarCompletely,
    // not a replacement for them — those cover mutations that don't go through MapScreen at
    // all (Manage Cars, Bluetooth). Redundant back-to-back calls (e.g. saveParkedState then
    // this) are harmless: enqueueWidgetRefresh uses ExistingWorkPolicy.REPLACE, so they
    // collapse into whichever one actually runs last rather than racing each other.
    suspend fun refreshActiveParkedCars() {
        activeParkedCars = loadActiveParkedCars(context)
        allCarsById = AppDatabase.getInstance(context).carDao().getAll().associateBy { it.id }
        enqueueWidgetRefresh(context)
    }

    // The one-time "keep checking for street closures in the background?" offer (Tier 2, spec §1),
    // shown as a card on the map (ClosureOfferCard), NOT a dialog: right after two or three routine
    // parking dialogs, another dialog is easy to tap away without reading.
    var closureOfferVisible by remember { mutableStateOf(false) }
    var closureOfferText by remember { mutableStateOf("") }

    /**
     * Every MANUAL park in the flow below ends here, after its save: redraw the parked-car markers
     * and the banner, close the flow, and — the first time only — offer Tier 2 background closure
     * checks. Bluetooth auto-parks never come through here (no screen to show the offer on), so they
     * don't use it up. "Shown" is recorded by the card itself once it is actually on screen (see its
     * LaunchedEffect), so a card that never appeared can't use the offer up.
     */
    suspend fun finishManualPark(carId: Long) {
        mapViewRef?.let { mv -> refreshParkedCarOverlays(mv, context) }
        refreshActiveParkedCars()
        parkingFlowState = ParkingFlowState.Hidden
        val settings = SettingsRepository(context)
        val notificationsGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (shouldAskNotificationPermissionAtPark(android.os.Build.VERSION.SDK_INT, notificationsGranted, settings.notificationPermissionAsked.first())) {
            notificationAskVisible = true
        }
        suspend fun offerWanted() = !settings.closureTier2OfferShown.first() && !settings.closureBackgroundSync.first()
        val wanted = offerWanted()
        android.util.Log.d("ClosureAlert", "Tier 2 offer after manual park: shownBefore=${settings.closureTier2OfferShown.first()} " +
                "backgroundOn=${settings.closureBackgroundSync.first()} -> offer=$wanted")
        if (!wanted) return
        // Let the park settle on screen first, and let the park-time closure check finish so the
        // card's count reflects fresh data rather than whatever was stored before this park.
        kotlinx.coroutines.delay(2_000)
        ClosureCheckCenter.awaitFor(carId, CLOSURE_CHECK_RECEIVER_WAIT_MILLIS)
        if (!offerWanted()) return // decided in Settings meanwhile
        closureOfferText = closureOfferSummaryFor(context, carId)
        closureOfferVisible = true
    }

    /**
     * Every manual park WITH a pin goes through here. A pin more than PIN_FAR_FROM_CURB_METERS from
     * the chosen curb contradicts the chosen street, so the user is asked first (PinFarFromStreet)
     * instead of saving two places that disagree. [checkDistance] false = they chose "Keep both".
     * [offerMeterAfter] keeps DroppingPin's existing meter-timer check for the dropped pin.
     */
    suspend fun parkWithPin(
        carId: Long,
        segment: StreetSegment,
        point: LatLng,
        pin: LatLng,
        offerMeterAfter: Boolean,
        checkDistance: Boolean = true
    ) {
        val distance = pinDistanceFromCurbMeters(segment, pin)
        if (checkDistance && distance > PIN_FAR_FROM_CURB_METERS) {
            parkingFlowState = ParkingFlowState.PinFarFromStreet(carId, segment, point, pin, distance, offerMeterAfter)
            return
        }
        if (offerMeterAfter) {
            // Defer the save to AskingForMeterTimer's own buttons (avoids saving twice).
            val meter = findConfidentMeteredMatch(context, pin)
            if (meter != null) {
                parkingFlowState = ParkingFlowState.AskingForMeterTimer(carId, segment, point, meter, exactPin = pin)
                return
            }
        }
        saveParkedState(context, carId, segment, point, pin.lat, pin.lng)
        finishManualPark(carId)
    }

    // Back / Cancel for every parking-flow step. Leaving a map-tap step also has to undo what it
    // set up: the pin-drop tap hook and the tapped-street highlight.
    fun leaveParkingStep() {
        pinDropCallback = null
        tapHighlightJob?.cancel()
        mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
    }
    fun parkingFlowBack() { leaveParkingStep(); parkingFlow.back() }
    fun parkingFlowCancel() { leaveParkingStep(); parkingFlow.cancel() }
    /** The Back action for the current step, or null on the first step (no Back button then). */
    fun parkingFlowBackOrNull(): (() -> Unit)? = if (parkingFlow.canGoBack) ({ parkingFlowBack() }) else null

    // Settings-backed state. Loaded once on entry — MapScreen fully remounts when
    // navigating back from Settings (a known tradeoff of the current enum-based screen
    // switching), so a fresh load here is sufficient to pick up any changes made there.
    var sweepThresholds by remember { mutableStateOf(SweepThresholds()) }
    var statusColors by remember { mutableStateOf(SweepStatusColors()) }
    var showImminentCountdown by remember { mutableStateOf(SettingsDefaults.SHOW_IMMINENT_COUNTDOWN) }
    var showRppZoneLabels by remember { mutableStateOf(SettingsDefaults.SHOW_RPP_ZONE_LABELS) }
    var showMeterBadges by remember { mutableStateOf(SettingsDefaults.SHOW_METER_BADGES) }
    // The citywide closures layer is on (its switch AND Tier 2): shows the "Closures through …" label.
    var closureMapLayerOn by remember { mutableStateOf(false) }
    // "CAUTIOUS" (default) keeps the original two-dialog confirm-then-pin flow; "SIMPLE"
    // collapses it into QuickParkConfirmDialog for someone who's decided they'd rather trade
    // that extra checkpoint for fewer taps. See ParkingNotificationsSection in SettingsScreen.
    var parkingConfirmationStyle by remember { mutableStateOf(SettingsDefaults.PARKING_CONFIRMATION_STYLE) }

    // Tunnel-style GPS-loss detection (mirrors Google Maps): if fixes stop arriving for a
    // while during driving mode, assume a dark environment and force dark map tiles
    // regardless of what the resolved auto-mode would otherwise pick, since a suspected
    // tunnel means "it's dark right now" independent of the clock or system theme.
    var lastFixTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }
    // Speed of that last fix (null if it had none), and when the map last recovered from a
    // suspected tunnel — the two extra inputs to shouldSuspectTunnel (see TunnelDetection.kt),
    // which is what stops a car idling at a red light from being mistaken for a tunnel.
    var lastFixSpeedMps by remember { mutableStateOf<Float?>(null) }
    var lastRegainAtMs by remember { mutableStateOf<Long?>(null) }
    // The "Auto-dim map in tunnels" setting; loaded with the other map settings below.
    var tunnelAutoDimEnabled by remember { mutableStateOf(SettingsDefaults.TUNNEL_AUTO_DIM_ENABLED) }
    // Tracks the last time onRawLocation actually triggered a segment reload, so that path
    // can be throttled (fire on a fixed cadence) rather than debounced (wait for fixes to
    // stop arriving) — see the onRawLocation hook below for why debounce was wrong here.
    var lastAutoReloadAtMs by remember { mutableStateOf(0L) }
    var suspectedTunnel by remember { mutableStateOf(false) }

    var mapSettingsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sweepThresholds = SettingsRepository(context).sweepThresholdsSnapshot()
        statusColors = SettingsRepository(context).sweepStatusColorsSnapshot()
        drivingModeZoom = SettingsRepository(context).drivingModeZoom.first()
        drivingModeAutoCenter = SettingsRepository(context).drivingModeAutoCenter.first()
        drivingModeAutoZoom = SettingsRepository(context).drivingModeAutoZoom.first()
        defaultMapZoom = SettingsRepository(context).defaultMapZoom.first()
        segmentRadiusDegrees = SettingsRepository(context).mapSegmentRadiusMeters.first() / METERS_PER_DEGREE
        lastRefreshMillis = SettingsRepository(context).lastRefreshMillis.first()
        showImminentCountdown = SettingsRepository(context).showImminentCountdown.first()
        showRppZoneLabels = SettingsRepository(context).showRppZoneLabels.first()
        showMeterBadges = SettingsRepository(context).showMeterBadges.first()
        closureMapLayerOn = SettingsRepository(context).closureMapLayerOn()
        tunnelAutoDimEnabled = SettingsRepository(context).tunnelAutoDimEnabled.first()
        parkingConfirmationStyle = SettingsRepository(context).parkingConfirmationStyle.first()
        // The map (below) isn't created until this flips true. Without this gate, the
        // AndroidView factory — which runs synchronously on first composition, before this
        // suspend block has a chance to finish reading from DataStore — was calling
        // controller.setZoom(defaultMapZoom...) while defaultMapZoom still held its
        // SettingsDefaults fallback rather than what's actually saved in Settings. The
        // factory only ever runs once, so a value corrected here moments later was too late
        // to matter; gating creation on mapSettingsLoaded is what actually fixes it.
        mapSettingsLoaded = true
        onInitialCenterConsumed()
    }

    // Color Theme (Day/Night/Automatic-matches-system/Automatic-time-of-day) is now resolved
    // once in MainActivity and shared with the whole app via isDarkTheme, so every screen
    // agrees — the map layers one more thing on top just for its own tiles: a suspected
    // tunnel forces dark regardless of isDarkTheme, since Settings/Manage Cars shouldn't
    // flip to dark just because you're driving through a tunnel right now.
    val resolvedTileSource = remember(isDarkTheme, suspectedTunnel) {
        if (isDarkTheme || suspectedTunnel) buildStadiaDarkTileSource(context) else buildStadiaTileSource(context)
    }
    // The gear/overflow icons float directly over map tiles with no button background of
    // their own, so their color needs to track the MAP's actual current darkness (theme +
    // tunnel override combined), not just the app theme alone.
    val isMapDark = isDarkTheme || suspectedTunnel
    val floatingIconTint = if (isMapDark) Color.White else Color.Black
    LaunchedEffect(resolvedTileSource) {
        mapViewRef?.let { mv ->
            mv.setTileSource(resolvedTileSource)
            // Force-clear so no stale tile (from the previous style) lingers on screen —
            // without this, a switch could look like it silently failed even when the new
            // source is loading correctly, since osmdroid may not always evict an
            // already-rendered tile just because the source changed.
            mv.tileProvider.clearTileCache()
            mv.invalidate()
        }
    }

    // The location marker's icon (car-specific when linked, generic pin otherwise) is owned
    // entirely by the AndroidView's own `update` block further below — a separate
    // LaunchedEffect(isMapDark) used to also write to this same overlay (rebuilding a
    // generic, theme-tinted dot/arrow whenever isMapDark changed), and since that effect runs
    // AFTER the AndroidView's synchronous `update` callback within the same recomposition, it
    // always won, silently clobbering the correct car-specific icon back to a generic one —
    // reported as "the current-location icon resets to the default dart even while still
    // showing 'currently driving'." Removed rather than merged: neither buildConnectedLocationIcon
    // nor buildDefaultLocationIcon actually needs a dark/light variant (they're colored
    // per-car/pin icons, not a plain dot relying on outline contrast), so there was nothing
    // this effect did that `update` doesn't already fully cover.

    // Single dispatcher for every segment tap. When the manual picker's "select from map"
    // path is active, a tap now goes through the same "is this the correct side?" confirmation
    // (with the same tap-pop halo used for the ordinary detail-sheet tap) before the existing
    // "add an exact pin?" step — picking a street via map had no confirmation at all before,
    // unlike every other selection path (the distance-sorted list, the manual picker) which
    // all show the street/side back to the user before committing to it.
    fun handleSegmentTap(seg: StreetSegment) {
        if (drivingModeActive) return // taps ignored while driving — map gestures shouldn't be mistaken for taps mid-drive
        val state = parkingFlowState
        tapHighlightJob?.cancel()
        mapViewRef?.let { mv -> tapHighlightJob = showTappedSegmentHighlight(mv, seg, isMapDark, scope) }
        if (state is ParkingFlowState.PickingViaMap) {
            parkingFlowState = ParkingFlowState.ConfirmingSide(state.carId, seg, state.originPoint)
        } else {
            selectedSegment = seg
        }
    }

    // A tapped 🚧 closure badge (see drawClosureOverlays): its block's closures, shown in a dialog.
    var tappedClosureBlock by remember { mutableStateOf<ClosureBlock?>(null) }
    fun handleClosureTap(block: ClosureBlock) {
        if (drivingModeActive) return // same rule as segment taps
        if (parkingFlowState != ParkingFlowState.Hidden) return // mid-park, taps belong to the parking flow
        tappedClosureBlock = block
    }

    /** Tapping the banner's closure line: centre the map on that closure (a no-op for "check unavailable"). */
    fun centerMapOnClosure(status: ClosureStatus?) {
        val points = (status as? ClosureStatus.Affected)?.hit?.closure?.points?.takeIf { it.size >= 2 } ?: return
        val mid = midpointAlongPath(points)
        mapViewRef?.controller?.animateTo(GeoPoint(mid.lat, mid.lng))
    }

    // Only meaningful while actively driving with frequent fixes expected — outside driving
    // mode, a GPS gap is just normal idle behavior, not evidence of a tunnel.
    LaunchedEffect(drivingModeActive) {
        if (!drivingModeActive) {
            suspectedTunnel = false
        } else {
            // lastFixTimestamp is only ever updated by driving mode's own fix callback, so
            // without this it still holds whatever it was at the last drive (or at composition)
            // and the very first check below could see a huge stale "gap" the moment driving
            // mode turns on. Start the clock fresh, with no known speed (=> no dimming until a
            // real moving fix arrives).
            lastFixTimestamp = System.currentTimeMillis()
            lastFixSpeedMps = null
            var lastLoggedSuppressedFix = -1L
            while (true) {
                delay(2000L)
                val now = System.currentTimeMillis()
                // Config is rebuilt each pass so the Settings toggle loaded a moment after this
                // effect starts is picked up.
                val config = TunnelConfig(enabled = tunnelAutoDimEnabled)
                val suspect = shouldSuspectTunnel(now, lastFixTimestamp, lastFixSpeedMps, lastRegainAtMs, config)
                val gapMs = now - lastFixTimestamp
                if (suspect) {
                    if (!suspectedTunnel) {
                        android.util.Log.d(
                            "Tunnel",
                            "SUSPECTED: gap=${gapMs}ms lastSpeed=${lastFixSpeedMps}m/s " +
                                    "sinceRegain=${lastRegainAtMs?.let { now - it }}ms -> dimming"
                        )
                    }
                    suspectedTunnel = true
                } else if (tunnelAutoDimEnabled && gapMs > config.gapThresholdMs && lastLoggedSuppressedFix != lastFixTimestamp) {
                    // A silent-GPS gap that did NOT dim, logged once per gap so a wrongly
                    // missed (or wrongly suppressed) tunnel can be diagnosed from Logcat too.
                    lastLoggedSuppressedFix = lastFixTimestamp
                    android.util.Log.d(
                        "Tunnel",
                        "gap=${gapMs}ms lastSpeed=${lastFixSpeedMps}m/s sinceRegain=${lastRegainAtMs?.let { now - it }}ms -> NOT dimming " +
                                "(speed gate or regain cooldown)"
                    )
                }
            }
        }
    }

    // Explains the suspectedTunnel-driven dark flip via the same transient toast used for
    // Bluetooth connect/disconnect — without this, the map going dark on its own (correctly,
    // in response to a real GPS gap) read as an unexplained bug rather than an intentional
    // response to a real condition. Shares toastMessage/the 3s auto-clear with the BT toast
    // above; the two are rare enough to coincide that the minor race (one clearing the
    // other's message slightly early) isn't worth extra guarding against, consistent with
    // how this file already treats this class of purely-cosmetic concern elsewhere.
    var suspectedTunnelToastArmed by remember { mutableStateOf(false) }
    LaunchedEffect(suspectedTunnel) {
        if (!suspectedTunnelToastArmed) {
            // First composition only establishes a baseline — nothing has actually changed
            // yet, so toasting here would say "GPS regained" for a transition that never
            // happened. Every later firing of this effect is a real transition.
            suspectedTunnelToastArmed = true
            return@LaunchedEffect
        }
        toastMessage = if (suspectedTunnel) {
            "GPS lost \u2014 dimming map"
        } else {
            "GPS regained \u2014 map back to normal"
        }
        delay(3000)
        toastMessage = null
    }

    // Checks a safe-tagged Saved Location (LocationStyleDialog's toggle) before falling through
    // to the normal segment-matching flow. Unlike Bluetooth auto-park (where "car disconnected
    // near a safe spot" is already a strong signal, so it's fine to assume), a manual "I'm
    // Parked" tap only proves the PHONE is near the safe location — not that the car is
    // actually sitting in the garage rather than, say, legally parked on the street right in
    // front of it. So a match here asks instead of assuming: ConfirmingSafeLocation. "Yes"
    // saves silently via saveUnmanagedParkedState (still runs its own independent RPP check);
    // "No" falls through to the normal proceedToMatching flow, matching the pre-safe-location
    // "I'm Parked" behavior exactly. Every fresh-point call site below (startParkingFlow's two
    // direct-match branches, and ChoosingCar's onPick/onAddNew once a car is chosen) routes
    // through this instead of calling proceedToMatching directly.
    suspend fun resolveParkingFlow(carId: Long, point: LatLng): ParkingFlowState {
        val safeLocation = findSafeSavedLocation(context, point)
        if (safeLocation != null) {
            return ParkingFlowState.ConfirmingSafeLocation(carId, safeLocation, point)
        }
        return proceedToMatching(context, carId, point)
    }

    // Shared by the GPS-based "I'm Parked" button and "Use saved location" — the only
    // difference between them is where the starting point comes from.
    fun startParkingFlow(point: LatLng) {
        scope.launch {
            val settingsRepo = SettingsRepository(context)
            val alwaysAsk = settingsRepo.alwaysAskCar.first()
            val allCars = AppDatabase.getInstance(context).carDao().getAll()
            val defaultCar = allCars.firstOrNull { it.isDefault }

            parkingFlowState = when {
                allCars.isEmpty() -> ParkingFlowState.ChoosingCar(point)
                alwaysAsk -> ParkingFlowState.ChoosingCar(point)
                allCars.size == 1 -> resolveParkingFlow(allCars.first().id, point)
                defaultCar != null -> resolveParkingFlow(defaultCar.id, point)
                else -> ParkingFlowState.ChoosingCar(point) // multiple cars, none marked default — genuinely ambiguous
            }
        }
    }

    var showOfflineDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var offlineTileTotal by remember { mutableStateOf(0) }
    var offlineTileDownloaded by remember { mutableStateOf<Int?>(null) }
    var offlineStatusMessage by remember { mutableStateOf<String?>(null) }

    // Street-data sync status (totalSegmentCount, isFullySynced, isSyncRunning, statusMessage,
    // isBusy) now lives in StreetDataSyncCenter (app-scoped) rather than as local state here —
    // it used to live in this screen, but that meant the status was invisible on every other
    // screen, and navigating away from Map mid-refresh/import actually cancelled it
    // (rememberCoroutineScope() is cancelled when its composable leaves composition; the
    // shared center's appScope never is). nearbySegmentCount stays local since it's inherently
    // about this screen's current viewport: it distinguishes "nothing has ever synced"
    // (isFullySynced false) from "there's just no DataSF coverage in the area currently being
    // viewed" (nearbySegmentCount is 0 while the sync is otherwise done) — those need
    // different messages, since one means "wait" and the other means "pan somewhere else."
    var nearbySegmentCount by remember { mutableStateOf<Int?>(null) }
    val totalSegmentCount by StreetDataSyncCenter.totalSegmentCount.collectAsState()
    val currentAttemptFetchedCount by StreetDataSyncCenter.currentAttemptFetchedCount.collectAsState()
    val isSyncRunning by StreetDataSyncCenter.isSyncRunning.collectAsState()
    val isFullySynced by StreetDataSyncCenter.isFullySynced.collectAsState()
    val isSyncBusy by StreetDataSyncCenter.isBusy.collectAsState()

    // Drives the pill's one-time "✅ Data successfully loaded" state right after a sync
    // actually finishes — as opposed to showing that message on every ordinary app launch
    // where the data was already synced from a previous session. previousIsFullySynced's
    // initial value is captured fresh from isFullySynced itself, so if this screen mounts
    // already-synced (the normal case), no false "just finished" transition is detected.
    // isFullySynced is otherwise a one-way latch (see StreetDataSyncCenter), so this only
    // re-fires in practice via the debug "Show Sync Setup UI Again" Settings button.
    var showSyncSuccess by remember { mutableStateOf(false) }
    var previousIsFullySynced by remember { mutableStateOf(isFullySynced) }
    LaunchedEffect(isFullySynced) {
        if (isFullySynced && !previousIsFullySynced) {
            showSyncSuccess = true
        }
        previousIsFullySynced = isFullySynced
    }

    // Every other reload trigger on this screen is pan/zoom/GPS-movement-driven — nothing
    // previously redrew the map when a sync itself finished, so freshly-synced segments, RPP
    // zones, or meter badges (a manual "Refresh Data Now" already reports e.g. "+N metered
    // zones" in its status message) wouldn't actually show up until the next one of those
    // happened to fire. Same isSyncBusy TRUE->FALSE transition SettingsScreen already watches
    // to re-read lastRefreshMillis, just triggering a redraw here instead.
    var previousIsSyncBusy by remember { mutableStateOf(isSyncBusy) }
    LaunchedEffect(isSyncBusy) {
        if (!isSyncBusy && previousIsSyncBusy) {
            mapViewRef?.let { mv ->
                nearbySegmentCount = reloadSegmentsAndMarkers(
                    mv, context, mv.mapCenter as GeoPoint,
                    radiusDegrees = segmentRadiusDegrees,
                    isPinDropActive = { pinDropCallback != null },
                    thresholds = sweepThresholds,
                    statusColors = statusColors,
                    showCountdownLabels = showImminentCountdown,
                    showRppZoneLabels = showRppZoneLabels,
                    showMeterBadges = showMeterBadges,
                    onSegmentClick = ::handleSegmentTap, onClosureClick = ::handleClosureTap,
                    locationOverlay = locationOverlayRef
                )
            }
        }
        previousIsSyncBusy = isSyncBusy
    }

    LaunchedEffect(Unit) {
        refreshActiveParkedCars()
    }

    // Live sync: a Bluetooth park/unpark can happen while this exact screen is already open
    // (car parked/unparked in the background), so this can't wait for the next screen mount
    // the way the initial LaunchedEffect(Unit) above does. Collects the same version-bump
    // BluetoothConnectionCenter already exposes for the connection chip, and additionally
    // redraws the map overlays directly — refreshActiveParkedCars() alone only updates the
    // banner/widget data, not the drawn polylines/pins on the live MapView.
    val parkedStateVersion by BluetoothConnectionCenter.parkedStateVersion.collectAsState()
    LaunchedEffect(parkedStateVersion) {
        if (parkedStateVersion != 0L) {
            refreshActiveParkedCars()
            mapViewRef?.let { mv ->
                // Closures too: this also fires when a park-time closure check finishes, which is
                // when the parked car's closure (and fresher layer data) first becomes known.
                refreshClosureOverlays(
                    mv, context, segmentRadiusDegrees,
                    isPinDropActive = { pinDropCallback != null },
                    onClosureClick = ::handleClosureTap,
                    locationOverlay = locationOverlayRef
                )
            }
        }
    }

    // Lets the Bluetooth receivers know this screen (with its live connection toast/pill/ring)
    // is actually the thing on screen right now, so they can skip posting a redundant system
    // notification for the same park/unpark/connect event. ON_RESUME/ON_PAUSE (not just
    // composition enter/dispose) because backgrounding the whole app — home button, screen
    // off — doesn't dispose this composable, only pausing/resuming its lifecycle owner.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> MapScreenVisibility.setVisible(true)
                Lifecycle.Event.ON_PAUSE -> MapScreenVisibility.setVisible(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MapScreenVisibility.setVisible(false) // navigated to another screen entirely
        }
    }

    // A Bluetooth-disconnect auto-detect that couldn't confidently match a segment opens the
    // app here to finish the job — re-running the same matching used everywhere else rather
    // than trusting a stale precomputed result, since segment data may have refreshed since.
    LaunchedEffect(pendingAutoDetect) {
        pendingAutoDetect?.let { pending ->
            parkingFlowState = proceedToMatching(context, pending.carId, pending.point)
            onPendingAutoDetectConsumed()
        }
    }

    // "Pick from map" for a new saved location, triggered from SavedLocationsScreen. Reuses
    // the same pinDropCallback mechanism as the parking flow's manual pin drop — these are
    // mutually exclusive in practice (a person isn't mid-parking-flow and mid-saved-location
    // setup at once), so sharing the single callback slot is fine.
    LaunchedEffect(pendingSaveLocationName) {
        val name = pendingSaveLocationName ?: return@LaunchedEffect
        pinDropCallback = { tappedPoint ->
            scope.launch {
                AppDatabase.getInstance(context).savedLocationDao().insert(
                    SavedLocation(name = name, lat = tappedPoint.latitude, lng = tappedPoint.longitude)
                )
                pinDropCallback = null
                onPendingSaveLocationConsumed()
                onNavigateToSavedLocations() // back to the list so the new entry is visible immediately
            }
        }
    }

    // Owns driving mode's heading/speed hook into the single shared location provider (see
    // SingleSourceLocationProvider) and the map rotation/zoom it drives. Keyed on
    // drivingModeActive so toggling it on tightens the GPS update rate and zooms in, and
    // toggling it off (or leaving the screen) cleanly tears both back down via onDispose —
    // the correct Compose primitive for a callback-based resource, as opposed to
    // LaunchedEffect's coroutine model.
    DisposableEffect(drivingModeActive) {
        if (!drivingModeActive || !hasLocationPermission) {
            onDispose {}
        } else {
            if (drivingModeAutoZoom) {
                zoomBeforeDriving = mapViewRef?.zoomLevelDouble
                mapViewRef?.controller?.setZoom(drivingModeZoom.toDouble())
            }
            if (drivingModeAutoCenter) {
                // Re-engage follow mode explicitly: osmdroid's MyLocationNewOverlay disables
                // it automatically on any manual map drag, so without this, driving mode
                // wouldn't recenter at all if the person had panned around beforehand.
                locationOverlayRef?.enableFollowLocation()
            }

            // Tighten the shared provider's update rate for smooth rotation/following, and
            // hook into its raw fixes for heading — no separate requestLocationUpdates call
            // needed, since this reuses the exact same GPS subscription already feeding the
            // blue-dot overlay.
            locationProviderRef?.setUpdateCriteria(
                SingleSourceLocationProvider.DRIVING_MIN_TIME_MS,
                SingleSourceLocationProvider.DRIVING_MIN_DISTANCE_M
            )
            locationProviderRef?.onRawLocation = { location ->
                val fixAtMs = System.currentTimeMillis()
                if (suspectedTunnel) {
                    // Start the post-regain cooldown (see shouldSuspectTunnel).
                    lastRegainAtMs = fixAtMs
                    android.util.Log.d("Tunnel", "REGAINED after a ${fixAtMs - lastFixTimestamp}ms gap")
                }
                lastFixTimestamp = fixAtMs
                lastFixSpeedMps = if (location.hasSpeed()) location.speed else null
                suspectedTunnel = false

                if (location.hasSpeed() && location.speed > BEARING_TRUST_SPEED_MPS && location.hasBearing()) {
                    lastKnownHeading = location.bearing
                }
                // Rotate to the best-known heading on every fix, even a low-speed one —
                // holding the last GOOD heading (rather than freezing the whole hook) is
                // what makes "hold steady at a stoplight" work correctly.
                mapViewRef?.setMapOrientation(-lastKnownHeading)

                // Drive segment reloading directly off GPS fixes rather than relying solely
                // on MapListener.onScroll: that callback is tied to osmdroid's own pan-
                // gesture handling and isn't guaranteed to fire reliably for the SILENT
                // camera recentering follow-mode performs internally on each fix — which is
                // exactly what was causing segments to stop appearing once the driver moved
                // past the area loaded before driving mode started.
                //
                // THROTTLED, not debounced: this used to cancel+restart a 3s delay on every
                // single fix, same as the onScroll debounce below — but fixes arrive roughly
                // every DRIVING_MIN_TIME_MS (1s) while driving, faster than that 3s window
                // could ever complete, so it never actually fired during continuous motion.
                // Segments only ever caught up once the car stopped moving for a full 3
                // uninterrupted seconds (a red light, parking), which is exactly the
                // reported "segment center lags behind the current location" bug — the
                // reload wasn't slow, it just never ran at all while still driving. Firing
                // immediately once the throttle window has elapsed (rather than waiting out
                // yet another delay once it has) is what actually keeps pace with a moving car.
                val currentPoint = LatLng(location.latitude, location.longitude)
                val now = System.currentTimeMillis()
                if (now - lastAutoReloadAtMs >= SEGMENT_RELOAD_THROTTLE_MS) {
                    lastAutoReloadAtMs = now
                    // Still shares debounceJob with onScroll below — cancelling whichever
                    // fired most recently — so the two triggers stay mutually exclusive and
                    // never run two concurrent reloads against the same MapView.
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        mapViewRef?.let { mv ->
                            nearbySegmentCount = reloadSegmentsAndMarkers(
                                mv, context, GeoPoint(currentPoint.lat, currentPoint.lng),
                                radiusDegrees = segmentRadiusDegrees,
                                isPinDropActive = { pinDropCallback != null },
                                thresholds = sweepThresholds,
                                statusColors = statusColors,
                                showCountdownLabels = showImminentCountdown,
                                showRppZoneLabels = showRppZoneLabels,
                                showMeterBadges = showMeterBadges,
                                onSegmentClick = ::handleSegmentTap, onClosureClick = ::handleClosureTap,
                                locationOverlay = locationOverlayRef
                            )
                        }
                    }
                }
            }

            onDispose {
                locationProviderRef?.onRawLocation = null
                locationProviderRef?.setUpdateCriteria(
                    SingleSourceLocationProvider.IDLE_MIN_TIME_MS,
                    SingleSourceLocationProvider.IDLE_MIN_DISTANCE_M
                )
                mapViewRef?.setMapOrientation(0f)
                zoomBeforeDriving?.let { mapViewRef?.controller?.setZoom(it) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasLocationPermission && mapSettingsLoaded) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(resolvedTileSource)
                        setMultiTouchControls(true)
                        setBuiltInZoomControls(false)
                        controller.setZoom(defaultMapZoom.toDouble())

                        val locationManager = ctx.getSystemService(LocationManager::class.java)
                        val lastLocation = try {
                            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        } catch (e: SecurityException) { null }

                        val startPoint = when {
                            initialCenter != null -> GeoPoint(initialCenter.lat, initialCenter.lng)
                            lastLocation != null -> GeoPoint(lastLocation.latitude, lastLocation.longitude)
                            else -> GeoPoint(37.7749, -122.4194)
                        }
                        controller.setCenter(startPoint)

                        // Single shared GPS subscription for the whole screen (see
                        // SingleSourceLocationProvider) — driving mode taps into this same
                        // provider for heading/speed instead of registering its own separate
                        // location listener, and dials the update rate up/down by context.
                        val locationProvider = SingleSourceLocationProvider(locationManager)
                        locationProviderRef = locationProvider
                        val locationOverlay = MyLocationNewOverlay(locationProvider, this)
                        locationOverlay.enableMyLocation()
                        if (initialCenter == null) {
                            // Only auto-follow live GPS when we're not intentionally viewing a
                            // specific car's parked spot — otherwise the map would immediately
                            // snap away from the location the user navigated here to see. The
                            // recenter FAB still re-enables following at any time.
                            locationOverlay.enableFollowLocation()
                        }
                        overlays.add(locationOverlay)
                        locationOverlayRef = locationOverlay
                        overlays.add(TapCaptureOverlay { pinDropCallback })

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                // While driving, follow-mode recenters the camera on
                                // essentially every GPS fix (~1/s), which fires this callback
                                // just as often. Since this shares debounceJob with
                                // onRawLocation's own throttled reload below, letting this
                                // reschedule its 3s debounce on every one of those fixes could
                                // keep cancelling and re-deferring the reload indefinitely —
                                // events arriving faster than a debounce's own delay is exactly
                                // the failure mode onRawLocation's throttle was written to
                                // avoid (see its comment below), and this listener was racing
                                // it via the shared job. Reported as "drive out of the loaded
                                // radius and the segments never catch up." onRawLocation
                                // already keeps segments centered on the live GPS position
                                // while driving, so this listener has nothing to add then.
                                if (drivingModeActive) return true
                                debounceJob?.cancel()
                                val debounceMs = 400L
                                debounceJob = scope.launch {
                                    delay(debounceMs)
                                    val center = mapCenter as GeoPoint
                                    nearbySegmentCount = reloadSegmentsAndMarkers(
                                        this@apply, ctx, center,
                                        radiusDegrees = segmentRadiusDegrees,
                                        isPinDropActive = { pinDropCallback != null },
                                        thresholds = sweepThresholds,
                                        statusColors = statusColors,
                                        showCountdownLabels = showImminentCountdown,
                                showRppZoneLabels = showRppZoneLabels,
                                showMeterBadges = showMeterBadges,
                                        onSegmentClick = ::handleSegmentTap, onClosureClick = ::handleClosureTap,
                                        locationOverlay = locationOverlayRef
                                    )
                                }
                                return true
                            }
                            override fun onZoom(event: ZoomEvent?): Boolean = false
                        })

                        mapViewRef = this
                        // Single load on startup — previously two concurrent loads raced here,
                        // and the second one (which fed refreshParkedCarOverlays) passed no
                        // onSegmentClick at all, so if it finished last, segment taps silently
                        // stopped working. One call now does both, plus saved-location pins, and
                        // (via locationOverlay below) keeps the live location dot on top instead
                        // of buried under the segment lines this call is about to draw.
                        scope.launch {
                            nearbySegmentCount = reloadSegmentsAndMarkers(
                                this@apply, ctx, startPoint,
                                radiusDegrees = segmentRadiusDegrees,
                                isPinDropActive = { pinDropCallback != null },
                                thresholds = sweepThresholds,
                                statusColors = statusColors,
                                showCountdownLabels = showImminentCountdown,
                                showRppZoneLabels = showRppZoneLabels,
                                showMeterBadges = showMeterBadges,
                                onSegmentClick = ::handleSegmentTap, onClosureClick = ::handleClosureTap,
                                locationOverlay = locationOverlay
                            )
                        }
                    }
                },
                update = { _ ->
                    // Reruns on every recomposition where a captured value below changed —
                    // carLinkState/allCarsById/drivingModeActive are all read here, so a BT
                    // connect/disconnect or a Drive-mode toggle updates the live-location
                    // marker's icon immediately, without recreating the MapView.
                    //
                    // UNVERIFIED: setPersonIcon/setPersonAnchor/setDirectionArrow are real
                    // osmdroid MyLocationNewOverlay APIs per its public docs, but — like the
                    // other osmdroid surface noted in "Unverified-compile-risk areas" — not
                    // something this environment can compile-check. Confirm on the S25 before
                    // trusting this to build as-is.
                    val iconCar = (carLinkState as? CarLinkState.Resolved)?.carId?.let { allCarsById[it] }
                    // Deliberately NOT falling back to the default car here: "default car" is
                    // only a fallback for skipping car-selection in the "I'm Parked" flow — it
                    // says nothing about who's currently driving. Falling back to it here was
                    // the actual cause of the reported desync: the default car's icon showing
                    // at the live GPS position even while nobody was connected to it, at the
                    // same time its (unrelated, possibly stale) parked pin was showing
                    // elsewhere on the map — two markers for one car that visually implied
                    // "you are here AND parked there" simultaneously. With no BT link, this is
                    // null and buildDefaultLocationIcon/buildCarDirectionArrowBitmap(car = null)
                    // draw the plain blue marker/arrow instead — never osmdroid's own stock
                    // person/arrow bitmaps, since both icon slots are always supplied below.
                    locationOverlayRef?.let { overlay ->
                        val personBitmap = if (iconCar != null) {
                            buildConnectedLocationIcon(context, iconCar)
                        } else {
                            buildDefaultLocationIcon(context)
                        }
                        overlay.setPersonIcon(personBitmap)
                        overlay.setPersonAnchor(0.5f, 0.5f)
                        // Always supplied, not just while drivingModeActive: osmdroid decides
                        // for itself (from the GPS bearing) whether to render the person or
                        // direction-arrow bitmap, independently of our own driving-mode flag. If
                        // it ever switches to "arrow" while we hadn't set one, it falls back to
                        // its own bundled default (a plain white dart, round_navigation_white_48)
                        // which has no theme awareness and disappears on a light map.
                        val arrowBitmap = buildCarDirectionArrowBitmap(context, iconCar)
                        overlay.setDirectionArrow(personBitmap, arrowBitmap)
                        overlay.setDirectionAnchor(0.5f, 0.5f)
                    }
                }
            )

            // Top/bottom edge glow in the active car's color — a glance-able reinforcement of
            // "connected via Bluetooth" alongside the pill and toast, visible without reading
            // any text. Driven by animateColorAsState (not a plain conditional) so it fades
            // smoothly in both directions and cross-fades between two colors if the active car
            // itself changes, rather than snapping in/out. Purely decorative — no pointer input
            // modifiers, so it never intercepts map gestures — and placed before the buttons/
            // pill/toast below so it sits under them rather than tinting their own colors.
            val haloColor by animateColorAsState(
                targetValue = activeCar?.let { carComposeColor(it) } ?: Color.Transparent,
                animationSpec = tween(600),
                label = "bluetoothHaloColor"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to haloColor.copy(alpha = haloColor.alpha * 0.35f),
                            0.06f to Color.Transparent,
                            0.94f to Color.Transparent,
                            1f to haloColor.copy(alpha = haloColor.alpha * 0.35f)
                        )
                    )
            )

            // No-DataSF-coverage-here banner. Purely informational (no pointer input), sits
            // above the map but below the buttons/pill/toast below. The "still syncing"
            // indicator that used to live here (plus the full-screen first-sync takeover) has
            // moved to MainActivity as a global dialog/pill visible on every screen, not just
            // this one — this banner stays local since it's inherently about this screen's
            // current viewport, which a global indicator can't express.
            if (nearbySegmentCount == 0 && isFullySynced) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "No street cleaning data here \u2014 try panning toward San Francisco.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // Mode toggle — its own corner, visually separated from the one-shot actions,
            // and colored to signal state: amber/active when on, neutral when off.
            Button(
                onClick = {
                    DrivingModeState.setActive(!drivingModeActive)
                },
                colors = ButtonDefaults.buttonColors(
                    // Theme-driven (tertiary) rather than a literal amber hex, so this still
                    // looks correct under dark mode and Android 12+ dynamic color.
                    containerColor = if (drivingModeActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (drivingModeActive) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp)
            ) {
                Icon(
                    if (drivingModeActive) Icons.Filled.Stop else Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (drivingModeActive) "Stop" else "Drive")
            }

            // Primary action — dead center, the single most-used control in the app, colored
            // green (echoing the map's own SAFE status color) so it reads as "the one that
            // confirms/completes something," distinct from the neutral utility controls.
            Button(
                onClick = {
                    DrivingModeState.setActive(false) // Mode 1 exits directly into Mode 2 on this tap
                    val loc = locationOverlayRef?.myLocation
                    if (loc == null) {
                        android.util.Log.w("Park", "No GPS fix yet")
                        return@Button
                    }
                    startParkingFlow(LatLng(loc.latitude, loc.longitude))
                },
                // Theme-driven (primary) rather than a literal green hex, so this still
                // looks correct under dark mode and Android 12+ dynamic color.
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                // Dimmed and disabled (Material3's default disabled-button treatment) until
                // street data has actually loaded — matching to a segment is meaningless with
                // nothing to match against, and disabling it here is clearer than letting the
                // flow run and land on "no match" for every tap.
                enabled = isFullySynced,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
            ) {
                Text("Parked")
            }

            // Viewport utility — its own corner, opposite the mode toggle, left as the
            // default FAB styling since that's already visually distinct from the two
            // colored Buttons on either side of it.
            FloatingActionButton(
                onClick = {
                    mapViewRef?.let { mv ->
                        locationOverlayRef?.enableFollowLocation()
                        locationOverlayRef?.myLocation?.let { loc -> mv.controller.animateTo(loc) }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp)
            ) { Icon(Icons.Filled.MyLocation, contentDescription = "Recenter on my location") }

            // Compact, persistent "still connected" indicator — deliberately small and
            // silent by default (a colored dot) so it doesn't compete with the toast above for
            // attention; tap to expand into the same "Connected to / Linked to" detail the
            // old always-on chip used to show permanently.
            activeCar?.let { car ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .zIndex(10f)
                        .navigationBarsPadding()
                        .padding(end = 24.dp, bottom = 96.dp)
                ) {
                    Surface(
                        color = carComposeColor(car),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.clickable { pillExpanded = !pillExpanded }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(carIcon(car), fontSize = 14.sp)
                            if (pillExpanded) {
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    connectedDeviceNames[car.id]?.let {
                                        Text("Connected to: $it", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text("Linked to: ${car.name}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = floatingIconTint)
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Cars") },
                        leadingIcon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
                        onClick = { showOverflowMenu = false; onNavigateToManageCars() }
                    )
                    DropdownMenuItem(
                        text = { Text("Saved Locations") },
                        leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
                        onClick = { showOverflowMenu = false; onNavigateToSavedLocations() }
                    )
                    DropdownMenuItem(
                        text = { Text("Offline Maps") },
                        leadingIcon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                        onClick = { showOverflowMenu = false; showOfflineDialog = true }
                    )
                }
            }
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = floatingIconTint)
            }

            // Bluetooth connection status + driving-mode chip, and (when more than one linked
            // car is connected at once) the "did you switch?" confirmation banner. Stacked in
            // a Column above the parked-cars banner below, rather than both anchored
            // independently to TopCenter, so the two never overlap regardless of which are
            // visible at a given moment.
            //
            // zIndex is explicit here (rather than relying on this Column simply being
            // declared after the Drive/Parked/recenter buttons in the code, which happened to
            // draw it on top anyway) so it stays guaranteed to render above the FAB layout
            // even if this block gets moved earlier in the file later.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .padding(top = TOP_BANNER_CLEARANCE, start = 12.dp, end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Exact-alarm warning is the MOST persistent element here — it stays for as long as
                // a parked car has reminders and the permission is missing, and only changes when
                // the user grants it (outside this screen), never on its own — so it goes first,
                // above even the parked-cars banner, and nothing transient can push it around.
                // Its whole purpose is to be seen: without the permission, reminders fall back to
                // inexact alarms that Android may deliver minutes late.
                val hasCarWithReminders = activeParkedCars.any {
                    it.parkedState?.nextSweepAtMillis != null || it.rppDeadline != null
                }
                // Notifications-blocked banner sits ABOVE the exact-alarm one: no visible reminders at
                // all is more severe than late ones. Both can show at once.
                if (!reminderHealth.isHealthy && hasCarWithReminders) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                reminderHealthMessage(reminderHealth),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            TextButton(onClick = { openReminderHealthSettings(context, reminderHealth) }) { Text("Fix") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (exactAlarmPermissionApplies() && !exactAlarmsAllowed && hasCarWithReminders) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Reminders may arrive late — exact alarms are off",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            TextButton(onClick = { openExactAlarmSettings(context) }) { Text("Fix") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Priority (parked-cars) banner goes next and unconditionally occupies its slot
                // whenever there's anything parked — everything else in this Column after it
                // (toast, ambiguity banner) is appended AFTER it, so it never shifts position
                // when those pop in or out. Previously this was last, which meant a toast or
                // ambiguity banner appearing/disappearing pushed this banner up and down the
                // screen — exactly what was reported as unwanted.
                if (activeParkedCars.isNotEmpty()) {
                    val mostUrgent = activeParkedCars.first() // already sorted soonest-first
                    val now = System.currentTimeMillis()

                    Surface(
                        modifier = Modifier
                            .clickable { parkedBannerExpanded = !parkedBannerExpanded },
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CarAvatar(mostUrgent.car, size = 20.dp)
                                Spacer(modifier = Modifier.width(8.dp))

                                val mostUrgentDeadline = mostUrgent.soonestDeadline()
                                // Only when there's truly no risk of any kind (no sweep segment
                                // AND no deadline computed at all \u2014 see saveUnmanagedParkedState)
                                // does this get the distinct "safe" treatment below. A real
                                // segment whose schedule just failed to resolve also has a null
                                // deadline but keeps the existing "?" \u2014 that's a data gap, not a
                                // confirmed safe spot, and shouldn't be relabeled as one.
                                val mostUrgentUnmanaged = mostUrgent.parkedState?.segmentBlockSweepId == null && mostUrgentDeadline == null
                                val countdownText = when {
                                    mostUrgentUnmanaged -> "No cleaning risk"
                                    else -> mostUrgentDeadline?.let { formatCountdown(it.millis - now) } ?: "?"
                                }
                                val mostUrgentKindLabel = when (mostUrgentDeadline?.kind) {
                                    DeadlineKind.RPP -> " \u00b7 RPP limit"
                                    DeadlineKind.METER -> " \u00b7 Meter timer"
                                    DeadlineKind.TOW -> " \u00b7 Tow zone"
                                    else -> ""
                                }
                                Text(
                                    text = "${mostUrgent.car.name} \u2014 $countdownText$mostUrgentKindLabel",
                                    fontWeight = FontWeight.Bold,
                                    color = if (mostUrgentUnmanaged) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
                                )

                                if (activeParkedCars.size > 1) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "(+${activeParkedCars.size - 1} more)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    if (parkedBannerExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (parkedBannerExpanded) "Collapse" else "Expand",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Street closures (not deadlines, so separate from the countdown above).
                            // Collapsed: the single most important closure line across all parked
                            // cars, named when it isn't the car shown above.
                            if (!parkedBannerExpanded) {
                                activeParkedCars
                                    .mapNotNull { c -> closureBannerText(c.closureStatus, now)?.let { Triple(c, it, closureBannerRank(c.closureStatus)) } }
                                    .minByOrNull { it.third }
                                    ?.let { (c, line, _) ->
                                        Text(
                                            // A line about the city's data isn't about one car: no name on it.
                                            if (c.car.id == mostUrgent.car.id || closureLineIsFeedWide(c.closureStatus)) line else "${c.car.name}: $line",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .padding(top = 4.dp)
                                                .clickable { centerMapOnClosure(c.closureStatus) }
                                        )
                                    }
                                // Tow zones: the single most important tow line (in effect now, maybe
                                // nearby, check unavailable, data out of date). The tow DEADLINE itself
                                // is already in the countdown above when it's the soonest.
                                activeParkedCars
                                    .mapNotNull { c -> towBannerText(c.towStatus, now)?.let { Triple(c, it, towBannerRank(c.towStatus)) } }
                                    .minByOrNull { it.third }
                                    ?.let { (c, line, _) ->
                                        Text(
                                            if (c.car.id == mostUrgent.car.id || towLineIsFeedWide(c.towStatus)) line else "${c.car.name}: $line",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                            }

                            if (parkedBannerExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(4.dp))

                                activeParkedCars.forEach { item ->
                                    val itemDeadline = item.soonestDeadline()
                                    // Same distinction as the collapsed row above: only a car
                                    // with no segment AND no deadline at all gets the "safe"
                                    // wording/color; a real segment with an unresolvable
                                    // schedule keeps the existing ambiguous fallback text.
                                    val itemUnmanaged = item.parkedState?.segmentBlockSweepId == null && itemDeadline == null
                                    val itemNextText = when {
                                        itemUnmanaged -> "Not a street cleaning risk"
                                        else -> itemDeadline?.let {
                                            val dt = java.time.Instant.ofEpochMilli(it.millis)
                                                .atZone(SF_ZONE)
                                            val base = formatSweepDateTime(dt)
                                            when (it.kind) {
                                                DeadlineKind.RPP -> "RPP limit: $base"
                                                DeadlineKind.METER -> "Meter timer: $base"
                                                DeadlineKind.TOW -> "Tow-away zone: $base"
                                                DeadlineKind.SWEEP -> base
                                            }
                                        } ?: "No cleaning schedule found"
                                    }
                                    val itemCountdown = if (itemUnmanaged) "—" else itemDeadline?.let { formatCountdown(it.millis - now) } ?: "?"
                                    val itemTextColor = if (itemUnmanaged) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                item.parkedState?.let { parked ->
                                                    scope.launch {
                                                        val point = resolveCarLocation(context, parked)
                                                        mapViewRef?.controller?.animateTo(GeoPoint(point.lat, point.lng))
                                                    }
                                                }
                                                parkedBannerExpanded = false
                                            }
                                            .padding(vertical = 6.dp)
                                    ) {
                                        CarAvatar(item.car, size = 16.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.car.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(itemNextText, style = MaterialTheme.typography.bodySmall, color = itemTextColor)
                                            // Lines about the city's data are shown once below the list, not per car.
                                            closureBannerText(item.closureStatus, now)
                                                ?.takeUnless { closureLineIsFeedWide(item.closureStatus) }
                                                ?.let {
                                                    Text(
                                                        it,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.clickable {
                                                            centerMapOnClosure(item.closureStatus)
                                                            parkedBannerExpanded = false
                                                        }
                                                    )
                                                }
                                            towBannerText(item.towStatus, now)
                                                ?.takeUnless { towLineIsFeedWide(item.towStatus) }
                                                ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        }
                                        Text(
                                            itemCountdown,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = itemTextColor
                                        )
                                        // Only for an active meter timer specifically — checked
                                        // directly against the stored field rather than
                                        // itemDeadline.kind, since a car can have a meter timer
                                        // running even when a sooner sweep/RPP deadline is what's
                                        // actually shown as itemNextText/itemCountdown above.
                                        if (item.parkedState?.meterTimerAtMillis != null) {
                                            TextButton(onClick = {
                                                extendMeterTimerCar = item
                                                parkedBannerExpanded = false
                                            }) { Text("+ time") }
                                        }
                                    }
                                }
                                // The city-data lines, once for all cars (see BannerLines.kt).
                                feedWideBannerLines(activeParkedCars, now).forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                extendMeterTimerCar?.let { car ->
                    var extraMinutesText by remember(car) { mutableStateOf("") }
                    // true = add minutes onto the existing deadline (topped up the meter);
                    // false = replace it with a fresh "N minutes from now" (re-typed a new
                    // reading, or just wrong the first time).
                    var addMode by remember(car) { mutableStateOf(true) }
                    AlertDialog(
                        onDismissRequest = { extendMeterTimerCar = null },
                        title = { Text("Update meter timer for ${car.car.name}") },
                        text = {
                            Column {
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = addMode,
                                        onClick = { addMode = true },
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                    ) { Text("Add minutes") }
                                    SegmentedButton(
                                        selected = !addMode,
                                        onClick = { addMode = false },
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                    ) { Text("Set new time") }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = extraMinutesText,
                                    onValueChange = { extraMinutesText = it.filter(Char::isDigit) },
                                    label = { Text(if (addMode) "Additional minutes" else "Minutes from now") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val minutes = extraMinutesText.toIntOrNull()
                                if (minutes != null && minutes > 0) {
                                    scope.launch {
                                        val now = System.currentTimeMillis()
                                        // addMode extends from whichever is later — the existing
                                        // deadline, or now — so a timer that already lapsed
                                        // before this got updated doesn't schedule the new one
                                        // further in the past. Setting a new time always counts
                                        // from now instead, ignoring whatever was there before.
                                        val base = if (addMode) {
                                            maxOf(car.parkedState?.meterTimerAtMillis ?: now, now)
                                        } else {
                                            now
                                        }
                                        scheduleMeterTimer(context, car.car.id, car.car.name, "the meter", base + minutes * 60_000L)
                                        refreshActiveParkedCars()
                                    }
                                }
                                extendMeterTimerCar = null
                            }) { Text(if (addMode) "Add" else "Set") }
                        },
                        dismissButton = {
                            TextButton(onClick = { extendMeterTimerCar = null }) { Text("Cancel") }
                        }
                    )
                }

                // Sync-status pill comes next, right below the priority banner — a dedicated,
                // obvious call-to-action right on the map, separate from the small global
                // status bar (MainActivity), specifically for a first-time user who hasn't
                // necessarily noticed or understood that bar yet. Living in this Column
                // (rather than its own independently-aligned Surface) means it stacks below
                // the priority banner instead of overlapping it, and never shifts the
                // priority banner's own position when it appears/disappears.
                if (!isFullySynced) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                when {
                                    // currentAttemptFetchedCount (not totalSegmentCount) while a
                                    // sync is actively in flight — a re-sync of an already-
                                    // populated table can leave totalSegmentCount looking frozen
                                    // for a while (insertAll is a REPLACE, so re-walking rows that
                                    // already exist doesn't move the DB's total), so this shows
                                    // the honest "what's actually happening right now" number,
                                    // including visibly resetting to near-zero on a fresh attempt
                                    // rather than silently displaying a stale total.
                                    currentAttemptFetchedCount != null ->
                                        "${"%,d".format(currentAttemptFetchedCount)} segments fetched this sync\u2026"
                                    (totalSegmentCount ?: 0) > 0 ->
                                        "${"%,d".format(totalSegmentCount)} segments loaded so far"
                                    isSyncRunning -> "Syncing street data\u2026"
                                    else -> "Street data not loaded yet"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row {
                                TextButton(
                                    onClick = { StreetDataSyncCenter.triggerManualRefresh() },
                                    enabled = !isSyncBusy
                                ) {
                                    Text(if (isSyncBusy) "\u2026" else "Sync Now")
                                }
                                TextButton(onClick = { StreetDataSyncCenter.reopenDialog() }) {
                                    Text("Details")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else if (showSyncSuccess) {
                    // Replaces the pill above the instant isFullySynced flips true, rather
                    // than the whole thing just vanishing with no acknowledgment — stays
                    // until explicitly tapped away, since a completion that auto-dismissed
                    // itself risked never being seen at all if it happened while the person
                    // was looking elsewhere on the map.
                    Surface(
                        modifier = Modifier.clickable { showSyncSuccess = false },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "\u2705 Data successfully loaded",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "\u2715",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Ambiguity banner comes next — it persists until the user resolves it, so it
                // gets a stable slot too, same reasoning as the priority banner above. The
                // toast goes last since it's the most transient of the three (auto-clears
                // after 3s) — putting it last means its appearing/disappearing never shifts
                // anything above it, including this banner.
                val ambiguous = carLinkState as? CarLinkState.Ambiguous
                if (ambiguous != null && BluetoothConnectionCenter.shouldShowAmbiguityBanner(ambiguous)) {
                    val suggested = allCarsById[ambiguous.suggestedCarId]
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                "Multiple cars connected: ${ambiguous.connectedCarIds.mapNotNull { allCarsById[it]?.name }.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Did you switch cars?",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row {
                                TextButton(onClick = {
                                    BluetoothConnectionCenter.confirmActive(ambiguous.suggestedCarId)
                                }) {
                                    Text("Yes, ${suggested?.name ?: "switch"}")
                                }
                                TextButton(onClick = { BluetoothConnectionCenter.dismissAmbiguity() }) {
                                    Text("No")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                toastMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            } // closes the BT-chip/ambiguity/parked-banner Column opened above
        } else if (!hasLocationPermission) {
            // Was a single line of text with nothing else on screen — the Settings gear and every
            // button lived inside the branch above — which read as a frozen, black screen.
            LocationPermissionRequired(
                approximateOnly = hasApproximateLocationOnly,
                onRequestPermission = { requestLocationPermission() },
                // Reuses the existing helper: it just opens this app's page in system Settings.
                onOpenAppSettings = { openAppSettingsForBackgroundLocation(context) },
                onOpenSettingsScreen = onNavigateToSettings
            )
        }
        // else: permission is granted but the map settings are still loading (a few milliseconds).
        // Nothing to show yet — this branch used to flash "Location permission is needed" here.

        // Everything below was previously placed AFTER this Box's closing brace, which meant
        // `Modifier.align(...)` inside the DroppingPin banner had no BoxScope receiver to
        // resolve against — that was the "unresolved reference 'align'" error. Moving the
        // Box's closing brace down here (to the end of the composable) fixes it, and is
        // harmless for the dialogs/bottom sheet since those render into their own window
        // regardless of their parent's scope.
        selectedSegment?.let { segment ->
            SegmentDetailSheet(
                segment = segment,
                activeCar = activeCar,
                onDismiss = {
                    selectedSegment = null
                    tapHighlightJob?.cancel()
                    mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
                },
                onOverrideChanged = {
                    // Force an immediate redraw so the corrected color/schedule shows right
                    // away, rather than waiting for the next pan-triggered reload.
                    //
                    // The parked-cars banner (and widget) show each car's stored deadline, which
                    // the sheet has just recomputed from the corrected schedule — reload them too.
                    scope.launch { refreshActiveParkedCars() }
                    mapViewRef?.let { mv ->
                        scope.launch {
                            reloadSegmentsAndMarkers(
                                mv, context, mv.mapCenter as GeoPoint,
                                radiusDegrees = segmentRadiusDegrees,
                                isPinDropActive = { pinDropCallback != null },
                                thresholds = sweepThresholds,
                                statusColors = statusColors,
                                showCountdownLabels = showImminentCountdown,
                                showRppZoneLabels = showRppZoneLabels,
                                showMeterBadges = showMeterBadges,
                                onSegmentClick = ::handleSegmentTap, onClosureClick = ::handleClosureTap,
                                locationOverlay = locationOverlayRef
                            )
                        }
                    }
                }
            )
        }

        if (showOfflineDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (offlineTileDownloaded == null) showOfflineDialog = false
                    // else: a download is in progress — ignore outside taps rather than
                    // abandoning it silently; the Close button is the explicit way out.
                },
                title = { Text("Download offline tiles") },
                text = {
                    Column {
                        Text(
                            "Downloads map tiles for the area currently on screen, across a " +
                                    "few zoom levels, so this area loads without a network " +
                                    "connection later."
                        )
                        offlineTileDownloaded?.let { downloaded ->
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { if (offlineTileTotal > 0) downloaded.toFloat() / offlineTileTotal else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("$downloaded / $offlineTileTotal tiles")
                        }
                        offlineStatusMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = offlineTileDownloaded == null,
                        onClick = {
                            mapViewRef?.let { mv ->
                                offlineStatusMessage = null
                                downloadOfflineTiles(
                                    context = context,
                                    mapView = mv,
                                    onEstimate = { count ->
                                        offlineTileTotal = count
                                        offlineTileDownloaded = 0
                                    },
                                    onProgress = { downloaded, total ->
                                        offlineTileDownloaded = downloaded
                                        offlineTileTotal = total
                                    },
                                    onComplete = {
                                        offlineStatusMessage = "Download complete."
                                        offlineTileDownloaded = null
                                    },
                                    onError = { errors ->
                                        offlineStatusMessage = "Finished with $errors error(s)."
                                        offlineTileDownloaded = null
                                    }
                                )
                            }
                        }
                    ) { Text("Download") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showOfflineDialog = false
                        offlineTileDownloaded = null
                        offlineStatusMessage = null
                    }) { Text("Close") }
                }
            )
        }

        pendingSaveLocationName?.let { name ->
            MapInstructionBanner(text = "Tap the map to save \"$name\"")
            BottomCancelPill(onCancel = {
                pinDropCallback = null
                onPendingSaveLocationConsumed()
            })
        }
        if (closureOfferVisible) {
            // Runs once when the card enters the screen: only now is the offer used up.
            LaunchedEffect(Unit) {
                SettingsRepository(context).setClosureTier2OfferShown()
                android.util.Log.d("ClosureAlert", "Tier 2 offer shown (recorded; it won't appear again)")
            }
        }
        tappedClosureBlock?.let { block ->
            val now = System.currentTimeMillis()
            AlertDialog(
                onDismissRequest = { tappedClosureBlock = null },
                title = { Text("🚧 Street closure") },
                text = {
                    Column {
                        closureDetailLines(block, now).forEachIndexed { i, line ->
                            Text(
                                line,
                                style = if (i == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (i == 0) FontWeight.Bold else null
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            closureDetailNote(block),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { tappedClosureBlock = null }) { Text("Close") } }
            )
        }
        // The layer's horizon, so an empty map reads as "none this week", not "none ever" (spec §4).
        if (closureMapLayerOn && parkingFlowState == ParkingFlowState.Hidden) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 96.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                // Explicit: Surface picks a text colour only for exact theme colours, and the .copy(alpha)
                // above isn't one, so the label fell back to black — unreadable on the dark theme's pill.
                // onSurface is white in dark mode and near-black in light mode.
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 2.dp
            ) {
                Text(
                    "🚧 " + closureHorizonLabel(System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        ClosureOfferCard(
            visible = closureOfferVisible,
            summary = closureOfferText,
            onTurnOn = {
                closureOfferVisible = false
                scope.launch {
                    SettingsRepository(context).setClosureBackgroundSync(true)
                    applyClosureSyncSchedule(context, androidx.work.ExistingPeriodicWorkPolicy.REPLACE)
                }
            },
            onNotNow = { closureOfferVisible = false }
        )
        if (notificationAskVisible) {
            // Any way out of this counts as "asked": Park never raises it again (the red banner and Settings remain).
            fun closeAsked() {
                notificationAskVisible = false
                scope.launch { SettingsRepository(context).setNotificationPermissionAsked() }
            }
            AlertDialog(
                onDismissRequest = { closeAsked() },
                title = { Text("Get parking reminders?") },
                text = {
                    Text(
                        "Park reminds you before street cleaning, a permit time limit or a tow-away zone " +
                                "where you just parked. Android needs your OK to show those reminders."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        closeAsked()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("Allow reminders") }
                },
                dismissButton = { TextButton(onClick = { closeAsked() }) { Text("Not now") } }
            )
        }
        when (val state = parkingFlowState) {
            is ParkingFlowState.ChoosingCar -> {
                var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
                LaunchedEffect(state) {
                    cars = AppDatabase.getInstance(context).carDao().getAll()
                }
                CarSelectionDialog(
                    cars = cars,
                    onPick = { car ->
                        scope.launch { parkingFlowState = resolveParkingFlow(car.id, state.point) }
                    },
                    onAddNew = { name ->
                        scope.launch {
                            val newId = AppDatabase.getInstance(context).carDao().insert(Car(name = name))
                            parkingFlowState = resolveParkingFlow(newId, state.point)
                        }
                    },
                    onDismiss = { parkingFlowCancel() }
                )
            }
            is ParkingFlowState.Confirming -> if (parkingConfirmationStyle == "SIMPLE") {
                QuickParkConfirmDialog(
                    segment = state.match.segment,
                    rejectLabel = "No, pick manually",
                    onConfirm = { dropPin ->
                        scope.launch {
                            if (dropPin) {
                                parkWithPin(state.carId, state.match.segment, state.point, pin = state.point, offerMeterAfter = false)
                            } else {
                                saveParkedState(context, state.carId, state.match.segment, state.point)
                                finishManualPark(state.carId)
                            }
                        }
                    },
                    onReject = {
                        scope.launch {
                            val matches = findNearbySegmentMatches(context, state.point)
                            parkingFlowState = ParkingFlowState.PickingManually(state.carId, matches, state.point)
                        }
                    },
                    onBack = parkingFlowBackOrNull(),
                    onCancel = { parkingFlowCancel() }
                )
            } else {
                ParkingConfirmationDialog(
                    match = state.match,
                    onConfirm = {
                        parkingFlowState = ParkingFlowState.AskingForPin(state.carId, state.match.segment, state.point)
                    },
                    onReject = {
                        scope.launch {
                            val matches = findNearbySegmentMatches(context, state.point)
                            parkingFlowState = ParkingFlowState.PickingManually(state.carId, matches, state.point)
                        }
                    },
                    onBack = parkingFlowBackOrNull(),
                    onCancel = { parkingFlowCancel() }
                )
            }
            is ParkingFlowState.ConfirmingSide -> if (parkingConfirmationStyle == "SIMPLE") {
                QuickParkConfirmDialog(
                    segment = state.segment,
                    rejectLabel = "No, tap again",
                    onConfirm = { dropPin ->
                        tapHighlightJob?.cancel()
                        mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
                        scope.launch {
                            if (dropPin) {
                                // The tapped street can be anywhere on the map; the pin is the phone's location.
                                parkWithPin(state.carId, state.segment, state.point, pin = state.point, offerMeterAfter = false)
                            } else {
                                saveParkedState(context, state.carId, state.segment, state.point)
                                finishManualPark(state.carId)
                            }
                        }
                    },
                    onReject = {
                        tapHighlightJob?.cancel()
                        mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
                        parkingFlowState = ParkingFlowState.PickingViaMap(state.carId, state.point)
                    },
                    onBack = parkingFlowBackOrNull(),
                    onCancel = { parkingFlowCancel() }
                )
            } else {
                ConfirmSideDialog(
                    segment = state.segment,
                    onConfirm = {
                        tapHighlightJob?.cancel()
                        mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
                        parkingFlowState = ParkingFlowState.AskingForPin(state.carId, state.segment, state.point)
                    },
                    onReject = {
                        // Wrong side — clear the halo and drop back into "tap a street" rather than
                        // all the way out of the flow, so a mis-tap costs one tap to correct, not a
                        // full restart.
                        tapHighlightJob?.cancel()
                        mapViewRef?.let { mv -> clearTappedSegmentHighlight(mv) }
                        parkingFlowState = ParkingFlowState.PickingViaMap(state.carId, state.point)
                    },
                    onBack = parkingFlowBackOrNull(),
                    onCancel = { parkingFlowCancel() }
                )
            }
            is ParkingFlowState.AskingForPin -> {
                // Checked here (rather than gating the dialog's shape entirely) so the extra
                // option only appears once a confident, currently-enforced MeteredZone match is
                // actually known for this point — see findConfidentMeteredMatch.
                var meterMatch by remember(state) { mutableStateOf<MeteredZone?>(null) }
                LaunchedEffect(state) {
                    meterMatch = findConfidentMeteredMatch(context, state.point)
                }
                AlertDialog(
                    onDismissRequest = { parkingFlowCancel() }, // tapping outside cancels, like every flow step
                    title = { Text("Add an exact pin?") },
                    text = {
                        Column {
                            Text("${state.segment.corridor} will be highlighted either way.")
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                scope.launch {
                                    parkWithPin(state.carId, state.segment, state.point, pin = state.point, offerMeterAfter = false)
                                }
                            }) { Text("Yes, pin my current location") }
                            TextButton(onClick = {
                                parkingFlowState = ParkingFlowState.DroppingPin(state.carId, state.segment, state.point)
                            }) { Text("Drop pin manually on map") }
                            TextButton(onClick = {
                                scope.launch {
                                    saveParkedState(context, state.carId, state.segment, state.point)
                                    finishManualPark(state.carId)
                                }
                            }) { Text("No, just highlight street") }
                            meterMatch?.let { meter ->
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = {
                                    parkingFlowState = ParkingFlowState.AskingForMeterTimer(state.carId, state.segment, state.point, meter)
                                }) { Text("Set a meter timer?") }
                            }
                            FlowNavRow(parkingFlowBackOrNull(), onCancel = { parkingFlowCancel() })
                        }
                    },
                    confirmButton = {},
                    dismissButton = {}
                )
            }
            is ParkingFlowState.AskingForMeterTimer -> {
                var customMinutesText by remember(state) { mutableStateOf("") }

                suspend fun finishWithMeterTimer(minutes: Int?) {
                    saveParkedState(context, state.carId, state.segment, state.point, state.exactPin?.lat, state.exactPin?.lng)
                    if (minutes != null && minutes > 0) {
                        val carName = AppDatabase.getInstance(context).carDao().getAll()
                            .firstOrNull { it.id == state.carId }?.name ?: "Your car"
                        val meterLabel = state.meter.streetName?.let { "the meter on $it" } ?: "the meter"
                        scheduleMeterTimer(context, state.carId, carName, meterLabel, System.currentTimeMillis() + minutes * 60_000L)
                    }
                    finishManualPark(state.carId)
                }

                AlertDialog(
                    onDismissRequest = { parkingFlowCancel() }, // tapping outside cancels (nothing saved yet)
                    title = { Text("Set a meter timer?") },
                    text = {
                        Column {
                            Text(
                                "Not tracking meter payment — just a reminder for whenever you plan to move by." +
                                        (state.meter.timeLimitMinutes?.let { " Posted limit here: $it min." } ?: "")
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            state.meter.timeLimitMinutes?.let { limit ->
                                TextButton(onClick = { scope.launch { finishWithMeterTimer(limit) } }) {
                                    Text("In $limit min (posted limit)")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customMinutesText,
                                    onValueChange = { customMinutesText = it.filter(Char::isDigit) },
                                    label = { Text("Custom — minutes from now") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { scope.launch { finishWithMeterTimer(customMinutesText.toIntOrNull()) } }) {
                                    Text("Set")
                                }
                            }
                            FlowNavRow(parkingFlowBackOrNull(), onCancel = { parkingFlowCancel() })
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { scope.launch { finishWithMeterTimer(null) } }) { Text("Skip — just park") }
                    }
                )
            }
            is ParkingFlowState.DroppingPin -> {
                LaunchedEffect(state) {
                    pinDropCallback = { tappedPoint ->
                        scope.launch {
                            pinDropCallback = null
                            val droppedPoint = LatLng(tappedPoint.latitude, tappedPoint.longitude)
                            // First: is the dropped pin anywhere near the chosen street? Then the
                            // meter check, re-run against the DROPPED pin, not state.originalPoint —
                            // exactly the case AskingForPin's own one-time meter check (run against
                            // the original, possibly-imprecise GPS point) can miss. Both in parkWithPin.
                            parkWithPin(state.carId, state.segment, state.originalPoint, pin = droppedPoint, offerMeterAfter = true)
                        }
                    }
                }
                MapInstructionBanner(text = "Tap the map to drop your pin")
                BottomCancelPill(onCancel = { parkingFlowCancel() }, onBack = parkingFlowBackOrNull())
            }
            is ParkingFlowState.PinFarFromStreet -> AlertDialog(
                onDismissRequest = { parkingFlowCancel() },
                title = { Text("Pin is far from your street") },
                text = {
                    Column {
                        Text(
                            "Your pin is ${formatDistanceMeters(state.distanceMeters)} from ${state.segment.corridor}, " +
                                    "the street you picked. Reminders follow the street."
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            parkingFlowState = ParkingFlowState.DroppingPin(state.carId, state.segment, state.point)
                        }) { Text("Drop the pin again") }
                        TextButton(onClick = {
                            scope.launch {
                                saveParkedState(context, state.carId, state.segment, state.point)
                                finishManualPark(state.carId)
                            }
                        }) { Text("Park without a pin") }
                        TextButton(onClick = {
                            scope.launch {
                                parkWithPin(state.carId, state.segment, state.point, state.pin, state.offerMeterAfter, checkDistance = false)
                            }
                        }) { Text("Keep both") }
                        FlowNavRow(parkingFlowBackOrNull(), onCancel = { parkingFlowCancel() })
                    }
                },
                confirmButton = {}
            )
            is ParkingFlowState.PickingManually -> ManualSegmentPicker(
                candidates = state.candidates,
                onPick = { segment ->
                    // Routes through AskingForPin like every other selection path, so picking
                    // manually still offers the option to drop an exact pin instead of just
                    // highlighting the street.
                    parkingFlowState = ParkingFlowState.AskingForPin(state.carId, segment, state.point)
                },
                onPickFromMap = {
                    parkingFlowState = ParkingFlowState.PickingViaMap(state.carId, state.point)
                },
                onBack = parkingFlowBackOrNull(),
                onDismiss = { parkingFlowCancel() }
            )
            is ParkingFlowState.NoStreetNearby -> AlertDialog(
                onDismissRequest = { parkingFlowCancel() },
                title = { Text("No nearby streets found") },
                text = {
                    Column {
                        Text("We couldn't find any street-cleaning data near this spot — a garage, driveway or private lot, maybe?")
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = {
                            scope.launch {
                                saveUnmanagedParkedState(context, state.carId, state.point)
                                finishManualPark(state.carId)
                            }
                        }) { Text("Not a street cleaning risk spot") }
                        TextButton(onClick = {
                            // The matcher just missed a real nearby street — fall through to
                            // the same "tap a street on the map" escape hatch PickingManually
                            // already offers, rather than assuming this really is unmanaged.
                            parkingFlowState = ParkingFlowState.PickingViaMap(state.carId, state.point)
                        }) { Text("Select from map instead") }
                        FlowNavRow(parkingFlowBackOrNull(), onCancel = { parkingFlowCancel() })
                    }
                },
                confirmButton = {}
            )
            is ParkingFlowState.ConfirmingSafeLocation -> AlertDialog(
                onDismissRequest = { parkingFlowCancel() },
                title = { Text("Park at ${state.location.name}?") },
                text = {
                    Column {
                        Text(
                            "Did you park at “${state.location.name}” and not on a street-cleaning " +
                                    "segment — like a garage or driveway?"
                        )
                        FlowNavRow(parkingFlowBackOrNull(), onCancel = { parkingFlowCancel() })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            saveUnmanagedParkedState(context, state.carId, state.point, viaSafeLocationId = state.location.id)
                            finishManualPark(state.carId)
                        }
                    }) { Text("Yes") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // Not actually in the garage — fall through to the ordinary flow exactly
                        // as if this Saved Location had never matched at all.
                        scope.launch { parkingFlowState = proceedToMatching(context, state.carId, state.point) }
                    }) { Text("No, check the street") }
                }
            )
            is ParkingFlowState.PickingViaMap -> {
                MapInstructionBanner(text = "Tap a street on the map to select it")
                BottomCancelPill(onCancel = { parkingFlowCancel() }, onBack = parkingFlowBackOrNull())
            }
            ParkingFlowState.Hidden -> {}
        }
    }
}

/**
 * A rounded, elevated instruction banner for "tap the map to do X" states — purely
 * informational, no button of its own (see BottomCancelPill for that), so it stays exactly as
 * compact as its text needs, never a big rectangle padded out by a touch target.
 */
@Composable
private fun BoxScope.MapInstructionBanner(text: String) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = TOP_BANNER_CLEARANCE, start = 12.dp, end = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

/**
 * The one-time Tier 2 offer (background street-closure checks), as a card that slides up over the
 * bottom of the map. Deliberately NOT an AlertDialog: it arrives right after the parking dialogs,
 * and one more dialog of the same shape is easy to tap away on reflex. This one looks different
 * (its own colour, an icon, a line about the user's own spot, a filled button) and has no
 * outside-tap dismiss, so it stays until the user picks. Sits at the same height as
 * BottomCancelPill, clear of the "Parked" button.
 */
@Composable
private fun BoxScope.ClosureOfferCard(visible: Boolean, summary: String, onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚧", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Street closures near your car",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Park can keep checking in the background (every $CLOSURE_BACKGROUND_SYNC_HOURS h), so a " +
                            "closure permitted after you park still reaches you, and the week's closures show " +
                            "on the map. Your location is never sent. " +
                            "Change it anytime in Settings → Data & Sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onTurnOn) { Text("Turn on") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onNotNow) { Text("Not now") }
                }
            }
        }
    }
}

/**
 * A small pill, bottom-anchored well clear of the map's own bottom controls (the "Parked"
 * button sits at BottomCenter too, right at the navigation-bar edge — see its own comment),
 * for cancelling whichever map-tap flow is active. Deliberately separate from
 * MapInstructionBanner at the top of the screen: putting a cancel affordance inside/under that
 * banner (an earlier version of this did) either got lost against the instruction text or
 * ballooned the banner into a big rectangle to fit a 48dp touch target — a standalone pill
 * avoids both. [onBack], when given (a parking-flow step with a previous step), adds a second
 * "Back" pill to its left, the map-tap equivalent of a dialog's Back button.
 */
@Composable
private fun BoxScope.BottomCancelPill(onCancel: () -> Unit, onBack: (() -> Unit)? = null) {
    Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 88.dp) // clears the "Parked" button (bottom = 24.dp) stacked below it
    ) {
        if (onBack != null) BottomPill(text = "← Back", icon = null, onClick = onBack)
        BottomPill(text = "Cancel", icon = Icons.Filled.Close, onClick = onCancel)
    }
}

@Composable
private fun BottomPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}