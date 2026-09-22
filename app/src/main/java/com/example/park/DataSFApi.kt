package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

suspend fun fetchSweepingPage(context: Context, limit: Int, offset: Int): String =
    fetchSweepingQuery(context, "\$limit=$limit&\$offset=$offset", "offset $offset")

/** The server's own row count (`[{"count":"37878"}]`), used to check a fetch was complete. */
suspend fun fetchSweepingTotal(context: Context): Int =
    parseSocrataCount(fetchSweepingQuery(context, "\$select=count(*)", "count query"))
        ?: throw Exception("No count in Socrata count response")

internal fun parseSocrataCount(json: String): Int? =
    JSONArray(json).optJSONObject(0)?.optString("count")?.toIntOrNull()

private suspend fun fetchSweepingQuery(context: Context, query: String, what: String): String {
    return withContext(Dispatchers.IO) {
        // data.sf.gov, not data.sfgov.org \u2014 the site has moved to the newer domain, and
        // connecting directly to it (rather than relying on a possible redirect from the old
        // one) guarantees the certificate this connection actually negotiates during its TLS
        // handshake is the SAME one DataSfTrustConfig's bundled intermediate was matched
        // against. SNI/certificate selection happens against whichever hostname is dialed
        // first, before any HTTP-level redirect is even seen.
        val url = URL("https://data.sf.gov/resource/yhqp-riqs.json?$query")
        val connection = url.openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            // See DataSfTrustConfig's class doc \u2014 works around data.sf.gov not sending
            // its full certificate chain. Scoped to just this connection; null means the
            // bundled cert isn't set up yet, in which case this simply behaves exactly as it
            // did before (still subject to the known "Trust anchor" failure).
            DataSfTrustConfig.sslSocketFactory(context)?.let { connection.sslSocketFactory = it }
        }
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        val token = ApiKeys.dataSfAppToken(context)
        if (token.isNotBlank()) {
            connection.setRequestProperty("X-App-Token", token)
        }
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
}


suspend fun fetchWithRetry(context: Context, limit: Int, offset: Int, maxAttempts: Int = 3): String =
    retrySweeping(context, "offset $offset", maxAttempts) { fetchSweepingPage(context, limit, offset) }

// Not private: StreetSegmentRepository reuses this to read the feed's total count upfront
// (for "X out of N" progress display) with the same retry behavior fetchAllSegments itself uses.
suspend fun <T> retrySweeping(context: Context, what: String, maxAttempts: Int = 3, request: suspend () -> T): T {
    // A configured DataSF app token was assumed to move the client out of Socrata's shared
    // anonymous throttling pool entirely — but real testing at 300ms/1s-2s-3s (this function's
    // first tuning for the token path) still hit HTTP 425 with an empty body at basically the
    // same cadence as the anonymous path. An empty-body 425 looks like an infrastructure/CDN
    // burst limiter sitting in front of Socrata, not Socrata's own per-token quota — and that
    // kind of limiter typically watches request frequency per IP regardless of auth, which a
    // token wouldn't bypass at all. So this keeps a modest speed edge for a configured token
    // (some benefit is still plausible) without assuming it's a free pass.
    val hasToken = ApiKeys.dataSfAppToken(context).isNotBlank()
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return request()
        } catch (e: Exception) {
            lastError = e
            Log.w("DataSF", "Attempt ${attempt + 1} failed at $what: ${e.message}")
            val backoffBaseMillis = if (hasToken) 3500L else 4500L
            delay(backoffBaseMillis * (attempt + 1))
        }
    }
    throw lastError ?: Exception("Unknown fetch failure at $what")
}

suspend fun fetchAllSegments(context: Context, onPage: suspend (List<StreetSegment>) -> Unit): FeedFetch {
    val hasToken = ApiKeys.dataSfAppToken(context).isNotBlank()
    val pageSpacingMillis = if (hasToken) 1800L else 2800L
    return collectSegmentPages(
        pageSize = 1000,
        // A pause between successful requests, not just between retries of the same
        // one — testing showed data.sfgov.org (or whatever sits in front of it) starts
        // returning HTTP 425 after only a handful of back-to-back requests, and a
        // configured app token didn't meaningfully change that in practice (see
        // fetchWithRetry's comment) — so this stays close to the anonymous pacing rather
        // than assuming the token buys much headroom.
        pause = { delay(pageSpacingMillis) },
        fetchPage = { limit, offset -> fetchWithRetry(context, limit, offset) },
        fetchServerTotal = { retrySweeping(context, "count query") { fetchSweepingTotal(context) } },
        onPage = onPage,
        log = { Log.d("DataSF", it) }
    )
}

/**
 * The paging loop, with the network, pacing and logging injected so a unit test can drive it with a
 * fake server. Socrata has no "more rows" flag, so a page shorter than [pageSize] ends the walk; the
 * caller then checks the total against the server's own count before trusting it (see pruneSkipReason).
 * [parseSegments] keeps every row, so a page's parsed size is also its raw size.
 */
internal suspend fun collectSegmentPages(
    pageSize: Int,
    pause: suspend () -> Unit,
    fetchPage: suspend (limit: Int, offset: Int) -> String,
    fetchServerTotal: suspend () -> Int,
    onPage: suspend (List<StreetSegment>) -> Unit,
    log: (String) -> Unit = {}
): FeedFetch {
    var offset = 0
    var complete = false
    var pages = 0
    while (pages < MAX_FEED_PAGES) {
        if (offset > 0) pause()
        val page = parseSegments(fetchPage(pageSize, offset))
        pages++
        onPage(page)
        offset += page.size
        log("Fetched page at offset ${offset - page.size}: ${page.size} rows")
        if (page.size < pageSize) {
            complete = true
            break
        }
    }
    val serverTotal = if (complete) {
        pause()
        readServerTotalOrNull(log) { fetchServerTotal() }
    } else null
    return FeedFetch(keptRows = offset, rawRows = offset, complete = complete, serverTotal = serverTotal)
}

