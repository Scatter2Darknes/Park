package com.example.park

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Meter LOCATIONS: SFMTA's own ArcGIS mirror, same service (and same reasoning — no cert
// workaround needed, unlike data.sf.gov below) RppDataApi.kt already uses for RPP, just a
// different layer ("MTA.meters", confirmed live against the service's own layer list).
private const val METER_LOCATION_SERVICE_URL =
    "https://services.sfmta.com/arcgis/rest/services/DataSF/master/FeatureServer/19/query"
private const val METER_LOCATION_WHERE_CLAUSE = "ACTIVE_METER_FLAG = 'M'"
private const val METER_LOCATION_OUT_FIELDS = "POST_ID,LATITUDE,LONGITUDE,STREET_NAME"

// Meter operating SCHEDULES: unlike locations, this half genuinely has no ArcGIS mirror —
// confirmed by listing every layer on the service above and checking each meter/park-related
// one directly; only data.sf.gov's own Socrata API has it ("Meter Operating Schedules",
// 6cqg-dxku). docs/ReliabilityPlan.md's T0 section flags data.sf.gov's cert chain as having
// failed before (from curl, possibly reflecting a gap a browser tolerates but Android's
// stricter default TLS validation doesn't) — see fetchAllMeteredZones for how a failure here
// degrades to location-only zones instead of losing the whole feature.
private const val METER_SCHEDULE_BASE_URL = "https://data.sf.gov/resource/6cqg-dxku.json"
private const val METER_SCHEDULE_WHERE_CLAUSE = "starts_with(active_meter_status, 'M')"
private const val METER_SCHEDULE_SELECT = "post_id,priority,days_applied,from_time,to_time,time_limit"

private fun meterLocationQuery(vararg parts: String): String =
    "where=${URLEncoder.encode(METER_LOCATION_WHERE_CLAUSE, "UTF-8")}&" + parts.joinToString("&") + "&f=json"

private suspend fun meterLocationGet(query: String, what: String): String = withContext(Dispatchers.IO) {
    val url = URL("$METER_LOCATION_SERVICE_URL?$query")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    try {
        val code = connection.responseCode
        if (code != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("HTTP $code at $what: $errorBody")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val error = JSONObject(body).optJSONObject("error")
        if (error != null) throw Exception("ArcGIS error at $what: ${error.optString("message")}")
        body
    } finally {
        connection.disconnect()
    }
}

private suspend fun <T> retryMeter(what: String, maxAttempts: Int = 3, request: suspend () -> T): T {
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return request()
        } catch (e: Exception) {
            lastError = e
            Log.w("MeterSync", "Attempt ${attempt + 1} failed at $what: ${e.message}")
            delay(1500L * (attempt + 1))
        }
    }
    throw lastError ?: Exception("Unknown fetch failure at $what")
}

/** One meter location — before the schedule join, so it has no hours yet (see joinMeterFeeds). */
internal data class MeterLocation(val postId: String, val lat: Double, val lng: Double, val streetName: String?)

internal fun parseMeterLocationPage(json: String): List<MeterLocation> {
    val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
    val result = mutableListOf<MeterLocation>()
    for (i in 0 until features.length()) {
        val attrs = features.getJSONObject(i).optJSONObject("attributes") ?: continue
        val postId = attrs.optCleanString("POST_ID") ?: continue
        if (attrs.isNull("LATITUDE") || attrs.isNull("LONGITUDE")) continue
        result.add(
            MeterLocation(
                postId = postId,
                lat = attrs.optDouble("LATITUDE"),
                lng = attrs.optDouble("LONGITUDE"),
                streetName = attrs.optCleanString("STREET_NAME")
            )
        )
    }
    return result
}

