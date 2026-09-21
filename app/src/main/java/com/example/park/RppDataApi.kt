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
private const val RPP_OUT_FIELDS = "OBJECTID,REGULATION,DAYS,HRS_BEGIN,HRS_END,HRLIMIT,RPPAREA1,RPPAREA2,RPPAREA3"

/**
 * Assumed limit, in hours, for a "Time Limited" RPP row whose HRLIMIT is missing. The live feed has 5 such
 * rows out of ~6,500 (zones S, D, D, Q, U — checked 2026-09-20); 86% of all rows are 2 hours, so 2 is the
 * most likely value, and for a ticket-avoidance app warning on a guess beats staying silent. Rows built this
 * way are flagged `limitAssumed` so reminders can say so.
 */
const val RPP_ASSUMED_LIMIT_HOURS = 2f

private fun rppQuery(vararg parts: String): String =
    "where=${URLEncoder.encode(RPP_WHERE_CLAUSE, "UTF-8")}&" + parts.joinToString("&") + "&f=json"

/** One GET against the feature service; [what] names the request in error messages. */
private suspend fun rppGet(query: String, what: String): String = withContext(Dispatchers.IO) {
    val url = URL("$RPP_FEATURE_SERVICE_URL?$query")
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
        // ArcGIS returns HTTP 200 even for a malformed request — the actual error shows up as
        // an "error" object in the JSON body instead, so that has to be checked explicitly.
        val error = JSONObject(body).optJSONObject("error")
        if (error != null) {
            throw Exception("ArcGIS error at $what: ${error.optString("message")}")
        }
        body
    } finally {
        connection.disconnect()
    }
}

suspend fun fetchRppPage(limit: Int, offset: Int): String = rppGet(
    rppQuery(
        "outFields=$RPP_OUT_FIELDS",
        "orderByFields=OBJECTID",
        "returnGeometry=true",
        "resultOffset=$offset",
        "resultRecordCount=$limit",
        // The layer's native spatial reference is already WGS84 (wkid 4326, checked against the
        // layer's own metadata), but the parser reads geometry as [lng, lat] degrees, so ask for it
        // explicitly rather than depend on that never changing.
        "outSR=4326"
    ),
    "offset $offset"
)

/** The server's own row count for the same filter (`{"count":6502}`), used to check a fetch was complete. */
suspend fun fetchRppTotal(): Int =
    parseRppCount(rppGet(rppQuery("returnCountOnly=true"), "count query"))
        ?: throw Exception("No count in ArcGIS count response")

internal fun parseRppCount(json: String): Int? =
    JSONObject(json).takeIf { it.has("count") && !it.isNull("count") }?.optInt("count", -1)?.takeIf { it >= 0 }

private suspend fun <T> retryRpp(what: String, maxAttempts: Int = 3, request: suspend () -> T): T {
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return request()
        } catch (e: Exception) {
            lastError = e
            Log.w("RppSync", "Attempt ${attempt + 1} failed at $what: ${e.message}")
            delay(1500L * (attempt + 1))
        }
    }
    throw lastError ?: Exception("Unknown fetch failure at $what")
}

suspend fun fetchRppPageWithRetry(limit: Int, offset: Int, maxAttempts: Int = 3): String =
    retryRpp("offset $offset", maxAttempts) { fetchRppPage(limit, offset) }

suspend fun fetchAllRppRegulations(onPage: suspend (List<RppZoneRegulation>) -> Unit): FeedFetch =
    collectRppPages(
        pageSize = 2000,
        fetchPage = ::fetchRppPageWithRetry,
        fetchServerTotal = { retryRpp("count query") { fetchRppTotal() } },
        onPage = onPage,
        log = { Log.d("RppSync", it) }
    )

/**
 * The paging loop, with the network and logging injected so a unit test can drive it with a fake server.
 *
 * Paging is driven by what the SERVER says, never by how many rows survived parsing: the parser drops
 * junk rows, so a parsed page can be short while the server still has more (the bug that once stopped
 * the sync after two of four pages). It continues while the response carries `exceededTransferLimit`
 * (or, when the flag is absent, while the raw page was full), and advances the offset by the raw rows
 * actually returned — the server may cap a page below the requested size.
 */
