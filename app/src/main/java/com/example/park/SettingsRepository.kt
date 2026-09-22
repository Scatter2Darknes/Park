package com.example.park

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val ALWAYS_ASK_CAR = booleanPreferencesKey("always_ask_car")
    val PARKING_CONFIRMATION_STYLE = stringPreferencesKey("parking_confirmation_style") // "CAUTIOUS" | "SIMPLE"
    val NOTIFICATION_OFFSET_MINUTES = intPreferencesKey("notification_offset_minutes")
    val URGENT_REMINDER_ENABLED = booleanPreferencesKey("urgent_reminder_enabled")
    val URGENT_OFFSET_MINUTES = intPreferencesKey("urgent_offset_minutes")
    val INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES = intPreferencesKey("informational_notification_timeout_minutes") // 0 = never
    val SOON_THRESHOLD_DAYS = floatPreferencesKey("soon_threshold_days")
    val IMMINENT_THRESHOLD_DAYS = floatPreferencesKey("imminent_threshold_days")
    val REFRESH_INTERVAL_HOURS = intPreferencesKey("refresh_interval_hours")
    val LAST_REFRESH_MILLIS = longPreferencesKey("last_refresh_millis")
    val DRIVING_MODE_ZOOM = floatPreferencesKey("driving_mode_zoom")
    val DRIVING_MODE_AUTO_CENTER = booleanPreferencesKey("driving_mode_auto_center")
    val DRIVING_MODE_AUTO_ZOOM = booleanPreferencesKey("driving_mode_auto_zoom")
    val DEFAULT_MAP_ZOOM = floatPreferencesKey("default_map_zoom")
    val MAP_SEGMENT_RADIUS_METERS = intPreferencesKey("map_segment_radius_meters")
    val SAFE_COLOR_HEX = stringPreferencesKey("safe_color_hex")
    val SOON_COLOR_HEX = stringPreferencesKey("soon_color_hex")
    val IMMINENT_COLOR_HEX = stringPreferencesKey("imminent_color_hex")
    val ACTIVE_COLOR_HEX = stringPreferencesKey("active_color_hex")
    val MAP_STYLE_MODE = stringPreferencesKey("map_style_mode") // "DAY" | "NIGHT" | "AUTO"
    val WIFI_ONLY_REFRESH = booleanPreferencesKey("wifi_only_refresh")
    val BLUETOOTH_AUTO_DETECT_ENABLED = booleanPreferencesKey("bluetooth_auto_detect_enabled")
    val BLUETOOTH_AUTO_DROP_PIN = booleanPreferencesKey("bluetooth_auto_drop_pin")
    val BLUETOOTH_AUTO_UNPARK_ON_RECONNECT = booleanPreferencesKey("bluetooth_auto_unpark_on_reconnect")
    val AUTO_STOP_DRIVING_MODE_ON_DISCONNECT = booleanPreferencesKey("auto_stop_driving_mode_on_disconnect")
    val SHOW_IMMINENT_COUNTDOWN = booleanPreferencesKey("show_imminent_countdown")
    val TUNNEL_AUTO_DIM_ENABLED = booleanPreferencesKey("tunnel_auto_dim_enabled")
    val SHOW_RPP_ZONE_LABELS = booleanPreferencesKey("show_rpp_zone_labels")
    val SHOW_METER_BADGES = booleanPreferencesKey("show_meter_badges")
    val TILE_CACHE_MAX_MB = intPreferencesKey("tile_cache_max_mb")
    // "DRIVING:<carId>:<epochMillis>" or "PARKED:<carId>:<epochMillis>" — a short-lived record
    // of the most recent Bluetooth connect/disconnect, read by loadWidgetSummary so the widget
    // can show it briefly even if it's a freshly-spawned process with no access to the
    // in-memory BluetoothConnectionCenter the map screen uses (that object only lives as long
    // as this process does, which a background BT broadcast can't rely on).
    val TRANSIENT_CONNECTION_EVENT = stringPreferencesKey("transient_connection_event")
    // HIDE_REFRESH_BUTTON removed: the manual refresh action now lives permanently in
    // Settings instead of optionally showing on the map, so there's nothing left to hide.
    // Empty string (the default) means "use the developer's built-in key from assets" —
    // see ApiKeys.kt. Only set once someone types their own key into Settings.
    val STADIA_API_KEY_OVERRIDE = stringPreferencesKey("stadia_api_key_override")
    val DATASF_APP_TOKEN_OVERRIDE = stringPreferencesKey("datasf_app_token_override")
}

