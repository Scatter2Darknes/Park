package com.example.park

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.views.MapView
import java.io.File

/**
 * Bulk-downloads tiles for the map's CURRENT visible area across a small zoom range, for
 * offline use. Writes into osmdroid's own on-disk tile cache — the same cache the map reads
 * from automatically during normal use — so there's no separate "offline mode" to toggle; a
 * cached tile just loads instantly with no network regardless of how it got there.
 *
 * osmdroid's CacheManager API has been stable for a long time, but this can't be
 * compile-tested against your exact osmdroid version from here — if CacheManagerCallback's
 * method signatures don't match what you have, that's the first thing to check.
 *
 * The progress/complete/error callbacks fire from osmdroid's internal download thread, not
 * the calling coroutine — this is fine, since Compose's mutableStateOf writes are documented
 * as safe from any thread and will trigger recomposition correctly regardless.
 */
fun downloadOfflineTiles(
    context: Context,
    mapView: MapView,
    zoomPadding: Int = 2,
    onEstimate: (tileCount: Int) -> Unit,
    onProgress: (downloaded: Int, total: Int) -> Unit,
    onComplete: () -> Unit,
    onError: (errors: Int) -> Unit
) {
    val boundingBox = mapView.boundingBox
    val currentZoom = mapView.zoomLevelDouble.toInt()
    val minZoom = (currentZoom - zoomPadding).coerceAtLeast(1)
    val maxZoom = (currentZoom + zoomPadding).coerceAtMost(20)

    val cacheManager = CacheManager(mapView)
    val tileCount = cacheManager.possibleTilesInArea(boundingBox, minZoom, maxZoom)
    onEstimate(tileCount)

    cacheManager.downloadAreaAsync(
        context, boundingBox, minZoom, maxZoom,
        object : CacheManager.CacheManagerCallback {
            override fun onTaskComplete() {
                onComplete()
            }

            override fun onTaskFailed(errors: Int) {
                onError(errors)
            }

            override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                onProgress(progress, tileCount)
            }

            override fun downloadStarted() {}

            override fun setPossibleTilesInArea(total: Int) {}
        }
    )
}

/** Clears osmdroid's entire on-disk tile cache (online-fetched and offline-downloaded tiles
 *  alike) — a standalone operation, unlike download, since it doesn't need a live MapView. */
fun clearTileCache() {
    SqlTileWriter().purgeCache()
}

/** Approximate current on-disk tile cache size in bytes, for display in Settings. osmdroid
 *  stores its cache as a single SQLite file, so this is just that file's size on disk. */
fun tileCacheSizeBytes(): Long {
    val cacheDir = Configuration.getInstance().osmdroidTileCache
    val dbFile = File(cacheDir, "cache.db")
    return if (dbFile.exists()) dbFile.length() else 0L
}