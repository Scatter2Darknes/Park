package com.example.park

import android.content.Context
import android.location.LocationManager
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.pow
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class ParkedHighlightPolyline : Polyline()
class CarPinMarker(mapView: MapView) : Marker(mapView)
class SavedLocationMarker(mapView: MapView) : Marker(mapView)
class CountdownLabelMarker(mapView: MapView) : Marker(mapView)
class RppZoneLabelMarker(mapView: MapView) : Marker(mapView)
class MeterBadgeMarker(mapView: MapView) : Marker(mapView)

// The tap-feedback halo drawn under whichever segment was just tapped — see
// showTappedSegmentHighlight. A distinct class (rather than reusing Polyline directly) so
// loadAndDrawSegments' periodic overlay cleanup can recognize and skip it, the same pattern
// ParkedHighlightPolyline already uses.
class TappedSegmentHaloPolyline : Polyline()

// Safety net against a genuinely dense case (e.g. a citywide sweep morning) producing dozens
// of overlapping labels — the zoom gate already keeps the visible area small, but this caps
// it regardless of how many IMMINENT/ACTIVE segments the loaded radius happens to contain.
private const val MAX_COUNTDOWN_LABELS = 30

// Same idea, for RPP zone labels — a dense residential area can have many RPP-regulated
// blockfaces within one viewport, and unlike the countdown labels above (gated to only
// IMMINENT/ACTIVE segments) every RPP-regulated block in view gets a label, so this cap
// matters more here.
private const val MAX_RPP_ZONE_LABELS = 40

// Same idea, for meter badges.
private const val MAX_METER_BADGES = 40

// How close two badges' SCREEN positions (not geographic distance — this is what actually
// governs visual overlap, and it's naturally zoom-aware: the same real-world spacing maps to
// more screen pixels zoomed in, fewer zoomed out) can be before the later one is skipped
// entirely rather than drawn on top of / crowding the earlier one. Approximate (badges are
// pills of varying width, not all the same size, so this is a circular stand-in for what's
// really an irregular bounding-box overlap check) but tuned to noticeably thin out a dense
// block without being so aggressive it hides genuinely separate badges.
private const val BADGE_SUPPRESSION_RADIUS_DP = 26f

// A fixed violet, deliberately outside the SAFE/SOON/IMMINENT/ACTIVE palette (greens/yellows/
// reds) and not user-configurable like those are — RPP status isn't a sweep-urgency signal, so
// it shouldn't visually read as one.
private val RPP_ZONE_LABEL_COLOR_INT = android.graphics.Color.parseColor("#8E24AA")

// Distinct from both the sweep palette and the RPP violet above — metered is a third,
// independent axis (a curb can be swept AND RPP-zoned AND metered all at once), so it needs
// its own color rather than borrowing either.
private val METER_BADGE_COLOR_INT = android.graphics.Color.parseColor("#00838F")

// Keyed by a prefix ("car|"/"loc|") plus colorHex|icon|photoPath — styling rarely changes,
// so repeated overlay refreshes (every debounced pan) reuse the same Bitmap instead of
// redoing the Canvas draw each time. The prefix keeps a car and a location that happen to
// share an identical color/icon/photo combo from colliding in the cache. Plain
// (non-concurrent) map is fine: both refresh functions only ever run on the Compose
// main-thread coroutine scope in this app, never from a background thread.
private val avatarMarkerBitmapCache = mutableMapOf<String, android.graphics.Bitmap>()

/**
 * Builds a small circular marker icon for a car: a photo (circular-cropped) if one's set,
 * otherwise its custom color as the fill with its emoji centered on top. See
 * buildCircularAvatarBitmap for the shared drawing logic (also used by
 * buildLocationMarkerIcon for saved locations).
 */
fun buildCarMarkerIcon(context: Context, car: Car): android.graphics.drawable.Drawable {
    val cacheKey = "car|${car.photoPath}|${car.colorHex ?: DEFAULT_CAR_COLOR_HEX}|${car.iconEmoji ?: DEFAULT_CAR_ICON}"
    val bitmap = avatarMarkerBitmapCache.getOrPut(cacheKey) {
        buildCircularAvatarBitmap(context, car.photoPath, carColorInt(car), carIcon(car))
    }
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/** Same idea as buildCarMarkerIcon, for a saved location's pin instead of a parked car's. */
fun buildLocationMarkerIcon(context: Context, location: SavedLocation): android.graphics.drawable.Drawable {
    val cacheKey = "loc|${location.photoPath}|${location.colorHex ?: DEFAULT_LOCATION_COLOR_HEX}|${location.iconEmoji ?: DEFAULT_LOCATION_ICON}"
    val bitmap = avatarMarkerBitmapCache.getOrPut(cacheKey) {
        buildCircularAvatarBitmap(context, location.photoPath, locationColorInt(location), locationIcon(location))
    }
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * The current-location marker's generic icon for when no car is actively BT-linked.
 * MyLocationNewOverlay.setPersonIcon/setDirectionArrow have no "unset, go back to stock"
 * call — once a car-specific bitmap has been set, it stays showing until something else is
 * explicitly set in its place. This is that "something else": a plain, no-car-specific dot,
 * always applied whenever iconCar is null in the update lambda, rather than skipping the call
 * and hoping the old bitmap doesn't linger (it does — that was the "duplicate same icon"
 * bug when Bluetooth disconnects mid-drive, before Driving Mode is turned off).
 */
// Deliberately its own color, distinct from both DEFAULT_CAR_COLOR_HEX and
// DEFAULT_LOCATION_COLOR_HEX -- this marker's whole point is to never be mistaken for an
// actual car or saved-location profile, so it shouldn't borrow either system's default.
private const val NAVIGATION_DOT_COLOR_HEX = "#4285F4"

// Deliberately NOT built from buildCircularAvatarBitmap -- every car and saved-location pin
// already uses that exact look (colored circle + emoji + white border), and saved locations
// default to this same pin emoji too, so a plain colored-circle-with-pin marker for "you are
// here" was visually indistinguishable from an actual saved-location pin at a glance. A
// halo-plus-dot "navigation puck" (the standard Google-Maps-style current-location marker) is
// a genuinely different shape that no car/location profile can ever produce, rather than
// relying on a color choice a customized profile could still coincidentally match.
fun buildDefaultLocationIcon(context: Context): android.graphics.Bitmap {
    return avatarMarkerBitmapCache.getOrPut("default_location_icon_v2") {
        buildNavigationDotBitmap(context, android.graphics.Color.parseColor(NAVIGATION_DOT_COLOR_HEX))
    }
}

private fun buildNavigationDotBitmap(context: Context, colorInt: Int): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (44 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = sizePx / 2f

    val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        alpha = 60
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center, haloPaint)

    val dotRadius = center * 0.5f
    val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(center, center, dotRadius, dotPaint)

    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    canvas.drawCircle(center, center, dotRadius - borderPaint.strokeWidth / 2, borderPaint)

    return bitmap
}

