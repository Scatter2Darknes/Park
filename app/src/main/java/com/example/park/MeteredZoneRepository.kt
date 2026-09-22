package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex

/**
 * Mirrors RppZoneRepository's shape — a mutex-guarded refresh — but joins TWO upstream feeds
 * instead of reading one (see MeteredZoneApi.kt and MeteredZone's doc comment for why): meter
 * locations (SFMTA's ArcGIS mirror, reliable) and meter operating schedules (data.sf.gov's
 * Socrata API, which can fail — see the schedules try/catch below for the location-only
 * fallback this degrades to instead of losing meter badges entirely).
 */
class MeteredZoneRepository(private val context: Context) {
    suspend fun refreshFromNetwork(): Int {
        if (!fetchMutex.tryLock()) {
            Log.d("MeterSync", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return AppDatabase.getInstance(context).meteredZoneDao().count()
        }
        try {
            val db = AppDatabase.getInstance(context)
            val syncId = System.currentTimeMillis()
            val existingCount = db.meteredZoneDao().count()

            // Locations first, buffered in memory (not inserted page-by-page like RPP does,
            // since a location row isn't the final entity here — it needs the schedule join
            // below to become one, or the location-only fallback if that join can't happen).
            val locations = mutableMapOf<String, MeterLocation>()
            val locationFetch = fetchAllMeterLocations { page ->
                page.forEach { locations[it.postId] = it }
            }
            Log.d("MeterSync", "Meter location fetch complete=${locationFetch.complete}, ${locations.size} active meters")

            val schedules = try {
                val collected = mutableListOf<MeterSchedule>()
                val fetch = fetchAllMeterSchedules { page -> collected.addAll(page) }
                Log.d("MeterSync", "Meter schedule fetch complete=${fetch.complete}, ${collected.size} rows")
                collected
            } catch (e: Exception) {
                Log.w("MeterSync", "Meter schedule fetch failed — falling back to location-only meter badges (no hours gating)", e)
                null
            }

            val zones = if (schedules != null) {
                schedules.mapNotNull { schedule ->
                    val loc = locations[schedule.postId] ?: return@mapNotNull null
                    MeteredZone(
                        id = "${schedule.postId}#${schedule.priority}",
                        postId = schedule.postId,
                        lat = loc.lat,
                        lng = loc.lng,
                        streetName = loc.streetName,
                        days = schedule.daysApplied,
                        hrsBegin = schedule.fromTime?.let { parseMeterTimeToMilitary(it) },
                        hrsEnd = schedule.toTime?.let { parseMeterTimeToMilitary(it) },
                        timeLimitMinutes = parseMeterTimeLimitMinutes(schedule.timeLimitText),
                        lastSeenSyncId = syncId
                    )
                }
            } else {
                locations.values.map { loc ->
                    MeteredZone(
                        id = "${loc.postId}#location",
                        postId = loc.postId,
                        lat = loc.lat,
                        lng = loc.lng,
                        streetName = loc.streetName,
                        days = null, hrsBegin = null, hrsEnd = null, timeLimitMinutes = null,
                        lastSeenSyncId = syncId
                    )
                }
            }
            db.meteredZoneDao().insertAll(zones)

            // Prune only against the LOCATION fetch's completeness — the reliable half. A
            // schedule-fetch failure alone (already handled above) shouldn't also risk this
            // deleting otherwise-good location-only rows.
            try {
                pruneStaleMeteredZones(context, syncId, existingCount, locationFetch)
            } catch (e: Exception) {
                Log.w("MeterSync", "Stale-meter cleanup failed", e)
            }

            return zones.size
        } finally {
            fetchMutex.unlock()
        }
    }

    companion object {
        private val fetchMutex = Mutex()
    }
}
