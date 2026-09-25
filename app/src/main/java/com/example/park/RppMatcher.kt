package com.example.park

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class RppMatch(val regulation: RppZoneRegulation, val distanceMeters: Double)

/** Mirrors ParkingMatcher's findNearbySegmentMatches — same bounding-box-then-distance-to-
 *  polyline approach, since the RPP feed has no shared key (like CNN/blockSweepId) to join
 *  against street_segment on; the only way to associate an RPP regulation with a parked spot
 *  is by proximity. */
suspend fun findNearbyRppMatches(context: Context, point: LatLng, radiusDegrees: Double = 0.0015): List<RppMatch> {
    val db = AppDatabase.getInstance(context)
    val nearby = db.rppZoneRegulationDao().getNearby(
        minLat = point.lat - radiusDegrees,
        maxLat = point.lat + radiusDegrees,
        minLng = point.lng - radiusDegrees,
        maxLng = point.lng + radiusDegrees
    )
    return nearby.mapNotNull { regulation ->
        if (regulation.points.size < 2) return@mapNotNull null
        RppMatch(regulation, distancePointToPolylineMeters(point, regulation.points))
    }.sortedBy { it.distanceMeters }
}

/** The closest RPP regulation to [point] within a tight "this is the same curb" radius, or
 *  null if nothing plausible is nearby — same 30m confidence threshold ParkingMatcher's
 *  classifyMatch uses for sweep segments. */
suspend fun findConfidentRppMatch(context: Context, point: LatLng): RppZoneRegulation? =
    findNearbyRppMatches(context, point).firstOrNull { it.distanceMeters <= 30.0 }?.regulation

/**
 * Re-derives the live "move by" deadline for an already-parked car, for display — the same
 * computation saveParkedState/rescheduleAllActiveReminders use for scheduling, just called on
 * demand rather than cached, since the deadline is time-dependent (resets daily) and a stored
 * value would go stale. Null whenever there's nothing to show: no RPP match at this spot, the
 * car holds a permit for the zone, or the regulation's DAYS doesn't parse to any active day.
 */
suspend fun resolveRppDeadline(context: Context, parked: ParkedState, car: Car): RppWarning? =
    resolveRpp(context, parked, car)?.warning

/** An already-parked car's RPP deadline, with the regulation it came from (arming needs both). */
internal class RppResolution(val regulation: RppZoneRegulation, val warning: RppWarning) {
    val moveByMillis: Long get() = warning.moveByDateTime.atZone(SF_ZONE).toInstant().toEpochMilli()
    /** When the RPP roll-forward alarm goes off: the close of the deadline's enforcement window. */
    val rollForwardAtMillis: Long get() = rppWindowEndMillis(regulation, warning.moveByDateTime)
}

/**
 * THE RPP resolver for an already-parked car, shared by arming (RppSource.arm) and the banner
 * (resolveRppDeadline): there used to be two identical copies. Null when there's no deadline: parked
 * off the street, no regulation matched at park time, the car holds a permit, or the days didn't parse.
 *
 * Nothing stores this deadline; it's recomputed every time. That's safe for the delivery markers because
 * the result is deterministic: for a given parked row it stays the same from the moment of parking until
 * the deadline's own window closes (see RppRearmDeterminismTest).
 *
 * (The save path, saveParkedState, still computes its own at park time: a known leftover of the
 * CurbRestrictionSource refactor, see docs/park-sources-refactor-spec.md.)
 */
internal suspend fun resolveRpp(context: Context, parked: ParkedState, car: Car): RppResolution? {
    if (isParkedOffStreet(context, parked)) return null // a garage or lot: no street time limit
    val regulationId = parked.rppRegulationId ?: return null
    val regulation = AppDatabase.getInstance(context).rppZoneRegulationDao().getById(regulationId) ?: return null
    val parkedSince = Instant.ofEpochMilli(parked.parkedAtMillis).atZone(SF_ZONE).toLocalDateTime()
    val warning = nextRppDeadline(regulation, car, parkedSince, sfNow()) ?: return null
    return RppResolution(regulation, warning)
}