/**
 * Same visual language as buildCarMarkerIcon's parked-pin (colored circle + emoji, or photo)
 * but with a small triangular pointer above it, for use as the "driving" variant of the
 * current-location marker (MyLocationNewOverlay.setDirectionArrow's second bitmap) — the
 * pointer is what osmdroid rotates to the GPS bearing, so it needs to visually read as "this
 * end is forward" the way the stock default arrow icon does, rather than being ambiguous like
 * a plain circle would be.
 */
fun buildCarDirectionArrowBitmap(context: Context, car: Car?): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val circleSizePx = (44 * density).toInt()
    val pointerHeightPx = (14 * density).toInt()
    val totalHeightPx = circleSizePx + pointerHeightPx
    val bitmap = android.graphics.Bitmap.createBitmap(circleSizePx, totalHeightPx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val colorInt = car?.let { carColorInt(it) } ?: android.graphics.Color.parseColor(NAVIGATION_DOT_COLOR_HEX)

    // Pointer first (drawn underneath, at the top of the canvas) so the circle's white border
    // below cleanly overlaps its base rather than leaving a seam.
    val pointerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = android.graphics.Paint.Style.FILL
    }
    val pointerPath = android.graphics.Path().apply {
        moveTo(circleSizePx / 2f, 0f)
        lineTo(circleSizePx * 0.75f, pointerHeightPx + circleSizePx * 0.1f)
        lineTo(circleSizePx * 0.25f, pointerHeightPx + circleSizePx * 0.1f)
        close()
    }
    canvas.drawPath(pointerPath, pointerPaint)

    // Reuse the same circular avatar drawing the parked-pin uses, offset below the pointer —
    // falling back to the generic default icon (not leaving this blank) when there's no car,
    // so the no-BT-link driving state still shows a complete icon, not a bare floating triangle.
    val circleBitmap = if (car != null) {
        (buildCarMarkerIcon(context, car) as android.graphics.drawable.BitmapDrawable).bitmap
    } else {
        buildDefaultLocationIcon(context)
    }
    canvas.drawBitmap(circleBitmap, 0f, pointerHeightPx.toFloat(), null)

    return bitmap
}

/**
 * Same as buildCarMarkerIcon, but with a thicker, brighter ring than the parked-pin's plain
 * white border — the "connected" visual cue for the live-location marker specifically. Kept
 * as its own function (rather than a parameter on buildCarMarkerIcon) since the ring only
 * ever makes sense on the current-location marker: the parked-pin meaning is already "this
 * car is parked here", a ring there would be redundant, not clarifying.
 */
fun buildConnectedLocationIcon(context: Context, car: Car): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val ringWidthPx = 6f * density
    val base = (buildCarMarkerIcon(context, car) as android.graphics.drawable.BitmapDrawable).bitmap
    val totalSize = base.width + (ringWidthPx * 2).toInt()
    val out = android.graphics.Bitmap.createBitmap(totalSize, totalSize, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = carColorInt(car)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = ringWidthPx
    }
    canvas.drawCircle(totalSize / 2f, totalSize / 2f, totalSize / 2f - ringWidthPx / 2f, ringPaint)
    canvas.drawBitmap(base, ringWidthPx, ringWidthPx, null)
    return out
}

/**
 * Draws a circular marker: a photo (circular-cropped via BitmapShader — the standard Android
 * technique, since Canvas has no built-in "clip to circle and draw bitmap" primitive) if one's
 * provided, otherwise a solid color fill with an emoji centered on top — either way with a
 * white ring for contrast against any map background. Generated on the fly rather than shipped
 * as drawable resources, since colors/icons/photos are all user-chosen and open-ended. Takes
 * plain values rather than a typed Car/SavedLocation so both entity types can share this exact
 * drawing logic; only the caller-side cache key construction differs between them.
 */
private fun buildCircularAvatarBitmap(
    context: Context,
    photoPath: String?,
    colorInt: Int,
    icon: String
): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (44 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val radius = sizePx / 2f

    val photoBitmap = photoPath?.let { path ->
        try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
    }

    if (photoBitmap != null) {
        val scaled = android.graphics.Bitmap.createScaledBitmap(photoBitmap, sizePx, sizePx, true)
        val shader = android.graphics.BitmapShader(
            scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
        )
        val photoPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
        }
        canvas.drawCircle(radius, radius, radius, photoPaint)
    } else {
        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colorInt
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(radius, radius, radius, fillPaint)

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = sizePx * 0.55f
        }
        val textY = radius - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(icon, radius, textY, textPaint)
    }

    // White border drawn last, on top, for contrast — same treatment whether the fill above
    // was a photo or a color+emoji.
    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    canvas.drawCircle(radius, radius, radius - borderPaint.strokeWidth / 2, borderPaint)

    return bitmap
}

// Below this zoom level, segment tap targets are disabled so a pan/zoom gesture over a
// wide area of the map can't be accidentally interpreted as tapping a specific block.
private const val MIN_ZOOM_FOR_SEGMENT_TAP = 16.0

