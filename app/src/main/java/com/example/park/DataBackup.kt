package com.example.park

import android.content.Context
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private const val BACKUP_VERSION = 1

private fun JSONObject.putNullable(key: String, value: String?) {
    put(key, value ?: JSONObject.NULL)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

/**
 * Serializes everything worth backing up: car profiles, saved locations, schedule
 * overrides, and settings. Deliberately excludes street_segment (re-fetchable cached DataSF
 * data) and parked_state (ephemeral — restoring "where you were parked" on a different
 * device/after a factory reset isn't meaningful).
 */
suspend fun exportBackupJson(context: Context): String {
    val db = AppDatabase.getInstance(context)
    val settings = SettingsRepository(context)

    val carsJson = JSONArray()
    db.carDao().getAll().forEach { car ->
        carsJson.put(JSONObject().apply {
            put("name", car.name)
            put("isDefault", car.isDefault)
            putNullable("bluetoothDeviceAddress", car.bluetoothDeviceAddress)
            putNullable("colorHex", car.colorHex)
            putNullable("iconEmoji", car.iconEmoji)
        })
    }

    val locationsJson = JSONArray()
    db.savedLocationDao().getAll().forEach { loc ->
        locationsJson.put(JSONObject().apply {
            put("name", loc.name)
            put("lat", loc.lat)
            put("lng", loc.lng)
            put("isSafeFromSweeping", loc.isSafeFromSweeping == true)
            put("isOffStreet", loc.isOffStreet == true)
        })
    }

    val overridesJson = JSONArray()
    db.scheduleOverrideDao().getAll().forEach { o ->
        overridesJson.put(JSONObject().apply {
            put("blockSweepId", o.blockSweepId)
            put("fullName", o.fullName)
            put("week1", o.week1); put("week2", o.week2); put("week3", o.week3)
            put("week4", o.week4); put("week5", o.week5)
            put("fromHour", o.fromHour); put("toHour", o.toHour)
            putNullable("notes", o.notes)
        })
    }

    val settingsJson = JSONObject().apply {
        put("alwaysAskCar", settings.alwaysAskCar.first())
        put("notificationOffsetMinutes", settings.notificationOffsetMinutes.first())
        put("urgentReminderEnabled", settings.urgentReminderEnabled.first())
        put("urgentOffsetMinutes", settings.urgentOffsetMinutes.first())
        put("soonThresholdDays", settings.soonThresholdDays.first())
        put("imminentThresholdDays", settings.imminentThresholdDays.first())
        put("refreshIntervalHours", settings.refreshIntervalHours.first())
        put("drivingModeZoom", settings.drivingModeZoom.first())
        put("drivingModeAutoCenter", settings.drivingModeAutoCenter.first())
        put("drivingModeAutoZoom", settings.drivingModeAutoZoom.first())
        put("defaultMapZoom", settings.defaultMapZoom.first())
        put("mapSegmentRadiusMeters", settings.mapSegmentRadiusMeters.first())
        put("safeColorHex", settings.safeColorHex.first())
        put("soonColorHex", settings.soonColorHex.first())
        put("imminentColorHex", settings.imminentColorHex.first())
        put("activeColorHex", settings.activeColorHex.first())
        put("mapStyleMode", settings.mapStyleMode.first())
        put("wifiOnlyRefresh", settings.wifiOnlyRefresh.first())
        put("bluetoothAutoDropPin", settings.bluetoothAutoDropPin.first())
        put("bluetoothAutoUnparkOnReconnect", settings.bluetoothAutoUnparkOnReconnect.first())
        put("showImminentCountdown", settings.showImminentCountdown.first())
        put("showRppZoneLabels", settings.showRppZoneLabels.first())
        put("closureParkTimeCheck", settings.closureParkTimeCheck.first())
        put("closureBackgroundSync", settings.closureBackgroundSync.first())
        put("closureAlertLeadHours", settings.closureAlertLeadHours.first())
        put("towChecksEnabled", settings.towChecksEnabled.first())
        put("showClosuresLayer", settings.showClosuresLayer.first())
    }

    val root = JSONObject().apply {
        put("backupVersion", BACKUP_VERSION)
        put("exportedAtMillis", System.currentTimeMillis())
        put("cars", carsJson)
        put("savedLocations", locationsJson)
        put("scheduleOverrides", overridesJson)
        put("settings", settingsJson)
    }
    return root.toString(2)
}

/**
 * Imports a backup additively — inserts cars/locations/overrides without touching what's
 * already there (so importing twice, or onto a phone that already has data, just adds
 * duplicates rather than silently deleting anything). Settings values are applied directly
 * since those are singletons, not a list, so "import" and "overwrite" mean the same thing
 * for them.
 *
 * One exception to "just adds": a Bluetooth device can be linked to only ONE car (auto-park looks the car
 * up by device address and would otherwise pick one arbitrarily). An imported car whose device is already
 * linked (to an existing car, or to a car earlier in the same import) arrives unlinked; the existing link
 * wins, so an import never changes what's already on the phone.
 *
 * @return how many imported cars arrived without their Bluetooth link for that reason.
 */
suspend fun importBackupJson(context: Context, json: String): Result<Int> = runCatching {
    val root = JSONObject(json)
    val db = AppDatabase.getInstance(context)
    val settings = SettingsRepository(context)
    var skippedLinks = 0

    root.optJSONArray("cars")?.let { carsJson ->
        val imported = (0 until carsJson.length()).map { i ->
            val c = carsJson.getJSONObject(i)
            Car(
                name = c.getString("name"),
                isDefault = c.optBoolean("isDefault", false),
                bluetoothDeviceAddress = c.optStringOrNull("bluetoothDeviceAddress"),
                colorHex = c.optStringOrNull("colorHex"),
                iconEmoji = c.optStringOrNull("iconEmoji")
            )
        }
        val deduped = withoutTakenBluetoothLinks(db.carDao().getAll(), imported)
        skippedLinks = imported.zip(deduped).count { (before, after) -> before.bluetoothDeviceAddress != after.bluetoothDeviceAddress }
        deduped.forEach { db.carDao().insert(it) }
        // A newly linked device may be connected right now (display only: an imported car isn't parked).
        refreshBluetoothLinks(context, runMissedConnect = false)
    }

    root.optJSONArray("savedLocations")?.let { locationsJson ->
        for (i in 0 until locationsJson.length()) {
            val l = locationsJson.getJSONObject(i)
            db.savedLocationDao().insert(
                SavedLocation(
                    name = l.getString("name"), lat = l.getDouble("lat"), lng = l.getDouble("lng"),
                    // optBoolean rather than getBoolean: a backup exported before this field
                    // existed simply won't have the key, and "not marked safe" is the right
                    // default for that case.
                    isSafeFromSweeping = l.optBoolean("isSafeFromSweeping", false),
                    // Same for older backups without the key: not off-street, so tow checks keep applying.
                    isOffStreet = l.optBoolean("isOffStreet", false)
                )
            )
        }
    }

    root.optJSONArray("scheduleOverrides")?.let { overridesJson ->
        for (i in 0 until overridesJson.length()) {
            val o = overridesJson.getJSONObject(i)
            db.scheduleOverrideDao().upsert(
                ScheduleOverride(
                    blockSweepId = o.getString("blockSweepId"),
                    fullName = o.getString("fullName"),
                    week1 = o.getBoolean("week1"), week2 = o.getBoolean("week2"),
                    week3 = o.getBoolean("week3"), week4 = o.getBoolean("week4"),
                    week5 = o.getBoolean("week5"),
                    fromHour = o.getInt("fromHour"), toHour = o.getInt("toHour"),
                    notes = o.optStringOrNull("notes")
                )
            )
        }
    }

    root.optJSONObject("settings")?.let { s ->
        if (s.has("alwaysAskCar")) settings.setAlwaysAskCar(s.getBoolean("alwaysAskCar"))
        if (s.has("notificationOffsetMinutes")) settings.setNotificationOffsetMinutes(s.getInt("notificationOffsetMinutes"))
        if (s.has("urgentReminderEnabled")) settings.setUrgentReminderEnabled(s.getBoolean("urgentReminderEnabled"))
        if (s.has("urgentOffsetMinutes")) settings.setUrgentOffsetMinutes(s.getInt("urgentOffsetMinutes"))
        if (s.has("soonThresholdDays")) settings.setSoonThresholdDays(s.getDouble("soonThresholdDays").toFloat())
        if (s.has("imminentThresholdDays")) settings.setImminentThresholdDays(s.getDouble("imminentThresholdDays").toFloat())
        if (s.has("refreshIntervalHours")) settings.setRefreshIntervalHours(s.getInt("refreshIntervalHours"))
        if (s.has("drivingModeZoom")) settings.setDrivingModeZoom(s.getDouble("drivingModeZoom").toFloat())
        if (s.has("drivingModeAutoCenter")) settings.setDrivingModeAutoCenter(s.getBoolean("drivingModeAutoCenter"))
        if (s.has("drivingModeAutoZoom")) settings.setDrivingModeAutoZoom(s.getBoolean("drivingModeAutoZoom"))
        if (s.has("defaultMapZoom")) settings.setDefaultMapZoom(s.getDouble("defaultMapZoom").toFloat())
        if (s.has("mapSegmentRadiusMeters")) settings.setMapSegmentRadiusMeters(s.getInt("mapSegmentRadiusMeters"))
        if (s.has("mapStyleMode")) settings.setMapStyleMode(s.getString("mapStyleMode"))
        if (s.has("wifiOnlyRefresh")) settings.setWifiOnlyRefresh(s.getBoolean("wifiOnlyRefresh"))
        if (s.has("bluetoothAutoDropPin")) settings.setBluetoothAutoDropPin(s.getBoolean("bluetoothAutoDropPin"))
        if (s.has("bluetoothAutoUnparkOnReconnect")) settings.setBluetoothAutoUnparkOnReconnect(s.getBoolean("bluetoothAutoUnparkOnReconnect"))
        if (s.has("showImminentCountdown")) settings.setShowImminentCountdown(s.getBoolean("showImminentCountdown"))
        if (s.has("showRppZoneLabels")) settings.setShowRppZoneLabels(s.getBoolean("showRppZoneLabels"))
        if (s.has("closureParkTimeCheck")) settings.setClosureParkTimeCheck(s.getBoolean("closureParkTimeCheck"))
        if (s.has("closureBackgroundSync")) settings.setClosureBackgroundSync(s.getBoolean("closureBackgroundSync"))
        if (s.has("closureAlertLeadHours")) settings.setClosureAlertLeadHours(s.getInt("closureAlertLeadHours"))
        if (s.has("showClosuresLayer")) settings.setShowClosuresLayer(s.getBoolean("showClosuresLayer"))
        // Older backups don't have it: tow stays on, as it was before the switch existed.
        if (s.has("towChecksEnabled")) settings.setTowChecksEnabled(s.getBoolean("towChecksEnabled"))
        // The background closure job and every parked car's closure and tow alerts follow these settings.
        if (s.has("closureBackgroundSync") || s.has("closureParkTimeCheck") || s.has("closureAlertLeadHours") || s.has("towChecksEnabled")) {
            applyClosureSyncSchedule(context, androidx.work.ExistingPeriodicWorkPolicy.REPLACE)
            rescheduleAllActiveReminders(context)
        }
        // The four status colors are restored together via swapAssignment-preserving
        // setSweepStatusColors, rather than four independent writes, so the pairwise-distinct
        // invariant holds even if the imported values happen to collide with current ones.
        if (s.has("safeColorHex") && s.has("soonColorHex") && s.has("imminentColorHex") && s.has("activeColorHex")) {
            settings.setSweepStatusColors(
                SweepStatusColors(
                    safeHex = s.getString("safeColorHex"),
                    soonHex = s.getString("soonColorHex"),
                    imminentHex = s.getString("imminentColorHex"),
                    activeHex = s.getString("activeColorHex")
                )
            )
        }
    }
    skippedLinks
}

/**
 * [imported] with each Bluetooth link that's already taken removed: taken by one of [existing], or by a car
 * earlier in [imported]. Addresses compare case-insensitively, as everywhere else they're matched.
 */
internal fun withoutTakenBluetoothLinks(existing: List<Car>, imported: List<Car>): List<Car> {
    val taken = existing.mapNotNull { it.bluetoothDeviceAddress?.uppercase() }.toMutableSet()
    return imported.map { car ->
        val address = car.bluetoothDeviceAddress ?: return@map car
        // Set.add returns false when the address was already in the set: a clash.
        if (taken.add(address.uppercase())) car else car.copy(bluetoothDeviceAddress = null)
    }
}