object SettingsDefaults {
    // CAUTIOUS preserves the app's original behavior (a separate confirm-then-pin step for
    // every match) — someone has to opt into SIMPLE's fewer-taps flow, not the other way
    // around, since CAUTIOUS's extra step exists specifically to catch a wrong street/side
    // before it's saved.
    const val PARKING_CONFIRMATION_STYLE = "CAUTIOUS"
    const val NOTIFICATION_OFFSET_MINUTES = 120 // 2h — matches the old hardcoded offset
    const val URGENT_REMINDER_ENABLED = true
    const val URGENT_OFFSET_MINUTES = 15
    const val INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES = 5
    const val SOON_THRESHOLD_DAYS = 3f
    const val IMMINENT_THRESHOLD_DAYS = 2f
    // 72h (3 days) — these datasets (street sweeping schedules) change rarely, so a daily
    // background refresh was mostly wasted data usage; the Settings slider still goes down to
    // 6h for anyone who wants a tighter interval, and this default doesn't affect a manual
    // "Refresh Data Now" tap or anyone's already-saved preference.
    const val REFRESH_INTERVAL_HOURS = 72
    const val DRIVING_MODE_ZOOM = 19f
    const val DRIVING_MODE_AUTO_CENTER = true
    const val DRIVING_MODE_AUTO_ZOOM = true // preserves prior always-zoom-on-driving-mode behavior
    const val DEFAULT_MAP_ZOOM = 17f // matches the old hardcoded initial browsing zoom
    const val MAP_SEGMENT_RADIUS_METERS = 500 // ~ the old hardcoded 0.005-degree default
    const val SAFE_COLOR_HEX = "#4CAF50"     // matches the old hardcoded SAFE color (green)
    const val SOON_COLOR_HEX = "#FFC107"     // matches the old hardcoded SOON color (amber)
    const val IMMINENT_COLOR_HEX = "#F44336" // matches the old hardcoded IMMINENT color (red)
    const val ACTIVE_COLOR_HEX = "#2196F3"   // matches the old hardcoded ACTIVE color (blue)
    const val MAP_STYLE_MODE = "DAY"         // preserves prior always-light-tiles behavior
    const val WIFI_ONLY_REFRESH = false      // preserves prior any-network behavior
    // Defaults on: preserves existing behavior for anyone who's already linked a car's
    // Bluetooth device. Someone who never plans to use it can turn it off in Settings, which
    // also disables the two manifest-registered ACL receivers at the PackageManager level —
    // see applyBluetoothAutoDetectComponentState — so the OS stops waking this app's process
    // for every Bluetooth connect/disconnect system-wide, not just a linked device's.
    const val BLUETOOTH_AUTO_DETECT_ENABLED = true
    const val BLUETOOTH_AUTO_DROP_PIN = false // preserves prior no-exact-pin auto-detect behavior
    const val BLUETOOTH_AUTO_UNPARK_ON_RECONNECT = true
    // Defaults on: driving mode's whole purpose is heading/zoom/rotation tied to an active
    // drive, which stops being meaningful the moment the linked car's Bluetooth drops —
    // forgetting to tap "Stop" is the more likely failure mode than wanting it to stay on.
    const val AUTO_STOP_DRIVING_MODE_ON_DISCONNECT = true
    const val SHOW_IMMINENT_COUNTDOWN = true
    const val TUNNEL_AUTO_DIM_ENABLED = true
    // Defaults on, unlike SHOW_IMMINENT_COUNTDOWN above — an RPP zone label only ever appears
    // at all while that block's restriction is actively in effect (see activeRppWindowEndMillis),
    // so it's inherently rarer and more directly actionable than the countdown labels, which
    // show for every IMMINENT/ACTIVE sweep segment regardless of what's actually relevant to you.
    const val SHOW_RPP_ZONE_LABELS = true
    // Defaults on, same reasoning as SHOW_RPP_ZONE_LABELS just above — a meter badge only ever
    // appears while that meter is actually enforced right now, so it's inherently rarer and
    // more directly actionable than showing every metered post regardless of hours.
    const val SHOW_METER_BADGES = true
    const val TILE_CACHE_MAX_MB = 200
}