suspend fun refreshParkedCarOverlays(
    mapView: MapView,
    context: Context,
    locationOverlay: MyLocationNewOverlay? = null
) {
    val db = AppDatabase.getInstance(context)
    val cars = db.carDao().getAll()

    mapView.overlays.removeAll { it is ParkedHighlightPolyline || it is CarPinMarker }

    cars.forEach { car ->
        val parked = db.parkedStateDao().getForCar(car.id) ?: return@forEach
        val segment = parked.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }

        if (segment != null && segment.points.size >= 2) {
            val offsetPoints = offsetPolylineForSide(segment.points, segment.cnnRightLeft)
            val trimmedPoints = trimPolylineEnds(offsetPoints, trimMeters = 7.5)
            val highlight = ParkedHighlightPolyline().apply {
                setPoints(trimmedPoints.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = carColorInt(car)
                outlinePaint.strokeWidth = 26f
                outlinePaint.alpha = 200
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 15f), 0f)
            }
            mapView.overlays.add(0, highlight) // index 0 = drawn first = underneath everything added after it
        }

        if (parked.exactPinLat != null && parked.exactPinLng != null) {
            val marker = CarPinMarker(mapView).apply {
                position = GeoPoint(parked.exactPinLat, parked.exactPinLng)
                title = car.name
                snippet = parked.nextSweepAtMillis?.let {
                    val dt = java.time.Instant.ofEpochMilli(it).atZone(SF_ZONE)
                    "Next cleaning: ${dt.toLocalDate()}"
                } ?: "No schedule found"
                icon = buildCarMarkerIcon(context, car)
                // Custom icon is a plain circle (no pointed tip like the default pin), so
                // anchor its center — not its bottom — to the geo position.
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                // Markers are appended after hit-lines, and osmdroid dispatches taps in
                // reverse of add-order (last added = checked first) — without this, the
                // marker's default click behavior (show an info window) would swallow any
                // tap near it, making it impossible to view a segment's detail sheet or drop
                // a new pin right next to an existing parked car. Always returning false
                // makes the marker purely visual and lets every tap fall through.
                setOnMarkerClickListener { _, _ -> false }
            }
            mapView.overlays.add(marker)
        }
    }
    // See reloadSegmentsAndMarkers's doc comment — the freshly-appended CarPinMarker above
    // would otherwise bury the live location dot the same way segment lines do. Callers that
    // go through reloadSegmentsAndMarkers already get this via its own re-lift after this
    // function returns; this covers the standalone call site (the live Bluetooth park/unpark
    // redraw), which calls this function directly without a following segment reload.
    locationOverlay?.let { overlay ->
        if (mapView.overlays.remove(overlay)) {
            mapView.overlays.add(overlay)
        }
    }
    mapView.invalidate()
}

/**
 * Draws a pin for every saved location, mirroring refreshParkedCarOverlays. Markers are
 * purely visual (setOnMarkerClickListener always returns false) for the same reason car
 * markers are — otherwise, being added last, they'd intercept taps meant for a segment
 * hit-line or pin-drop underneath them.
 */
suspend fun refreshSavedLocationOverlays(mapView: MapView, context: Context) {
    val locations = AppDatabase.getInstance(context).savedLocationDao().getAll()

    mapView.overlays.removeAll { it is SavedLocationMarker }

    locations.forEach { location ->
        val marker = SavedLocationMarker(mapView).apply {
            position = GeoPoint(location.lat, location.lng)
            title = location.name
            icon = buildLocationMarkerIcon(context, location)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> false }
        }
        mapView.overlays.add(marker)
    }
    mapView.invalidate()
}

private fun toLocalXY(base: LatLng, p: LatLng): Pair<Double, Double> {
    val avgLatRad = Math.toRadians(base.lat)
    val latScale = 111320.0
    val lngScale = 111320.0 * cos(avgLatRad)
    return (p.lng - base.lng) * lngScale to (p.lat - base.lat) * latScale
}

fun distancePointToPolylineMeters(point: LatLng, line: List<LatLng>): Double {
    if (line.isEmpty()) return Double.MAX_VALUE
    val (px, py) = toLocalXY(point, point) // always (0,0) — point is its own origin
    var minDist = Double.MAX_VALUE

    for (i in 0 until line.size - 1) {
        val (ax, ay) = toLocalXY(point, line[i])
        val (bx, by) = toLocalXY(point, line[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val dist = sqrt((px - closestX).pow(2) + (py - closestY).pow(2))
        if (dist < minDist) minDist = dist
    }
    return minDist
}

/**
 * A single GPS subscription shared by the whole map screen, replacing osmdroid's default
 * `GpsMyLocationProvider`. Feeds the blue-dot overlay exactly like the default provider
 * would, but also exposes [onRawLocation] so driving mode can read bearing/speed from the
 * SAME fix instead of registering its own separate `requestLocationUpdates` — previously
 * two independent GPS subscriptions ran concurrently during driving mode (the overlay's own
 * provider, plus driving mode's raw listener), each waking the GPS chip independently.
 *
 * [setUpdateCriteria] lets the update rate be dialed down while idly browsing the map (no
 * need for sub-second fixes just to glance at street colors) and back up while driving mode
 * needs smooth, frequent fixes for rotation and following.
 */
class SingleSourceLocationProvider(
    private val locationManager: LocationManager
) : org.osmdroid.views.overlay.mylocation.IMyLocationProvider {
    private var consumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer? = null
    private var minTimeMs = IDLE_MIN_TIME_MS
    private var minDistanceM = IDLE_MIN_DISTANCE_M

    /** Extra hook for driving mode's heading/speed logic — set to null when not needed. */
    var onRawLocation: ((android.location.Location) -> Unit)? = null

    private val listener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            consumer?.onLocationChanged(location, this@SingleSourceLocationProvider)
            onRawLocation?.invoke(location)
        }
    }

    private fun requestUpdates(): Boolean = try {
        locationManager.removeUpdates(listener) // safe no-op if not currently registered
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, listener)
        true
    } catch (e: SecurityException) {
        false
    }

    /** Re-registers with new update criteria. Only takes effect immediately if the provider
     *  is currently running; otherwise the new criteria apply on the next start. */
    fun setUpdateCriteria(newMinTimeMs: Long, newMinDistanceM: Float) {
        minTimeMs = newMinTimeMs
        minDistanceM = newMinDistanceM
        if (consumer != null) requestUpdates()
    }

    override fun startLocationProvider(myLocationConsumer: org.osmdroid.views.overlay.mylocation.IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        return requestUpdates()
    }

    override fun stopLocationProvider() {
        locationManager.removeUpdates(listener)
        consumer = null
    }

    override fun getLastKnownLocation(): android.location.Location? = try {
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (e: SecurityException) {
        null
    }

    override fun destroy() {
        stopLocationProvider()
    }

    companion object {
        const val IDLE_MIN_TIME_MS = 5000L
        const val IDLE_MIN_DISTANCE_M = 10f
        const val DRIVING_MIN_TIME_MS = 1000L
        // 0, not a distance filter: with a 2 m filter Android stops calling back while the car is
        // stationary even though GPS is healthy, so a long red light looked like lost signal (a
        // tunnel). The 1 s time interval still bounds the rate; jitter while stopped is already
        // handled by holding the last good heading below BEARING_TRUST_SPEED_MPS.
        const val DRIVING_MIN_DISTANCE_M = 0f
    }
}