fun parseSegments(json: String): List<StreetSegment> {
    val array = JSONArray(json)
    val result = mutableListOf<StreetSegment>()
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val points = parseLineString(obj.optJSONObject("line")?.toString() ?: "")
        val centroidLat = if (points.isNotEmpty()) points.map { it.lat }.average() else 0.0
        val centroidLng = if (points.isNotEmpty()) points.map { it.lng }.average() else 0.0
        result.add(
            StreetSegment(
                blockSweepId = obj.optString("blocksweepid"),
                cnn = obj.optString("cnn"),
                corridor = obj.optString("corridor"),
                limits = obj.optString("limits"),
                cnnRightLeft = obj.optString("cnnrightleft"),
                blockSide = obj.optString("blockside"),
                fullName = obj.optString("fullname"),
                fromHour = obj.optString("fromhour").toIntOrNull() ?: 0,
                toHour = obj.optString("tohour").toIntOrNull() ?: 0,
                week1 = obj.optString("week1") == "1",
                week2 = obj.optString("week2") == "1",
                week3 = obj.optString("week3") == "1",
                week4 = obj.optString("week4") == "1",
                week5 = obj.optString("week5") == "1",
                holidays = obj.optString("holidays") == "1",
                points = points,
                centroidLat = centroidLat,
                centroidLng = centroidLng
            )
        )
    }
    return result
}

/**
 * Minimal RFC4180-style CSV line splitter \u2014 handles quoted fields and "" as an escaped
 * literal quote inside one. Needed specifically because the geometry column's WKT text (e.g.
 * "LINESTRING (-122.4 37.7, -122.41 37.71)") contains commas a naive split(",") would
 * misread as column boundaries. Not a general CSV library, and deliberately doesn't handle a
 * quoted field spanning multiple physical lines \u2014 this dataset's WKT values never contain
 * a literal newline, so line-by-line splitting upstream is safe for this specific export.
 */
private fun splitCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"')
                i++ // consume the second quote of the escaped pair
            }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> {
                fields.add(current.toString())
                current.clear()
            }
            else -> current.append(c)
        }
        i++
    }
    fields.add(current.toString())
    return fields
}

/**
 * Parses DataSF's CSV export of this same dataset (yhqp-riqs) \u2014 added because the
 * dataset's own site more prominently offers CSV than the raw JSON API endpoint, so someone
 * downloading it by hand from data.sfgov.org/data.sf.gov is more likely to end up with this
 * than with JSON. Confirmed header from the live export: CNN,Corridor,Limits,CNNRightLeft,
 * BlockSide,FullName,WeekDay,FromHour,ToHour,Week1,Week2,Week3,Week4,Week5,Holidays,
 * BlockSweepID,Line \u2014 columns are looked up by name (case-insensitive) rather than
 * assumed position, so a reordered export still parses correctly; WeekDay is present in the
 * CSV but unused here, same as the JSON path (day-of-week comes from parsing FullName
 * instead, since WeekDay itself has known gaps \u2014 see the project's own notes on that).
 */
fun parseSegmentsFromCsv(csv: String): List<StreetSegment> {
    val lines = csv.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return emptyList()
    val header = splitCsvLine(lines[0]).map { it.trim() }
    fun col(name: String) = header.indexOfFirst { it.equals(name, ignoreCase = true) }
    val cnnIdx = col("CNN")
    val corridorIdx = col("Corridor")
    val limitsIdx = col("Limits")
    val cnnRightLeftIdx = col("CNNRightLeft")
    val blockSideIdx = col("BlockSide")
    val fullNameIdx = col("FullName")
    val fromHourIdx = col("FromHour")
    val toHourIdx = col("ToHour")
    val week1Idx = col("Week1")
    val week2Idx = col("Week2")
    val week3Idx = col("Week3")
    val week4Idx = col("Week4")
    val week5Idx = col("Week5")
    val holidaysIdx = col("Holidays")
    val blockSweepIdIdx = col("BlockSweepID")
    val lineIdx = col("Line")

    fun field(row: List<String>, idx: Int): String = if (idx in row.indices) row[idx] else ""

    val result = mutableListOf<StreetSegment>()
    for (i in 1 until lines.size) {
        val row = splitCsvLine(lines[i])
        if (row.size < 2) continue // skip stray blank/short lines
        val points = parseWktLineString(field(row, lineIdx))
        val centroidLat = if (points.isNotEmpty()) points.map { it.lat }.average() else 0.0
        val centroidLng = if (points.isNotEmpty()) points.map { it.lng }.average() else 0.0
        result.add(
            StreetSegment(
                blockSweepId = field(row, blockSweepIdIdx),
                cnn = field(row, cnnIdx),
                corridor = field(row, corridorIdx),
                limits = field(row, limitsIdx),
                cnnRightLeft = field(row, cnnRightLeftIdx),
                blockSide = field(row, blockSideIdx),
                fullName = field(row, fullNameIdx),
                fromHour = field(row, fromHourIdx).toIntOrNull() ?: 0,
                toHour = field(row, toHourIdx).toIntOrNull() ?: 0,
                week1 = field(row, week1Idx) == "1",
                week2 = field(row, week2Idx) == "1",
                week3 = field(row, week3Idx) == "1",
                week4 = field(row, week4Idx) == "1",
                week5 = field(row, week5Idx) == "1",
                holidays = field(row, holidaysIdx) == "1",
                points = points,
                centroidLat = centroidLat,
                centroidLng = centroidLng
            )
        )
    }
    return result
}