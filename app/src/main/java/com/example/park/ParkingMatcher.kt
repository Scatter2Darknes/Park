package com.example.park

import android.content.Context

data class SegmentMatch(val segment: StreetSegment, val distanceMeters: Double)

enum class MatchConfidence { CONFIDENT, AMBIGUOUS, NO_MATCH }

/**
 * Candidate curbs near [point], nearest first, ONE per physical curb: a curb described by several database rows
 * (one per sweep day / week pattern) is collapsed to a single candidate — see CurbSchedule.collapseSameCurb — so
 * it isn't mistaken for an ambiguous match and the picker doesn't list the same curb repeatedly.
 */
suspend fun findNearbySegmentMatches(context: Context, point: LatLng, radiusDegrees: Double = 0.0015): List<SegmentMatch> {
    val db = AppDatabase.getInstance(context)
    val nearby = db.streetSegmentDao().getNearby(
        minLat = point.lat - radiusDegrees,
        maxLat = point.lat + radiusDegrees,
        minLng = point.lng - radiusDegrees,
        maxLng = point.lng + radiusDegrees
    )
    return nearby.mapNotNull { segment ->
        if (segment.points.size < 2) return@mapNotNull null
        val offsetPoints = offsetPolylineForSide(segment.points, segment.cnnRightLeft)
        SegmentMatch(segment, distancePointToPolylineMeters(point, offsetPoints))
    }.sortedBy { it.distanceMeters }.let { CurbSchedule.collapseSameCurb(it) }
}

fun classifyMatch(matches: List<SegmentMatch>): MatchConfidence {
    if (matches.isEmpty() || matches.first().distanceMeters > 30.0) return MatchConfidence.NO_MATCH
    if (matches.size > 1 && (matches[1].distanceMeters - matches[0].distanceMeters) < 5.0) return MatchConfidence.AMBIGUOUS
    return MatchConfidence.CONFIDENT
}

// NOTE: saveParkedState used to also be defined here with a 4-arg signature identical to
// the one in AppDatabase.kt (which additionally has two optional exactPin params). Two
// top-level functions with the same name where one's required arity is a prefix of the
// other's is an ambiguous-overload compile error at any 4-arg call site — this was a real
// bug, not just dead code. Removed; AppDatabase.kt's version (with pin support) is now the
// single source of truth.

suspend fun proceedToMatching(context: android.content.Context, carId: Long, point: LatLng): ParkingFlowState {
    val matches = findNearbySegmentMatches(context, point)
    return when (classifyMatch(matches)) {
        MatchConfidence.CONFIDENT -> ParkingFlowState.Confirming(carId, matches.first(), point)
        MatchConfidence.AMBIGUOUS -> ParkingFlowState.PickingManually(carId, matches, point)
        // NO_MATCH covers both "zero candidates at all" and "closest candidate is present but
        // over 30m away" (see classifyMatch). Only the former is a genuine no-street-nearby
        // situation (a garage, say) — the latter still has a real, if distant, candidate the
        // manual picker's list and "select from map" escape hatch can offer, so it keeps going
        // to PickingManually exactly as before. Strict on purpose: a middle ground here (e.g.
        // routing a lone 45m-away candidate to NoStreetNearby too) is a real possibility but
        // was left as a deliberate non-decision — see the feature spec's open-question note.
        MatchConfidence.NO_MATCH ->
            if (matches.isEmpty()) ParkingFlowState.NoStreetNearby(carId, point)
            else ParkingFlowState.PickingManually(carId, matches, point)
    }
}

// Tighter than the 30m street-matching confidence radius: a false match here skips segment/RPP
// matching entirely (see SavedLocation.isSafeFromSweeping), so this should only fire when the
// point is essentially AT the saved location, not just generally nearby it.
private const val SAFE_LOCATION_MATCH_RADIUS_METERS = 25.0

/**
 * The closest safe-tagged Saved Location to [point], if one is within
 * [SAFE_LOCATION_MATCH_RADIUS_METERS] — null otherwise. A location must be explicitly marked
 * safe in Settings first (LocationStyleDialog); this never infers it from a parking event. See
 * the doc comments on startParkingFlow's resolveParkingFlow (MapScreen.kt) and
 * performAutoDetectPark (BluetoothDisconnectReceiver.kt) for the two places this short-circuits
 * the normal segment-matching flow.
 */
suspend fun findSafeSavedLocation(context: android.content.Context, point: LatLng): SavedLocation? =
    AppDatabase.getInstance(context).savedLocationDao().getAll()
        .filter { it.isSafeFromSweeping == true }
        .map { it to distanceMetersBetween(point, it.toLatLng()) }
        .filter { (_, distance) -> distance <= SAFE_LOCATION_MATCH_RADIUS_METERS }
        .minByOrNull { (_, distance) -> distance }
        ?.first

suspend fun refreshParkedInfo(context: android.content.Context, carId: Long): ParkedState? {
    return AppDatabase.getInstance(context).parkedStateDao().getForCar(carId)
}

/**
 * Finds the closest segment to [point] that's currently IMMINENT or ACTIVE_OR_VERY_SOON, for
 * the "how much time is left" countdown feature. Uses a tight radius (default ~110m) rather
 * than findNearbySegmentMatches' usual matching radius, since this is meant to answer "is the
 * spot right next to me about to become restricted," not survey a wide area.
 */
suspend fun findNearestUrgentSegment(
    context: android.content.Context,
    point: LatLng,
    thresholds: SweepThresholds,
    radiusDegrees: Double = 0.001
): Pair<StreetSegment, SweepStatus>? {
    val db = AppDatabase.getInstance(context)
    val nearby = db.streetSegmentDao().getNearby(
        minLat = point.lat - radiusDegrees,
        maxLat = point.lat + radiusDegrees,
        minLng = point.lng - radiusDegrees,
        maxLng = point.lng + radiusDegrees
    )
    return nearby
        .filter { it.points.size >= 2 }
        .map { segment ->
            val offsetPoints = offsetPolylineForSide(segment.points, segment.cnnRightLeft)
            segment to distancePointToPolylineMeters(point, offsetPoints)
        }
        .sortedBy { it.second }
        .map { (segment, _) -> segment to sweepStatus(segment, thresholds = thresholds) }
        .firstOrNull { (_, status) -> status == SweepStatus.IMMINENT || status == SweepStatus.ACTIVE_OR_VERY_SOON }
}