fun buildStadiaTileSource(context: Context): OnlineTileSourceBase = object : OnlineTileSourceBase(
    "StadiaAlidadeSmooth", 0, 20, 256, ".png",
    arrayOf("https://tiles.stadiamaps.com/tiles/alidade_smooth/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$x/$y.png?api_key=${ApiKeys.stadiaMapsKey(context)}"
    }
}

// Stadia's dark counterpart to alidade_smooth, used for map "Night" style / the dark half
// of "Automatic". Style name and URL format confirmed correct against Stadia's docs — if
// this doesn't load, check that your API key's property has this style enabled; some keys
// are scoped more narrowly than Stadia's general docs suggest.
fun buildStadiaDarkTileSource(context: Context): OnlineTileSourceBase = object : OnlineTileSourceBase(
    "StadiaAlidadeSmoothDark", 0, 20, 256, ".png",
    arrayOf("https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$x/$y.png?api_key=${ApiKeys.stadiaMapsKey(context)}"
    }
}

fun offsetPolylineForSide(points: List<LatLng>, side: String, offsetMeters: Double = 8.0): List<LatLng> {
    if (points.size < 2) return points
    val first = points.first(); val last = points.last()
    val avgLatRad = Math.toRadians((first.lat + last.lat) / 2)
    val latScale = 111320.0
    val lngScale = 111320.0 * cos(avgLatRad)
    val dxMeters = (last.lng - first.lng) * lngScale
    val dyMeters = (last.lat - first.lat) * latScale
    val length = sqrt(dxMeters * dxMeters + dyMeters * dyMeters)
    if (length == 0.0) return points
    val perpXMeters = -dyMeters / length
    val perpYMeters = dxMeters / length
    val sign = if (side.equals("R", ignoreCase = true)) -1.0 else 1.0
    val offsetLat = (perpYMeters * offsetMeters * sign) / latScale
    val offsetLng = (perpXMeters * offsetMeters * sign) / lngScale
    return points.map { LatLng(it.lat + offsetLat, it.lng + offsetLng) }
}

fun trimPolylineEnds(points: List<LatLng>, trimMeters: Double = 7.0): List<LatLng> {
    if (points.size < 2) return points
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val avgLatRad = Math.toRadians((a.lat + b.lat) / 2)
        val latScale = 111320.0
        val lngScale = 111320.0 * cos(avgLatRad)
        val dx = (b.lng - a.lng) * lngScale
        val dy = (b.lat - a.lat) * latScale
        return sqrt(dx * dx + dy * dy)
    }
    fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    val totalLength = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
    if (totalLength <= trimMeters * 2) return points
    fun trimFromStart(pts: List<LatLng>, trim: Double): List<LatLng> {
        var remaining = trim
        for (i in 0 until pts.size - 1) {
            val d = distanceMeters(pts[i], pts[i + 1])
            if (d >= remaining) {
                val trimmedPoint = interpolate(pts[i], pts[i + 1], remaining / d)
                return listOf(trimmedPoint) + pts.subList(i + 1, pts.size)
            }
            remaining -= d
        }
        return pts
    }
    val trimmedFromStart = trimFromStart(points, trimMeters)
    return trimFromStart(trimmedFromStart.reversed(), trimMeters).reversed()
}

// Tap-feedback halo: grows from 0 to TAPPED_HALO_PEAK_WIDTH over TAPPED_HALO_GROW_STEPS
// steps, then settles back to TAPPED_HALO_SETTLE_WIDTH for as long as the detail sheet stays
// open. A glow drawn UNDERNEATH the status-colored line (added at overlay index 0, same
// convention as ParkedHighlightPolyline) rather than a recolor, so SAFE/SOON/IMMINENT/ACTIVE
// stays fully legible on top of it. Sized deliberately large/opaque to read as a clear "this
// one" pop rather than a subtle glow.
private const val TAPPED_HALO_PEAK_WIDTH = 60f
private const val TAPPED_HALO_SETTLE_WIDTH = 32f
private const val TAPPED_HALO_GROW_STEPS = 8
private const val TAPPED_HALO_STEP_MS = 18L

