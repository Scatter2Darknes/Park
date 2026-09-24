package com.example.park

import android.content.Context
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * How a closure affects a parked car. Deliberately NOT a deadline: neither value ever means "move
 * or get towed" (docs/park-closures-spec.md §1 and §4).
 */
enum class ClosureImpact {
    /** The car's own block is fully closed: it may not be able to drive out until the closure ends. */
    BLOCKED_IN,
    /** A closure within [CLOSURE_NEARBY_RADIUS_METERS], or a partial closure on the car's own block: "check signs". */
    NEARBY
}

data class ClosureHit(val closure: StreetClosure, val impact: ClosureImpact, val distanceMeters: Double)

/** "Nearby" radius: straight-line distance, about 1–2 SF blocks. Not a street-graph distance. */
const val CLOSURE_NEARBY_RADIUS_METERS = 200.0

/**
 * Without a known block (no matched street segment, e.g. a safe-location or manual no-street park),
 * a car counts as ON a closed block when it is this close to the block's centerline AND its closest
 * point is strictly inside the line, not at an end. The end check stops a car parked around the corner
 * on the cross street (which is also close to the line's end) from being counted as on the block.
 */
const val CLOSURE_SAME_BLOCK_METERS = 20.0

/** Bounding-box half-size for the candidate query: the nearby radius plus half of a long block, so a
 *  closure whose CENTROID is far but whose line passes close is still found. ~0.004° ≈ 440 m of latitude. */
private const val CLOSURE_SEARCH_BOX_DEGREES = 0.004

/**
 * Classifies one closure for a car at [point]. [parkedCnn] is the CNN of the car's matched street
 * segment, or null when the car wasn't matched to one. Returns null when the closure is irrelevant.
 * Time is not checked here (see findClosuresForParkedCar).
 *
 * Rules:
 * - Same block (by CNN when known, else by geometry, see CLOSURE_SAME_BLOCK_METERS):
 *   full closure -> BLOCKED_IN; partial or lanes-open -> NEARBY.
 * - Otherwise within CLOSURE_NEARBY_RADIUS_METERS of the closed block's line -> NEARBY.
 * The feed has no side-of-street information, so a closure on the block applies to both sides.
 */
fun classifyClosure(closure: StreetClosure, parkedCnn: String?, point: LatLng): ClosureHit? {
    val projection = projectOntoPolyline(point, closure.points)
    val distance = projection?.distanceMeters ?: Double.MAX_VALUE
    val sameBlock = if (parkedCnn != null) {
        closure.cnn == parkedCnn
    } else {
        projection != null && projection.interior && distance <= CLOSURE_SAME_BLOCK_METERS
    }
    return when {
        sameBlock && closure.isFullClosure -> ClosureHit(closure, ClosureImpact.BLOCKED_IN, distance)
        sameBlock -> ClosureHit(closure, ClosureImpact.NEARBY, distance)
        distance <= CLOSURE_NEARBY_RADIUS_METERS -> ClosureHit(closure, ClosureImpact.NEARBY, distance)
        else -> null
    }
}

/**
 * Where a parked car is, for closure matching. In order:
 *  1. [exactPin]: the user pinned the car (their location, or a pin dropped by hand);
 *  2. [curbMidpoint]: the middle of the curb they chose — NOT the GPS point the parking flow started
 *     from, which is where the phone was, and after "pick manually / select from map" can be far
 *     from the car (the same reason saveParkedState matches RPP from the curb);
 *  3. [parkedPoint]: a park with no curb (garage, "no street nearby"), where the saved point is all
 *     there is.
 */
fun closureMatchOrigin(exactPin: LatLng?, curbMidpoint: LatLng?, parkedPoint: LatLng): LatLng =
    exactPin ?: curbMidpoint ?: parkedPoint

/**
 * Every closure that affects [parked], not yet over at [nowMillis], BLOCKED_IN first, then by start
 * time. Matched from closureMatchOrigin; "same block" is decided by the chosen segment's CNN when
 * there is one. Whether each hit is far enough ahead to alert about is the caller's decision.
 */
suspend fun findClosuresForParkedCar(
    context: Context,
    parked: ParkedState,
    nowMillis: Long = System.currentTimeMillis()
): List<ClosureHit> {
    val db = AppDatabase.getInstance(context)
    val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
    val parkedCnn = segment?.cnn?.takeIf { it.isNotBlank() }
    val point = closureMatchOrigin(
        exactPin = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng) else null,
        curbMidpoint = segment?.takeIf { it.points.size >= 2 }
            ?.let { midpointAlongPath(offsetPolylineForSide(it.points, it.cnnRightLeft)) },
        parkedPoint = LatLng(parked.parkedLat, parked.parkedLng)
    )
    val candidates = db.streetClosureDao().getNearby(
        nowMillis,
        minLat = point.lat - CLOSURE_SEARCH_BOX_DEGREES,
        maxLat = point.lat + CLOSURE_SEARCH_BOX_DEGREES,
        minLng = point.lng - CLOSURE_SEARCH_BOX_DEGREES,
        maxLng = point.lng + CLOSURE_SEARCH_BOX_DEGREES
    ) + (parkedCnn?.let { db.streetClosureDao().getForCnn(it, nowMillis) } ?: emptyList()) // a row with no geometry is only reachable by CNN
    return rankClosureHits(candidates.distinctBy { it.objectId }.mapNotNull { classifyClosure(it, parkedCnn, point) })
}

/** BLOCKED_IN before NEARBY, then soonest start first. */
fun rankClosureHits(hits: List<ClosureHit>): List<ClosureHit> =
    hits.sortedWith(compareBy<ClosureHit> { it.impact.ordinal }.thenBy { it.closure.startMillis })

/** Where [point] lands on a polyline: the distance to it, and whether that closest point is strictly
 *  inside the line (true) or clamped to one of its two ends (false). */
data class PolylineProjection(val distanceMeters: Double, val interior: Boolean)

/**
 * Closest point on [line] to [point], using the same flat local projection as
 * distancePointToPolylineMeters in MapUtils.kt (accurate to well under a meter at block scale).
 * Kept separate because this also needs to know whether the closest point is an END of the line,
 * and because MapUtils.kt pulls in osmdroid, which plain JVM unit tests can't load.
 * Null for a line with fewer than two points.
 */
fun projectOntoPolyline(point: LatLng, line: List<LatLng>): PolylineProjection? {
    if (line.size < 2) return null
    val lngScale = 111320.0 * cos(Math.toRadians(point.lat))
    val latScale = 111320.0
    fun x(p: LatLng) = (p.lng - point.lng) * lngScale
    fun y(p: LatLng) = (p.lat - point.lat) * latScale
    var best = Double.MAX_VALUE
    var bestIsEnd = true
    for (i in 0 until line.size - 1) {
        val ax = x(line[i]); val ay = y(line[i])
        val bx = x(line[i + 1]); val by = y(line[i + 1])
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / lenSq // the point itself is the origin
        val clamped = t.coerceIn(0.0, 1.0)
        val cx = ax + clamped * dx; val cy = ay + clamped * dy
        val dist = sqrt(cx * cx + cy * cy)
        if (dist < best) {
            best = dist
            // Clamped to a vertex: only an "end" if that vertex is the line's first or last point.
            bestIsEnd = (t <= 0.0 && i == 0) || (t >= 1.0 && i == line.size - 2)
        }
    }
    return PolylineProjection(best, interior = !bestIsEnd)
}