internal suspend fun collectRppPages(
    pageSize: Int,
    fetchPage: suspend (limit: Int, offset: Int) -> String,
    fetchServerTotal: suspend () -> Int,
    onPage: suspend (List<RppZoneRegulation>) -> Unit,
    log: (String) -> Unit = {}
): FeedFetch {
    var offset = 0
    var kept = 0
    var complete = false
    var pages = 0
    while (pages < MAX_FEED_PAGES) {
        val page = parseRppPage(fetchPage(pageSize, offset))
        pages++
        onPage(page.rows)
        kept += page.rows.size
        log("Fetched RPP page at offset $offset: ${page.rawCount} raw, ${page.rows.size} kept")
        offset += page.rawCount
        val more = page.exceededTransferLimit ?: (page.rawCount >= pageSize)
        if (!more) {
            complete = true
            break
        }
        // The server says there is more but returned nothing, so the offset can't advance: give up
        // (incomplete) rather than ask for the same page forever.
        if (page.rawCount == 0) break
    }
    val serverTotal = if (complete) readServerTotalOrNull(log) { fetchServerTotal() } else null
    return FeedFetch(keptRows = kept, rawRows = offset, complete = complete, serverTotal = serverTotal)
}

/** One parsed ArcGIS response: the kept rows plus what the server said about the page itself. */
internal class RppPage(val rows: List<RppZoneRegulation>, val rawCount: Int, val exceededTransferLimit: Boolean?)

internal fun parseRppPage(json: String): RppPage {
    val root = JSONObject(json)
    val flag = if (root.has("exceededTransferLimit") && !root.isNull("exceededTransferLimit")) {
        root.optBoolean("exceededTransferLimit")
    } else null
    return RppPage(
        rows = parseRppRegulations(root),
        rawCount = root.optJSONArray("features")?.length() ?: 0,
        exceededTransferLimit = flag
    )
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
fun parseRppRegulations(json: String): List<RppZoneRegulation> = parseRppRegulations(JSONObject(json))

internal fun parseRppRegulations(root: JSONObject): List<RppZoneRegulation> {
    val features = root.optJSONArray("features") ?: return emptyList()
    val result = mutableListOf<RppZoneRegulation>()
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val attrs = feature.optJSONObject("attributes") ?: continue

        val zoneLetters = listOf("RPPAREA1", "RPPAREA2", "RPPAREA3")
            .mapNotNull { key -> attrs.optCleanString(key) }
            // Real zones are letters only (A-Z, AA-HH, HV). The feed's "no parking any time" rows carry a
            // "0" in RPPAREA1, which slips past the query's `<> ' '` filter and would show up as a zone "0"
            // in the permit picker; drop anything that isn't letters.
            .filter { zone -> zone.all { it.isLetter() } }
            .distinct()
            .joinToString(",")
        if (zoneLetters.isEmpty()) continue // junk rows (zone "0") and anything the WHERE clause missed

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

        // A missing / non-positive limit: assume RPP_ASSUMED_LIMIT_HOURS for "Time Limited" rows (the
        // regulation type says a limit exists, the value just isn't filled in); leave every other type
        // (metered "Paid + Permit", 72-hour "Pay or Permit", ...) at 0 = no deadline.
        val postedLimit = if (attrs.isNull("HRLIMIT")) 0f else attrs.optDouble("HRLIMIT", 0.0).toFloat()
        val isTimeLimited = attrs.optCleanString("REGULATION")?.equals("Time Limited", ignoreCase = true) == true
        val assumeLimit = postedLimit <= 0f && isTimeLimited

        result.add(
            RppZoneRegulation(
                objectId = attrs.optInt("OBJECTID").toString(),
                zoneLetters = zoneLetters,
                days = attrs.optCleanString("DAYS") ?: "",
                hrsBegin = attrs.optInt("HRS_BEGIN"),
                hrsEnd = attrs.optInt("HRS_END"),
                hrLimit = if (assumeLimit) RPP_ASSUMED_LIMIT_HOURS else postedLimit,
                limitAssumed = if (assumeLimit) true else null,
                points = points,
                centroidLat = centroidLat,
                centroidLng = centroidLng
            )
        )
    }
    return result
}