/** All active meter locations, paged the same way fetchAllRppRegulations pages the RPP feed. */
internal suspend fun fetchAllMeterLocations(onPage: suspend (List<MeterLocation>) -> Unit): FeedFetch {
    var offset = 0
    var kept = 0
    var complete = false
    var pages = 0
    while (pages < MAX_FEED_PAGES) {
        val pageSize = 2000
        val json = retryMeter("meter location offset $offset") {
            meterLocationGet(
                meterLocationQuery(
                    "outFields=$METER_LOCATION_OUT_FIELDS", "orderByFields=POST_ID",
                    "returnGeometry=false", "resultOffset=$offset", "resultRecordCount=$pageSize"
                ),
                "meter location offset $offset"
            )
        }
        val root = JSONObject(json)
        val rawCount = root.optJSONArray("features")?.length() ?: 0
        val page = parseMeterLocationPage(json)
        pages++
        onPage(page)
        kept += page.size
        Log.d("MeterSync", "Fetched meter-location page at offset $offset: $rawCount raw, ${page.size} kept")
        offset += rawCount
        val exceededTransferLimit = if (root.has("exceededTransferLimit") && !root.isNull("exceededTransferLimit")) {
            root.optBoolean("exceededTransferLimit")
        } else null
        val more = exceededTransferLimit ?: (rawCount >= pageSize)
        if (!more) {
            complete = true
            break
        }
        if (rawCount == 0) break
    }
    val serverTotal = if (complete) {
        readServerTotalOrNull({ Log.d("MeterSync", it) }) {
            retryMeter("meter location count query") {
                JSONObject(meterLocationGet(meterLocationQuery("returnCountOnly=true"), "meter location count query"))
                    .takeIf { it.has("count") && !it.isNull("count") }?.optInt("count", -1)?.takeIf { it >= 0 }
            }
        }
    } else null
    return FeedFetch(keptRows = kept, rawRows = offset, complete = complete, serverTotal = serverTotal)
}

// --- Schedules (Socrata) ---

/** One meter's schedule row — days_applied/from_time/to_time are the feed's own raw strings, parsed by
 *  the caller (MeterStatus.kt) rather than here, so a parse failure on one field doesn't drop the row. */
internal data class MeterSchedule(
    val postId: String,
    val priority: String,
    val daysApplied: String?,
    val fromTime: String?,
    val toTime: String?,
    val timeLimitText: String?
)

private fun meterScheduleUrl(limit: Int, offset: Int): String {
    val params = listOf(
        "\$select" to METER_SCHEDULE_SELECT,
        "\$where" to METER_SCHEDULE_WHERE_CLAUSE,
        "\$order" to "post_id,priority",
        "\$limit" to limit.toString(),
        "\$offset" to offset.toString()
    ).joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
    return "$METER_SCHEDULE_BASE_URL?$params"
}

private suspend fun meterScheduleGet(url: String, what: String): String = withContext(Dispatchers.IO) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    try {
        val code = connection.responseCode
        if (code != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("HTTP $code at $what: $errorBody")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

internal fun parseMeterSchedulePage(json: String): List<MeterSchedule> {
    val array = org.json.JSONArray(json)
    val result = mutableListOf<MeterSchedule>()
    for (i in 0 until array.length()) {
        val row = array.getJSONObject(i)
        val postId = row.optCleanString("post_id") ?: continue
        val priority = row.optCleanString("priority") ?: continue
        result.add(
            MeterSchedule(
                postId = postId,
                priority = priority,
                daysApplied = row.optCleanString("days_applied"),
                fromTime = row.optCleanString("from_time"),
                toTime = row.optCleanString("to_time"),
                timeLimitText = row.optCleanString("time_limit")
            )
        )
    }
    return result
}

/**
 * All active meter schedule rows, paged against Socrata's $limit/$offset (SODA2 pagination —
 * a different shape than the ArcGIS offset/exceededTransferLimit convention above, since this
 * is a different API entirely). Throws on an unrecoverable failure (including a TLS handshake
 * failure against data.sf.gov) — the caller (MeteredZoneRepository) is what decides to fall
 * back to location-only zones rather than fail the whole sync.
 */
internal suspend fun fetchAllMeterSchedules(onPage: suspend (List<MeterSchedule>) -> Unit): FeedFetch {
    val pageSize = 5000
    var offset = 0
    var kept = 0
    var pages = 0
    while (pages < MAX_FEED_PAGES) {
        val json = retryMeter("meter schedule offset $offset") {
            meterScheduleGet(meterScheduleUrl(pageSize, offset), "meter schedule offset $offset")
        }
        val page = parseMeterSchedulePage(json)
        val rawCount = org.json.JSONArray(json).length()
        pages++
        onPage(page)
        kept += page.size
        Log.d("MeterSync", "Fetched meter-schedule page at offset $offset: $rawCount raw, ${page.size} kept")
        offset += rawCount
        if (rawCount < pageSize) return FeedFetch(keptRows = kept, rawRows = offset, complete = true, serverTotal = offset)
    }
    return FeedFetch(keptRows = kept, rawRows = offset, complete = false, serverTotal = null)
}