/**
 * Draws the tap-feedback halo under the tapped segment's line. Purely visual (no click
 * listener of its own) so it never intercepts a tap meant for the hit-line underneath.
 *
 * @param isMapDark Same day/night signal MapScreen already resolves for tile source and
 * icon tinting (isDarkTheme || suspectedTunnel) — the halo is black against the light map
 * style and white against the dark one, for the same contrast reason the location dot and
 * gear/overflow icons are tinted this way rather than a fixed color.
 *
 * Call [clearTappedSegmentHighlight] when the detail sheet is dismissed. Returns the
 * animation [Job] so the caller can cancel it if a new tap comes in before this one settles
 * (clearTappedSegmentHighlight removes the overlay either way, but cancelling also stops a
 * now-pointless coroutine from continuing to mutate a Paint no one can see).
 */
fun showTappedSegmentHighlight(
    mapView: MapView,
    segment: StreetSegment,
    isMapDark: Boolean,
    scope: CoroutineScope
): Job? {
    clearTappedSegmentHighlight(mapView)

    // Same offset + trim as the segment's own visible/hit lines (loadAndDrawSegments), so
    // the halo sits exactly under the real line rather than a slightly-off approximation.
    val offsetPoints = offsetPolylineForSide(segment.points, segment.cnnRightLeft)
    val trimmedPoints = trimPolylineEnds(offsetPoints, trimMeters = 8.0)
    if (trimmedPoints.size < 2) return null
    val geoPoints = trimmedPoints.map { GeoPoint(it.lat, it.lng) }

    val halo = TappedSegmentHaloPolyline().apply {
        setPoints(geoPoints)
        outlinePaint.color = if (isMapDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.alpha = 160
        outlinePaint.strokeWidth = 0f
        setOnClickListener { _, _, _ -> false }
    }
    mapView.overlays.add(0, halo) // underneath everything else, same as ParkedHighlightPolyline

    return scope.launch {
        for (step in 1..TAPPED_HALO_GROW_STEPS) {
            halo.outlinePaint.strokeWidth = TAPPED_HALO_PEAK_WIDTH * step / TAPPED_HALO_GROW_STEPS
            mapView.invalidate()
            delay(TAPPED_HALO_STEP_MS)
        }
        halo.outlinePaint.strokeWidth = TAPPED_HALO_SETTLE_WIDTH
        mapView.invalidate()
    }
}

/** Removes the tap-feedback halo, if present. Safe to call even if none is showing. */
fun clearTappedSegmentHighlight(mapView: MapView) {
    val hadAny = mapView.overlays.removeAll { it is TappedSegmentHaloPolyline }
    if (hadAny) mapView.invalidate()
}

/**
 * Builds a small pill-shaped label icon: a rounded rectangle filled with [backgroundColorInt]
 * (the segment's own status color, so the label visually ties to its colored line) with
 * [text] in bold white on top. Sized to fit the text tightly rather than a fixed size, since
 * the text is deliberately short ("45m", "2h", "2d") — see formatShortCountdown.
 */
// [compact] shrinks the text/padding a notch — used for the RPP zone label so it takes less
// room than the sweep countdown labels while still showing its zone+time text (unlike the
// meter badge, which drops text/buildCountdownLabelIcon entirely in favor of a small icon —
// see buildMeterBadgeIcon — since there can be many more meters than RPP blocks in one view).
private fun buildCountdownLabelIcon(context: Context, text: String, backgroundColorInt: Int, compact: Boolean = false): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = (if (compact) 9f else 11f) * density
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val paddingPx = (if (compact) 3f else 5f) * density
    val textWidth = textPaint.measureText(text)
    val textHeight = textPaint.descent() - textPaint.ascent()
    val width = (textWidth + paddingPx * 2).toInt().coerceAtLeast(1)
    val height = (textHeight + paddingPx * 2).toInt().coerceAtLeast(1)

    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColorInt
        style = android.graphics.Paint.Style.FILL
    }
    val cornerRadius = height / 2f
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
        cornerRadius, cornerRadius, bgPaint
    )

    val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, width / 2f, textY, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * A small filled circle with a "P" — deliberately just an icon, no text, unlike
 * buildCountdownLabelIcon's pill: meters are spaced every ~20ft along a block, so a real block
 * can have a dozen-plus badges in view at once, and a full "$ Metered" pill per one was
 * cluttering the map. No per-meter time/limit text either (unlike the RPP zone label, which
 * keeps its zone+countdown) — a glance at the map badge only needs to answer "is this block
 * metered," not each individual post's exact numbers; the timer/limit detail already lives in
 * the AskingForPin/AskingForMeterTimer flow once you've actually picked a spot.
 */