/**
 * Presets shown in the notification-offset picker, in minutes. Extended per feedback to
 * cover "a few days back" rather than capping at 4 hours — some people want a heads-up
 * the night (or two) before, not just hours before. Further extended with finer low-end
 * granularity (5/10 min) and a 1-week option, since the urgent tier's own range (below) can
 * now reach up to 12 hours — this needs enough low values that a short early-reminder
 * setting still leaves room for a meaningfully-smaller urgent one under it.
 */
val NOTIFICATION_OFFSET_PRESETS: List<Pair<Int, String>> = listOf(
    5 to "5 minutes before",
    10 to "10 minutes before",
    15 to "15 minutes before",
    30 to "30 minutes before",
    45 to "45 minutes before",
    60 to "1 hour before",
    90 to "1.5 hours before",
    120 to "2 hours before",
    180 to "3 hours before",
    240 to "4 hours before",
    360 to "6 hours before",
    480 to "8 hours before",
    720 to "12 hours before",
    1440 to "1 day before",
    2880 to "2 days before",
    4320 to "3 days before",
    10080 to "1 week before"
)

/** How long an informational Bluetooth notification (the confident auto-park notice and the
 *  "unparked" notice) stays before removing itself. 0 means never. Sweep/RPP reminders, the
 *  ambiguous "Did X just park?" prompt, and the no-match prompt are NOT governed by this. */
val INFORMATIONAL_TIMEOUT_PRESETS: List<Pair<Int, String>> = listOf(
    1 to "1 minute",
    5 to "5 minutes",
    30 to "30 minutes",
    0 to "Never"
)

/** Presets for the urgent tier. Originally capped at 1 hour on the assumption this only
 *  ever catches someone right before sweeping starts, but that meant anyone with a large
 *  early-reminder offset (e.g. "1 day before") had no urgent option anywhere close to it —
 *  extended up to 12 hours, plus finer sub-5-minute granularity for the opposite case,
 *  someone who wants the persistent alert to hold off until the very last minute. The
 *  Settings UI enforces urgent < early regardless of which values these presets offer.
 */
val URGENT_OFFSET_PRESETS: List<Pair<Int, String>> = listOf(
    1 to "1 minute before",
    2 to "2 minutes before",
    3 to "3 minutes before",
    5 to "5 minutes before",
    10 to "10 minutes before",
    15 to "15 minutes before",
    20 to "20 minutes before",
    30 to "30 minutes before",
    45 to "45 minutes before",
    60 to "1 hour before",
    90 to "1.5 hours before",
    120 to "2 hours before",
    180 to "3 hours before",
    240 to "4 hours before",
    360 to "6 hours before",
    480 to "8 hours before",
    720 to "12 hours before"
)

