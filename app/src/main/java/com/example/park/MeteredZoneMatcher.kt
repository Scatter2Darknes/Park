package com.example.park

import android.content.Context

data class MeteredZoneMatch(val zone: MeteredZone, val distanceMeters: Double)

/**
 * Candidate meters near [point], nearest first. Mirrors RppMatcher's findNearbyRppMatches, but
 * meters are point features (one lat/lng per post), so this is a plain point-to-point distance
 * rather than point-to-polyline.
 */
suspend fun findNearbyMeteredMatches(context: Context, point: LatLng, radiusDegrees: Double = 0.0015): List<MeteredZoneMatch> {
    val db = AppDatabase.getInstance(context)
    val nearby = db.meteredZoneDao().getNearby(
        minLat = point.lat - radiusDegrees,
        maxLat = point.lat + radiusDegrees,
        minLng = point.lng - radiusDegrees,
        maxLng = point.lng + radiusDegrees
    )
    return nearby
        .map { MeteredZoneMatch(it, distanceMetersBetween(point, LatLng(it.lat, it.lng))) }
        .sortedBy { it.distanceMeters }
}

/**
 * The closest CURRENTLY-ENFORCED meter to [point] within the same 30m confidence radius
 * ParkingMatcher's classifyMatch uses for sweep segments, or null if nothing plausible and
 * active is nearby. "Enforced" includes the location-only fallback case (hours unknown — see
 * isMeterEnforcedOrUnknown), so a meter with no schedule data still surfaces rather than being
 * silently dropped.
 */
suspend fun findConfidentMeteredMatch(context: Context, point: LatLng): MeteredZone? =
    findNearbyMeteredMatches(context, point)
        .filter { it.distanceMeters <= 30.0 && isMeterEnforcedOrUnknown(it.zone, sfNow()) }
        .minByOrNull { it.distanceMeters }
        ?.zone