private fun buildMeterBadgeIcon(context: Context, colorInt: Int): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    val diameterPx = (16f * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(diameterPx, diameterPx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = diameterPx / 2f

    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center, bgPaint)

    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 10f * density
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText("P", center, textY, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/**
 * Draws nearby street segments as tappable polylines.
 *
 * @param isPinDropActive Checked on every tap. When true, the segment hit-lines yield
 *   (return false from their click listener) so the tap falls through to
 *   [TapCaptureOverlay] instead of opening a segment detail sheet — this is what lets
 *   you drop a pin directly on top of a highlighted/parked segment.
 * @param thresholds Color cutoffs (days until sweep) used to compute each segment's
 *   [SweepStatus], sourced from Settings rather than hardcoded.
 * @param statusColors The four sweep-status colors (pairwise distinct — see
 *   [swapAssignment]), sourced from Settings rather than hardcoded.
 * @param showCountdownLabels When true, IMMINENT/ACTIVE_OR_VERY_SOON segments get a small
 *   color-matched pill label at their midpoint showing time-until-starts/ends (e.g. "45m",
 *   "2h"). Uses the same visibility rule as the segment lines themselves — anything within
 *   [radiusDegrees] of [centerPoint], no separate zoom gate — plus a hard cap, since a dense
 *   citywide sweep morning could otherwise produce dozens of overlapping labels.
 * @param onSegmentClick Fired when a segment hit-line is tapped, but only when pin-drop
 *   mode is inactive and the map is zoomed in past [MIN_ZOOM_FOR_SEGMENT_TAP].
 */
suspend fun loadAndDrawSegments(
    mapView: MapView,
    context: Context,
    centerPoint: GeoPoint,
    radiusDegrees: Double = 0.005,
    isPinDropActive: () -> Boolean = { false },
    thresholds: SweepThresholds = SweepThresholds(),
    statusColors: SweepStatusColors = SweepStatusColors(),
    showCountdownLabels: Boolean = false,
    showRppZoneLabels: Boolean = true,
    showMeterBadges: Boolean = true,
    onSegmentClick: (StreetSegment) -> Unit = {}
): Int {
    val db = AppDatabase.getInstance(context)
    val nearby = db.streetSegmentDao().getNearby(
        minLat = centerPoint.latitude - radiusDegrees,
        maxLat = centerPoint.latitude + radiusDegrees,
        minLng = centerPoint.longitude - radiusDegrees,
        maxLng = centerPoint.longitude + radiusDegrees
    )

    // getNearby already COALESCEs override data into each segment's schedule fields (so
    // coloring/status is correct either way), but that merge is transparent by design — it
    // gives no way to tell a hand-corrected block apart from a raw DataSF one just by looking
    // at the StreetSegment. Fetching the override id set separately, once per draw, lets the
    // line itself flag "this doesn't match what DataSF says" without touching the DAO's
    // merge query or any of its other callers (notification scheduling, the widget, etc.).
    val overriddenIds = db.scheduleOverrideDao().getAll().map { it.blockSweepId }.toSet()

    // TappedSegmentHaloPolyline is excluded for the same reason ParkedHighlightPolyline is:
    // it needs to survive a pan-triggered redraw while the detail sheet is still open,
    // rather than vanishing mid-animation or the instant you nudge the map.
    mapView.overlays.removeAll { it is Polyline && it !is ParkedHighlightPolyline && it !is TappedSegmentHaloPolyline }
    mapView.overlays.removeAll { it is CountdownLabelMarker }
    mapView.overlays.removeAll { it is RppZoneLabelMarker }
    mapView.overlays.removeAll { it is MeterBadgeMarker }

    // Skips the query entirely when disabled in Settings, not just the drawing — no point
    // fetching rows that'll never be shown.
    val nearbyMeters = if (showMeterBadges) {
        db.meteredZoneDao().getNearby(
            minLat = centerPoint.latitude - radiusDegrees,
            maxLat = centerPoint.latitude + radiusDegrees,
            minLng = centerPoint.longitude - radiusDegrees,
            maxLng = centerPoint.longitude + radiusDegrees
        )
    } else {
        emptyList()
    }

    // Skips the query entirely when disabled in Settings, not just the drawing — no point
    // fetching rows that'll never be shown.
    val nearbyRpp = if (showRppZoneLabels) {
        db.rppZoneRegulationDao().getNearby(
            minLat = centerPoint.latitude - radiusDegrees,
            maxLat = centerPoint.latitude + radiusDegrees,
            minLng = centerPoint.longitude - radiusDegrees,
            maxLng = centerPoint.longitude + radiusDegrees
        )
    } else {
        emptyList()
    }

    val now = sfNow()

    // Computed once per segment, shared between line-drawing and label-selection below —
    // avoids recomputing offset/trim/status twice, and lets label selection see every
    // segment's data before deciding which ones actually get a label.
    data class PreparedSegment(
        val segment: StreetSegment,
        val geoPoints: List<GeoPoint>,
        val trimmedPoints: List<LatLng>,
        val status: SweepStatus
    )

    val allPrepared = nearby.mapNotNull { segment ->
        if (segment.points.size < 2) return@mapNotNull null
        val offsetPoints = offsetPolylineForSide(segment.points, segment.cnnRightLeft)
        val trimmedPoints = trimPolylineEnds(offsetPoints, trimMeters = 8.0)
        PreparedSegment(
            segment = segment,
            geoPoints = trimmedPoints.map { GeoPoint(it.lat, it.lng) },
            trimmedPoints = trimmedPoints,
            status = sweepStatus(segment, now, thresholds)
        )
    }

    // DataSF sometimes has multiple rows for the exact same physical curb-side (different
    // schedule variants — cnn is not unique per row, unlike blockSweepId). Drawing every one
    // independently let whichever was added LAST visually win with no regard for urgency —
    // which could paint a falsely reassuring SAFE-colored line over a curb that another row
    // says is actually IMMINENT, while that other row's own countdown label still correctly
    // appeared on top of it (the label was right; the line underneath it was wrong). Grouping
    // by curb identity and keeping only the single most-urgent row per curb fixes both at
    // once, since the line and any label on it now always come from the same chosen row.
    val prepared = allPrepared
        .groupBy { "${it.segment.cnn}|${it.segment.cnnRightLeft}" }
        // Most urgent row wins; on a tie prefer a real weekday row over an unparseable "HOLIDAY" one, so the line
        // and the tap target never come from a row that says "no upcoming cleaning" for a curb that is swept.
        .map { (_, group) -> CurbSchedule.pickByUrgency(group, { it.segment }, { it.status }) }

    prepared.forEach { p ->
        // Wide, invisible line — purely for a bigger, easier tap target.
        // strokeWidth is in screen pixels, so this is the effective tap radius.
        val hitLine = Polyline().apply {
            setPoints(p.geoPoints)
            outlinePaint.color = android.graphics.Color.TRANSPARENT
            outlinePaint.strokeWidth = 90f
            setOnClickListener { _, mv, _ ->
                when {
                    // Pin-drop mode wins: let TapCaptureOverlay see this tap instead.
                    isPinDropActive() -> false
                    // Too zoomed out — a pan/zoom over a wide area shouldn't select a block.
                    mv.zoomLevelDouble < MIN_ZOOM_FOR_SEGMENT_TAP -> false
                    else -> {
                        onSegmentClick(p.segment)
                        true
                    }
                }
            }
        }
        mapView.overlays.add(hitLine)

        // Thin, colored line — purely visual
        val visibleLine = Polyline().apply {
            setPoints(p.geoPoints)
            outlinePaint.color = sweepStatusColor(p.status, statusColors).toArgb()
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.alpha = 220
            // Manually-corrected blocks get a dashed rather than solid line — a glanceable
            // "this doesn't match raw DataSF" flag on the map itself, not just once you tap
            // into the detail sheet. Dash sizing is in screen px, same unit as strokeWidth.
            if (p.segment.blockSweepId in overriddenIds) {
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(28f, 18f), 0f)
            }
        }
        mapView.overlays.add(visibleLine)
    }

    // Shared across all three badge kinds below (countdown, RPP zone, meter) so they never
    // visually stack on top of each other — checked and updated in priority order (countdown
    // first, then RPP, then meter: most safety-critical/least numerous wins a collision), and
    // also naturally thins out same-kind density (e.g. several meters on one block), since a
    // later same-type candidate checks against earlier ones from its own loop too. See
    // BADGE_SUPPRESSION_RADIUS_DP's doc comment for why this is screen-space, not geographic.
    val placedBadgeScreenPoints = mutableListOf<android.graphics.Point>()
    val badgeSuppressionRadiusPx = BADGE_SUPPRESSION_RADIUS_DP * context.resources.displayMetrics.density
    // Returns true (and reserves the spot) if [geoPoint] isn't too close to any badge already
    // placed this draw pass; false (and draws nothing) if it is.
    fun tryPlaceBadge(geoPoint: GeoPoint): Boolean {
        val screenPoint = mapView.projection.toPixels(geoPoint, null)
        val tooClose = placedBadgeScreenPoints.any { existing ->
            val dx = (existing.x - screenPoint.x).toDouble()
            val dy = (existing.y - screenPoint.y).toDouble()
            sqrt(dx * dx + dy * dy) < badgeSuppressionRadiusPx
        }
        if (tooClose) return false
        placedBadgeScreenPoints.add(screenPoint)
        return true
    }

    if (showCountdownLabels) {
        val centerLatLng = LatLng(centerPoint.latitude, centerPoint.longitude)

        prepared
            .filter { it.status == SweepStatus.IMMINENT || it.status == SweepStatus.ACTIVE_OR_VERY_SOON }
            .map { p ->
                val midpoint = midpointAlongPath(p.trimmedPoints)
                Triple(p, midpoint, distanceMetersBetween(centerLatLng, midpoint))
            }
            // Prioritize by distance to the viewport center, not arbitrary DB order — if
            // there are more eligible segments than MAX_COUNTDOWN_LABELS, the closest ones
            // (most likely what the user is actually looking at) win, not whichever happened
            // to be truncated first.
            .sortedBy { (_, _, distance) -> distance }
            .take(MAX_COUNTDOWN_LABELS)
            .forEach { (p, midpoint, _) ->
                val countdownMillis = when (p.status) {
                    SweepStatus.IMMINENT -> NextSweepCalculator.nextSweepDateTime(p.segment, now)
                        ?.let { java.time.Duration.between(now, it).toMillis() }
                    SweepStatus.ACTIVE_OR_VERY_SOON -> NextSweepCalculator.activeWindowEndDateTime(p.segment, now)
                        ?.let { java.time.Duration.between(now, it).toMillis() }
                    else -> null
                }
                val geoPoint = GeoPoint(midpoint.lat, midpoint.lng)
                if (countdownMillis != null && tryPlaceBadge(geoPoint)) {
                    val label = CountdownLabelMarker(mapView).apply {
                        position = geoPoint
                        icon = buildCountdownLabelIcon(
                            context,
                            formatShortCountdown(countdownMillis),
                            sweepStatusColor(p.status, statusColors).toArgb()
                        )
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> false } // purely visual, same as car/location pins
                    }
                    mapView.overlays.add(label)
                }
            }
    }

    // RPP zone labels — independent of showCountdownLabels (which is gated to IMMINENT/ACTIVE
    // sweep segments only): whether a block requires a residential parking permit doesn't
    // depend on sweep urgency at all, and previously had no on-map indication whatsoever —
    // only visible by tapping into the detail sheet one block at a time. Only shown while the
    // restriction is actually in effect right now (correct day AND inside its hours) — a label
    // that never disappeared outside those hours would look like a permanent marker for
    // something that only sometimes applies, which is worse than no marker at all.
    run {
        val centerLatLng = LatLng(centerPoint.latitude, centerPoint.longitude)
        nearbyRpp
            .filter { it.points.size >= 2 }
            .mapNotNull { regulation ->
                val remainingMillis = activeRppWindowEndMillis(regulation, now) ?: return@mapNotNull null
                val midpoint = midpointAlongPath(regulation.points)
                Triple(regulation, midpoint, remainingMillis) to distanceMetersBetween(centerLatLng, midpoint)
            }
            // Same rationale as the countdown labels above — closest-to-viewport-center wins
            // when there are more RPP-regulated blocks in view than the cap allows.
            .sortedBy { (_, distance) -> distance }
            .take(MAX_RPP_ZONE_LABELS)
            .forEach { (triple, _) ->
                val (regulation, midpoint, remainingMillis) = triple
                val geoPoint = GeoPoint(midpoint.lat, midpoint.lng)
                if (!tryPlaceBadge(geoPoint)) return@forEach
                val label = RppZoneLabelMarker(mapView).apply {
                    position = geoPoint
                    // Compact, and without the redundant "RPP" word — the violet color already
                    // reads as "this is the permit indicator" once meters have their own,
                    // entirely different (icon-only) badge style, so it doesn't need spelling
                    // out too. Still shows the zone letter(s) and remaining time.
                    icon = buildCountdownLabelIcon(
                        context,
                        "${regulation.zoneLetterSet().sorted().joinToString("/")} · ${formatShortCountdown(remainingMillis)}",
                        RPP_ZONE_LABEL_COLOR_INT,
                        compact = true
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> false } // purely visual, same as the countdown labels
                }
                mapView.overlays.add(label)
            }
    }

    // Meter badges — independent of sweep/RPP status entirely: a curb can be swept AND
    // RPP-zoned AND metered all at once (three separate axes, not a replacement status), so
    // this layers on top rather than competing with the polyline color. Only shown for a
    // confident, currently-enforced match (or one with unknown hours — see
    // isMeterEnforcedOrUnknown), same reasoning as the RPP zone labels: a badge that never
    // disappeared outside enforced hours would misrepresent a spot that's genuinely free right now.
    run {
        val centerLatLng = LatLng(centerPoint.latitude, centerPoint.longitude)
        nearbyMeters
            .filter { isMeterEnforcedOrUnknown(it, now) }
            .map { zone -> zone to distanceMetersBetween(centerLatLng, LatLng(zone.lat, zone.lng)) }
            .sortedBy { (_, distance) -> distance }
            .take(MAX_METER_BADGES)
            .forEach { (zone, _) ->
                val geoPoint = GeoPoint(zone.lat, zone.lng)
                if (!tryPlaceBadge(geoPoint)) return@forEach
                val badge = MeterBadgeMarker(mapView).apply {
                    position = geoPoint
                    icon = buildMeterBadgeIcon(context, METER_BADGE_COLOR_INT)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> false } // purely visual, same as the other labels
                }
                mapView.overlays.add(badge)
            }
    }

    mapView.invalidate()
    // Distinct curbs actually drawn near this viewport — used by MapScreen to tell "no DataSF
    // coverage here" (this is > 0 elsewhere but 0 for this pan) apart from "nothing's synced
    // yet at all" (the DB itself is empty), so it can show the right first-run empty state.
    return prepared.size
}