class SettingsRepository(private val context: Context) {
    val alwaysAskCar: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.ALWAYS_ASK_CAR] ?: false
    }

    suspend fun setAlwaysAskCar(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.ALWAYS_ASK_CAR] = value }
    }

    val parkingConfirmationStyle: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.PARKING_CONFIRMATION_STYLE] ?: SettingsDefaults.PARKING_CONFIRMATION_STYLE
    }

    suspend fun setParkingConfirmationStyle(value: String) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.PARKING_CONFIRMATION_STYLE] = value }
    }

    val notificationOffsetMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.NOTIFICATION_OFFSET_MINUTES] ?: SettingsDefaults.NOTIFICATION_OFFSET_MINUTES
    }

    suspend fun setNotificationOffsetMinutes(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.NOTIFICATION_OFFSET_MINUTES] = value }
    }

    val urgentReminderEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.URGENT_REMINDER_ENABLED] ?: SettingsDefaults.URGENT_REMINDER_ENABLED
    }

    suspend fun setUrgentReminderEnabled(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.URGENT_REMINDER_ENABLED] = value }
    }

    val urgentOffsetMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.URGENT_OFFSET_MINUTES] ?: SettingsDefaults.URGENT_OFFSET_MINUTES
    }

    suspend fun setUrgentOffsetMinutes(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.URGENT_OFFSET_MINUTES] = value }
    }

    val informationalNotificationTimeoutMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES] ?: SettingsDefaults.INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES
    }

    suspend fun setInformationalNotificationTimeoutMinutes(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.INFORMATIONAL_NOTIFICATION_TIMEOUT_MINUTES] = value }
    }

    /** The timeout above as milliseconds for NotificationCompat's setTimeoutAfter, or null when
     *  it's set to Never (setTimeoutAfter must only be applied for a value greater than 0). */
    suspend fun informationalNotificationTimeoutMillis(): Long? =
        informationalNotificationTimeoutMinutes.first().takeIf { it > 0 }?.let { it * 60_000L }

    val drivingModeZoom: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.DRIVING_MODE_ZOOM] ?: SettingsDefaults.DRIVING_MODE_ZOOM
    }

    suspend fun setDrivingModeZoom(value: Float) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.DRIVING_MODE_ZOOM] = value }
    }

    val drivingModeAutoCenter: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.DRIVING_MODE_AUTO_CENTER] ?: SettingsDefaults.DRIVING_MODE_AUTO_CENTER
    }

    suspend fun setDrivingModeAutoCenter(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.DRIVING_MODE_AUTO_CENTER] = value }
    }

    val drivingModeAutoZoom: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.DRIVING_MODE_AUTO_ZOOM] ?: SettingsDefaults.DRIVING_MODE_AUTO_ZOOM
    }

    suspend fun setDrivingModeAutoZoom(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.DRIVING_MODE_AUTO_ZOOM] = value }
    }

    val defaultMapZoom: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.DEFAULT_MAP_ZOOM] ?: SettingsDefaults.DEFAULT_MAP_ZOOM
    }

    suspend fun setDefaultMapZoom(value: Float) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.DEFAULT_MAP_ZOOM] = value }
    }

    val mapSegmentRadiusMeters: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.MAP_SEGMENT_RADIUS_METERS] ?: SettingsDefaults.MAP_SEGMENT_RADIUS_METERS
    }

    suspend fun setMapSegmentRadiusMeters(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.MAP_SEGMENT_RADIUS_METERS] = value }
    }

    val safeColorHex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SAFE_COLOR_HEX] ?: SettingsDefaults.SAFE_COLOR_HEX
    }

    val soonColorHex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SOON_COLOR_HEX] ?: SettingsDefaults.SOON_COLOR_HEX
    }

    val imminentColorHex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.IMMINENT_COLOR_HEX] ?: SettingsDefaults.IMMINENT_COLOR_HEX
    }

    val activeColorHex: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.ACTIVE_COLOR_HEX] ?: SettingsDefaults.ACTIVE_COLOR_HEX
    }

    /** Persists all four at once — always called together so the on-disk state never sits
     *  between a partially-applied swap (see swapAssignment in SweepStatus.kt). */
    suspend fun setSweepStatusColors(colors: SweepStatusColors) {
        context.dataStore.edit { prefs ->
            prefs[SettingsKeys.SAFE_COLOR_HEX] = colors.safeHex
            prefs[SettingsKeys.SOON_COLOR_HEX] = colors.soonHex
            prefs[SettingsKeys.IMMINENT_COLOR_HEX] = colors.imminentHex
            prefs[SettingsKeys.ACTIVE_COLOR_HEX] = colors.activeHex
        }
    }

    suspend fun sweepStatusColorsSnapshot(): SweepStatusColors = SweepStatusColors(
        safeHex = safeColorHex.first(),
        soonHex = soonColorHex.first(),
        imminentHex = imminentColorHex.first(),
        activeHex = activeColorHex.first()
    )

    val mapStyleMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.MAP_STYLE_MODE] ?: SettingsDefaults.MAP_STYLE_MODE
    }

    suspend fun setMapStyleMode(value: String) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.MAP_STYLE_MODE] = value }
    }

    /**
     * A person's own Stadia Maps / DataSF key, entered once in Settings — lets someone this
     * app is shared with use their own quota instead of whichever key is baked into the
     * build they were handed. Empty string means "not set, use the built-in default";
     * ApiKeys.kt is what actually resolves which one wins. Persisted here so it survives
     * app restarts, but ApiKeys also keeps its own in-memory copy so a freshly-saved key
     * takes effect on the very next tile request rather than needing the app relaunched.
     */
    val stadiaApiKeyOverride: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.STADIA_API_KEY_OVERRIDE] ?: ""
    }

    suspend fun setStadiaApiKeyOverride(value: String) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.STADIA_API_KEY_OVERRIDE] = value }
    }

    val dataSfAppTokenOverride: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.DATASF_APP_TOKEN_OVERRIDE] ?: ""
    }

    suspend fun setDataSfAppTokenOverride(value: String) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.DATASF_APP_TOKEN_OVERRIDE] = value }
    }

    /**
     * type is "DRIVING", "PARKED", or "NEEDS_CONFIRMATION" — see TRANSIENT_CONNECTION_EVENT's
     * doc comment. [point] is only meaningful for "NEEDS_CONFIRMATION": it's what lets the
     * widget's tap deep-link straight into the same confirm flow the notification for that
     * same event already opens, rather than just launching the app generically.
     */
    suspend fun recordTransientConnectionEvent(type: String, carId: Long, point: LatLng? = null) {
        context.dataStore.edit { prefs ->
            val base = "$type:$carId:${System.currentTimeMillis()}"
            prefs[SettingsKeys.TRANSIENT_CONNECTION_EVENT] = if (point != null) "$base:${point.lat}:${point.lng}" else base
        }
    }

    data class TransientConnectionEvent(val type: String, val carId: Long, val atMillis: Long, val point: LatLng? = null)

    suspend fun transientConnectionEventSnapshot(): TransientConnectionEvent? {
        val raw = context.dataStore.data.map { it[SettingsKeys.TRANSIENT_CONNECTION_EVENT] }.first() ?: return null
        val parts = raw.split(":")
        if (parts.size != 3 && parts.size != 5) return null
        val carId = parts[1].toLongOrNull() ?: return null
        val atMillis = parts[2].toLongOrNull() ?: return null
        val point = if (parts.size == 5) {
            val lat = parts[3].toDoubleOrNull()
            val lng = parts[4].toDoubleOrNull()
            if (lat != null && lng != null) LatLng(lat, lng) else null
        } else null
        return TransientConnectionEvent(parts[0], carId, atMillis, point)
    }

    val wifiOnlyRefresh: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.WIFI_ONLY_REFRESH] ?: SettingsDefaults.WIFI_ONLY_REFRESH
    }

    suspend fun setWifiOnlyRefresh(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.WIFI_ONLY_REFRESH] = value }
    }

    val bluetoothAutoDetectEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.BLUETOOTH_AUTO_DETECT_ENABLED] ?: SettingsDefaults.BLUETOOTH_AUTO_DETECT_ENABLED
    }

    /** Also flips the two ACL receivers' PackageManager component state to match — see
     *  applyBluetoothAutoDetectComponentState's doc comment for why that (not just this
     *  persisted flag) is what actually stops the background wakeups. */
    suspend fun setBluetoothAutoDetectEnabled(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.BLUETOOTH_AUTO_DETECT_ENABLED] = value }
        applyBluetoothAutoDetectComponentState(context, value)
    }

    val bluetoothAutoDropPin: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.BLUETOOTH_AUTO_DROP_PIN] ?: SettingsDefaults.BLUETOOTH_AUTO_DROP_PIN
    }

    suspend fun setBluetoothAutoDropPin(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.BLUETOOTH_AUTO_DROP_PIN] = value }
    }

    val bluetoothAutoUnparkOnReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.BLUETOOTH_AUTO_UNPARK_ON_RECONNECT] ?: SettingsDefaults.BLUETOOTH_AUTO_UNPARK_ON_RECONNECT
    }

    suspend fun setBluetoothAutoUnparkOnReconnect(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.BLUETOOTH_AUTO_UNPARK_ON_RECONNECT] = value }
    }

    val autoStopDrivingModeOnDisconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.AUTO_STOP_DRIVING_MODE_ON_DISCONNECT] ?: SettingsDefaults.AUTO_STOP_DRIVING_MODE_ON_DISCONNECT
    }

    suspend fun setAutoStopDrivingModeOnDisconnect(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.AUTO_STOP_DRIVING_MODE_ON_DISCONNECT] = value }
    }

    val showImminentCountdown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SHOW_IMMINENT_COUNTDOWN] ?: SettingsDefaults.SHOW_IMMINENT_COUNTDOWN
    }

    suspend fun setShowImminentCountdown(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.SHOW_IMMINENT_COUNTDOWN] = value }
    }

    val tunnelAutoDimEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.TUNNEL_AUTO_DIM_ENABLED] ?: SettingsDefaults.TUNNEL_AUTO_DIM_ENABLED
    }

    suspend fun setTunnelAutoDimEnabled(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.TUNNEL_AUTO_DIM_ENABLED] = value }
    }

    val showRppZoneLabels: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SHOW_RPP_ZONE_LABELS] ?: SettingsDefaults.SHOW_RPP_ZONE_LABELS
    }

    suspend fun setShowRppZoneLabels(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.SHOW_RPP_ZONE_LABELS] = value }
    }

    val showMeterBadges: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SHOW_METER_BADGES] ?: SettingsDefaults.SHOW_METER_BADGES
    }

    suspend fun setShowMeterBadges(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.SHOW_METER_BADGES] = value }
    }

    val tileCacheMaxMb: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.TILE_CACHE_MAX_MB] ?: SettingsDefaults.TILE_CACHE_MAX_MB
    }

    suspend fun setTileCacheMaxMb(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.TILE_CACHE_MAX_MB] = value }
    }

    val soonThresholdDays: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SOON_THRESHOLD_DAYS] ?: SettingsDefaults.SOON_THRESHOLD_DAYS
    }

    suspend fun setSoonThresholdDays(value: Float) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.SOON_THRESHOLD_DAYS] = value }
    }

    val imminentThresholdDays: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.IMMINENT_THRESHOLD_DAYS] ?: SettingsDefaults.IMMINENT_THRESHOLD_DAYS
    }

    suspend fun setImminentThresholdDays(value: Float) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.IMMINENT_THRESHOLD_DAYS] = value }
    }

    val refreshIntervalHours: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.REFRESH_INTERVAL_HOURS] ?: SettingsDefaults.REFRESH_INTERVAL_HOURS
    }

    suspend fun setRefreshIntervalHours(value: Int) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.REFRESH_INTERVAL_HOURS] = value }
    }

    val lastRefreshMillis: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.LAST_REFRESH_MILLIS]
    }

    suspend fun setLastRefreshMillis(value: Long) {
        context.dataStore.edit { prefs -> prefs[SettingsKeys.LAST_REFRESH_MILLIS] = value }
    }

    /**
     * One-shot read of both color thresholds, for use where a continuously-observed Flow
     * isn't worth the complexity (MapScreen fully remounts when navigating back from
     * Settings anyway, so a snapshot taken at load time is sufficient).
     */
    suspend fun sweepThresholdsSnapshot(): SweepThresholds = SweepThresholds(
        soonDays = soonThresholdDays.first(),
        imminentDays = imminentThresholdDays.first()
    )
}