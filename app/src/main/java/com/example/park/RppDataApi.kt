package com.example.park

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// SFMTA's own ArcGIS mirror of DataSF's "Parking regulations (except non-metered color curb)"
// feed (hi6h-neyh) — used directly rather than data.sf.gov's Socrata endpoint for this one,
// since its exact JSON shape (attributes + geometry.paths, confirmed live) needs no cert
// workaround the way data.sf.gov does (see DataSfTrustConfig).
private const val RPP_FEATURE_SERVICE_URL =
    "https://services.sfmta.com/arcgis/rest/services/DataSF/master/FeatureServer/24/query"

// RPPAREA1 is " " (a literal space), not blank/null, on rows with no RPP regulation — matches
// the live feed's own convention, confirmed by querying it directly.
private const val RPP_WHERE_CLAUSE = "RPPAREA1 IS NOT NULL AND RPPAREA1 <> ' '"
private const val RPP_OUT_FIELDS = "OBJECTID,DAYS,HRS_BEGIN,HRS_END,HRLIMIT,RPPAREA1,RPPAREA2,RPPAREA3"

suspend fun fetchRppPage(limit: Int, offset: Int): String = withContext(Dispatchers.IO) {
    val query = "where=${URLEncoder.encode(RPP_WHERE_CLAUSE, "UTF-8")}" +
            "&outFields=$RPP_OUT_FIELDS" +
            "&orderByFields=OBJECTID" +
            "&returnGeometry=true" +
            "&resultOffset=$offset" +
            "&resultRecordCount=$limit" +
            // The layer's native spatial reference is already WGS84 (wkid 4326, checked against the
            // layer's own metadata), but the parser reads geometry as [lng, lat] degrees, so ask for it
            // explicitly rather than depend on that never changing.
            "&outSR=4326" +
            "&f=json"
    val url = URL("$RPP_FEATURE_SERVICE_URL?$query")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    try {
        val code = connection.responseCode
        if (code != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("HTTP $code at offset $offset: $errorBody")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        // ArcGIS returns HTTP 200 even for a malformed request — the actual error shows up as
        // an "error" object in the JSON body instead, so that has to be checked explicitly.
        val error = JSONObject(body).optJSONObject("error")
        if (error != null) {
            throw Exception("ArcGIS error at offset $offset: ${error.optString("message")}")
        }
        body
    } finally {
        connection.disconnect()
    }
}

suspend fun fetchRppPageWithRetry(limit: Int, offset: Int, maxAttempts: Int = 3): String {
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return fetchRppPage(limit, offset)
        } catch (e: Exception) {
            lastError = e
            Log.w("RppSync", "Attempt ${attempt + 1} failed at offset $offset: ${e.message}")
            delay(1500L * (attempt + 1))
        }
    }
    throw lastError ?: Exception("Unknown fetch failure at offset $offset")
}

suspend fun fetchAllRppRegulations(onPage: suspend (List<RppZoneRegulation>) -> Unit): Int {
    val pageSize = 2000
    var offset = 0
    var total = 0
    while (true) {
        val json = fetchRppPageWithRetry(pageSize, offset)
        val page = parseRppRegulations(json)
        onPage(page)
        total += page.size
        Log.d("RppSync", "Fetched RPP page at offset $offset: ${page.size} rows")
        if (page.size < pageSize) break
        offset += pageSize
    }
    return total
}

/**
 * A string attribute, or null when it's absent, a JSON null, blank, or the literal text "null".
 *
 * Android's org.json `optString` turns a JSON null into the four-letter STRING "null" rather than
 * an empty string, which would slip through a plain isNotEmpty() check and become a bogus zone
 * (a "NULL" entry in the permit picker). `isNull` is true for both a missing key and a JSON null.
 * The feed also uses a single space " " for "no value" in these fields (its own WHERE clause
 * filters on it), which `trim()` turns into blank. The text check is a belt-and-braces guard in case
 * a null ever arrives already stringified.
 */
internal fun JSONObject.optCleanString(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

/**
 * Parses an ArcGIS FeatureServer query response (attributes + geometry.paths — this exact
 * shape was confirmed against RPP_FEATURE_SERVICE_URL directly, not assumed from generic
 * ArcGIS docs). A feature's geometry can have multiple "paths" (disconnected line parts); every
 * RPP blockface row observed live has exactly one, so only the first is kept here — matching
 * how StreetSegment models one physical curb-side as a single polyline.
 */
fun parseRppRegulations(json: String): List<RppZoneRegulation> {
    val root = JSONObject(json)
    val features = root.optJSONArray("features") ?: return emptyList()
    val result = mutableListOf<RppZoneRegulation>()
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val attrs = feature.optJSONObject("attributes") ?: continue

        val zoneLetters = listOf("RPPAREA1", "RPPAREA2", "RPPAREA3")
            .mapNotNull { key -> attrs.optCleanString(key) }
            .distinct()
            .joinToString(",")
        if (zoneLetters.isEmpty()) continue // defensive — the WHERE clause should already exclude these

        val firstPath = feature.optJSONObject("geometry")?.optJSONArray("paths")?.optJSONArray(0)
        val points = mutableListOf<LatLng>()
        if (firstPath != null) {
            for (p in 0 until firstPath.length()) {
                val pair = firstPath.getJSONArray(p)
                points.add(LatLng(lat = pair.getDouble(1), lng = pair.getDouble(0)))
            }
        }
        val centroidLat = if (points.isNotEmpty()) points.map { it.lat }.average() else 0.0
        val centroidLng = if (points.isNotEmpty()) points.map { it.lng }.average() else 0.0

        result.add(
            RppZoneRegulation(
                objectId = attrs.optInt("OBJECTID").toString(),
                zoneLetters = zoneLetters,
                days = attrs.optCleanString("DAYS") ?: "",
                hrsBegin = attrs.optInt("HRS_BEGIN"),
                hrsEnd = attrs.optInt("HRS_END"),
                hrLimit = attrs.optDouble("HRLIMIT", 0.0).toFloat(),
                points = points,
                centroidLat = centroidLat,
                centroidLng = centroidLng
            )
        )
    }
    return result
}