/**
 * loadAndDrawSegments appends freshly-drawn street-segment polylines to the END of
 * mapView.overlays — which osmdroid renders last = on top of whatever was already there,
 * including any car pins, the parked-sidewalk highlight, and saved-location markers drawn by
 * an earlier refreshParkedCarOverlays/refreshSavedLocationOverlays call. Called on its own
 * (as it is on every pan/zoom debounce, and every ~3s while driving), a segment redraw
 * silently buries those markers under the fresh polylines. This wrapper re-runs both marker
 * refreshes immediately after, so they're always re-appended to the top.
 *
 * [locationOverlay], if passed, is re-lifted to the very end (on top of everything, including
 * the just-refreshed car/saved-location markers) after the redraw. Without this, the live
 * "you are here" dot — added to mapView.overlays once, near the bottom, when the map is first
 * created — permanently loses the z-order fight against any segment line or marker that
 * happens to overlap its position, since nothing else ever moves it back on top. This isn't
 * just a first-load issue: every debounced pan/zoom reload re-buries it the same way, which is
 * why it's a required part of this wrapper rather than a one-off fix at map creation. Always
 * pass the map's MyLocationNewOverlay here — including for the very first load — there's no
 * longer a "one-time initial load" exception to calling loadAndDrawSegments directly; use this
 * wrapper everywhere segments need (re)drawing.
 */
