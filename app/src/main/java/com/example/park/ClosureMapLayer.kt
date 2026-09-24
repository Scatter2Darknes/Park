package com.example.park

import android.content.Context
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.format.DateTimeFormatter

/*
 * What the map shows about street closures (docs/park-closures-spec.md §4 "Map layer").
 *
 * Two sources, merged per block:
 *  - The citywide layer: every closure in view that is on now or starts before the end of the day
 *    CLOSURE_MAP_HORIZON_DAYS from today (closureMapHorizonEndMillis). Only with Tier 2 background
 *    sync on AND the layer's own switch on.
 *  - The parked car's own closures (the ones behind its banner line), drawn whenever closures are
 *    enabled at all, even Tier 1: they come from the same data the banner already shows.
 * The drawing itself (osmdroid overlays) is in MapUtils.kt: drawClosureOverlays.
 */

/** How far ahead the closures layer looks, in whole days. One constant (spec: tune from first-seen data). */
const val CLOSURE_MAP_HORIZON_DAYS = 7L

/** Roughly the same span in ms, for the parked car's own closures (see carClosuresForMap). */
const val CLOSURE_MAP_HORIZON_MILLIS = CLOSURE_MAP_HORIZON_DAYS * 24 * 60 * 60_000L

/**
 * The layer's cut-off: the END of the day [CLOSURE_MAP_HORIZON_DAYS] days from today, San Francisco
 * time (midnight starting the day after), so the label's date is covered in full. A plain "now + 7
 * days" cut-off would stop partway through the labelled day and leave its later closures off a map
 * that says it shows them. Always SF time, whatever the phone's time zone: these are SF events.
 */
fun closureMapHorizonEndMillis(nowMillis: Long): Long =
    Instant.ofEpochMilli(nowMillis).atZone(SF_ZONE).toLocalDate()
        .plusDays(CLOSURE_MAP_HORIZON_DAYS + 1).atStartOfDay(SF_ZONE).toInstant().toEpochMilli()

/**
 * One block's closures, drawn as a single line with a single badge. A recurring closure arrives as
 * one row per day on the same block (same CNN), so rows are merged here rather than stacked.
 * [closures] is sorted by start; [points] is the block's centerline.
 */
data class ClosureBlock(
    val cnn: String,
    val closures: List<StreetClosure>,
    val points: List<LatLng>,
    val affectsParkedCar: Boolean
) {
    val next: StreetClosure get() = closures.first()
}

/** The closures among a parked car's [hits] worth highlighting: the same windows the banner uses. */
fun carClosuresForMap(hits: List<ClosureHit>, nowMillis: Long, leadMillis: Long): List<StreetClosure> =
    hits.filter { hit ->
        val c = hit.closure
        c.endMillis > nowMillis && when (hit.impact) {
            ClosureImpact.BLOCKED_IN -> c.startMillis <= nowMillis + maxOf(CLOSURE_MAP_HORIZON_MILLIS, leadMillis)
            ClosureImpact.NEARBY -> c.startMillis <= nowMillis + leadMillis
        }
    }.map { it.closure }

/**
 * Merges the citywide [layerClosures] (already limited to the viewport; empty when the layer is off)
 * with the parked cars' [carClosures] into one [ClosureBlock] per block. Layer rows outside the
 * horizon or already over are dropped; a block with no usable geometry can't be drawn and is skipped.
 */
fun groupClosuresForMap(
    layerClosures: List<StreetClosure>,
    carClosures: List<StreetClosure>,
    nowMillis: Long,
    horizonEndMillis: Long = closureMapHorizonEndMillis(nowMillis)
): List<ClosureBlock> {
    val carIds = carClosures.map { it.objectId }.toSet()
    val inHorizon = layerClosures.filter { it.endMillis > nowMillis && it.startMillis < horizonEndMillis }
    return (inHorizon + carClosures)
        .distinctBy { it.objectId }
        .groupBy { it.cnn }
        .mapNotNull { (cnn, rows) ->
            val sorted = rows.sortedBy { it.startMillis }
            val points = sorted.firstOrNull { it.points.size >= 2 }?.points ?: return@mapNotNull null
            ClosureBlock(cnn, sorted, points, affectsParkedCar = rows.any { it.objectId in carIds })
        }
}

private val HORIZON_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * The layer's label, so an empty map reads as "none this week", not "none ever": "Closures through
 * Sep 30" — the last day the cut-off (closureMapHorizonEndMillis) covers in full.
 */
fun closureHorizonLabel(nowMillis: Long): String =
    "Closures through " + Instant.ofEpochMilli(closureMapHorizonEndMillis(nowMillis) - 1).atZone(SF_ZONE).format(HORIZON_DATE)

/** The badge on a closed block: "🚧 now" while it's on, else time until it starts ("🚧 2d"). */
fun closureBadgeText(block: ClosureBlock, nowMillis: Long): String =
    "🚧 " + if (block.next.startMillis <= nowMillis) "now" else formatShortCountdown(block.next.startMillis - nowMillis)

/** The details shown when a closure badge is tapped. Up to [maxWindows] upcoming windows, then "+N more". */
fun closureDetailLines(block: ClosureBlock, nowMillis: Long, maxWindows: Int = 5): List<String> {
    val c = block.next
    val lines = mutableListOf<String>()
    lines += closurePlaceLabel(c)
    listOfNotNull(c.caseName, c.type).distinct().takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
    lines += when (c.vehicleImpact) {
        "some-lanes-closed" -> "Some lanes closed"
        "all-lanes-open" -> "Lanes open (sidewalk or curb work)"
        else -> "Closed to traffic"
    }
    val upcoming = block.closures.filter { it.endMillis > nowMillis }
    upcoming.take(maxWindows).forEach { lines += "• " + formatClosureWindow(it, nowMillis) }
    if (upcoming.size > maxWindows) lines += "+${upcoming.size - maxWindows} more"
    return lines
}

/**
 * Loads what the map should show near [center]: nothing when every closure feature is off; the
 * parked cars' closures whenever closures are on; plus the citywide layer with Tier 2 + its switch.
 */
suspend fun loadClosureBlocksForMap(context: Context, center: LatLng, radiusDegrees: Double): List<ClosureBlock> {
    val settings = SettingsRepository(context)
    if (!settings.closuresEnabled()) return emptyList()
    val db = AppDatabase.getInstance(context)
    val now = System.currentTimeMillis()
    val lead = closureLeadMillis(context)
    // A closure's centroid can be half a block from the part of it that's in view.
    val box = radiusDegrees + 0.004
    val layer = if (settings.closureMapLayerOn()) {
        db.streetClosureDao().getNearby(now, center.lat - box, center.lat + box, center.lng - box, center.lng + box)
    } else emptyList()
    val carClosures = db.parkedStateDao().getAll().flatMap { parked ->
        val point = if (parked.exactPinLat != null && parked.exactPinLng != null) LatLng(parked.exactPinLat, parked.exactPinLng)
        else LatLng(parked.parkedLat, parked.parkedLng)
        carClosuresForMap(findClosuresForParkedCar(context, point, parked.segmentBlockSweepId, now), now, lead)
    }
    return groupClosuresForMap(layer, carClosures, now)
}