suspend fun reloadSegmentsAndMarkers(
    mapView: MapView,
    context: Context,
    centerPoint: GeoPoint,
    radiusDegrees: Double = 0.005,
    isPinDropActive: () -> Boolean = { false },
    thresholds: SweepThresholds = SweepThresholds(),
    statusColors: SweepStatusColors = SweepStatusColors(),
    showCountdownLabels: Boolean = false,
    showRppZoneLabels: Boolean = true,
    showMeterBadges: Boolean = true,
    onSegmentClick: (StreetSegment) -> Unit = {},
    locationOverlay: MyLocationNewOverlay? = null
): Int {
    val nearbyCount = loadAndDrawSegments(
        mapView, context, centerPoint,
        radiusDegrees, isPinDropActive, thresholds, statusColors, showCountdownLabels, showRppZoneLabels, showMeterBadges, onSegmentClick
    )
    refreshParkedCarOverlays(mapView, context)
    refreshSavedLocationOverlays(mapView, context)
    locationOverlay?.let { overlay ->
        if (mapView.overlays.remove(overlay)) {
            mapView.overlays.add(overlay)
            mapView.invalidate()
        }
    }
    return nearbyCount
}

// Not private: reused by ParkingMatcher's findSafeSavedLocation (matching against a saved
// location's single lat/lng) and MeteredZoneMatcher (matching a meter point feature against a
// parked point) — both need this same flat-projection point-to-point distance.
fun distanceMetersBetween(a: LatLng, b: LatLng): Double {
    val avgLatRad = Math.toRadians((a.lat + b.lat) / 2)
    val latScale = 111320.0
    val lngScale = 111320.0 * cos(avgLatRad)
    val dx = (b.lng - a.lng) * lngScale
    val dy = (b.lat - a.lat) * latScale
    return sqrt(dx * dx + dy * dy)
}

/**
 * True midpoint along the polyline's actual path length — not a naive average of its first
 * and last point, which drifts off the real line on any block with noticeable curvature and
 * could land a label on top of a neighboring parallel segment instead of its own. Not private:
 * also used by saveParkedState as the RPP-match anchor for a confirmed segment (see its own
 * comment) — the same "representative point of this block" this function already serves as
 * for label placement.
 */
fun midpointAlongPath(points: List<LatLng>): LatLng {
    if (points.size < 2) return points.firstOrNull() ?: LatLng(0.0, 0.0)
    val totalLength = points.zipWithNext().sumOf { (a, b) -> distanceMetersBetween(a, b) }
    if (totalLength == 0.0) return points.first()
    val halfLength = totalLength / 2.0
    var accumulated = 0.0
    for (i in 0 until points.size - 1) {
        val segLength = distanceMetersBetween(points[i], points[i + 1])
        if (accumulated + segLength >= halfLength) {
            val t = if (segLength == 0.0) 0.0 else (halfLength - accumulated) / segLength
            return LatLng(
                points[i].lat + (points[i + 1].lat - points[i].lat) * t,
                points[i].lng + (points[i + 1].lng - points[i].lng) * t
            )
        }
        accumulated += segLength
    }
    return points.last()
}