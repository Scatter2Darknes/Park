package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * SFMTA "Temporary Street Closures" (DataSF 8x25-yybr), fetched from data.sf.gov. What the feed
 * actually contains was checked on 2026-09-23 (docs/park-closures-spec.md §9).
 *
 * Privacy: the query is always CITYWIDE. It filters by status and by time only, never by place,
 * so DataSF's logs never see where the car is. Matching to the parked spot happens on-device
 * (StreetClosureMatcher.kt).
 */

private const val CLOSURES_DATASET_ID = "8x25-yybr"
private const val CLOSURES_PAGE_SIZE = 5000
private const val CLOSURES_SELECT =
    "objectid,case_num,case_name,type,cnn,street,from_st,to_st,veh_imp,start_utc,end_utc,start_dt,end_dt,shape"

// Socrata "floating timestamp" literal, e.g. 2026-09-23T21:00:00. The *_utc columns are meant to hold
// UTC (but see feedMillis: they are sometimes an hour off during daylight saving time).
private val SOCRATA_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/**
 * The feed's WHERE clause for one sync. Its column description claims it only holds PERMITTED
 * closures, but the live feed also carries applications still in review, awaiting payment, etc.,
 * so the status filter is required. [cutoffMillis] is fixed once per sync so the page fetches and
 * the count query all see the same set of rows.
 */
internal fun closuresWhereClause(cutoffMillis: Long): String {
    // end_utc is sometimes an hour LATE (see feedMillis), never early, so filtering on it can only keep
    // a just-ended row a little longer, never drop a live one. Ended rows are removed by time anyway.
    val cutoff = SOCRATA_TIMESTAMP.format(Instant.ofEpochMilli(cutoffMillis).atOffset(ZoneOffset.UTC))
    return "status = 'Permitted' AND end_utc > '$cutoff'"
}

private fun encodeParams(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

private suspend fun fetchClosuresPage(context: Context, where: String, limit: Int, offset: Int): String =
    retrySweeping(context, "closures offset $offset") {
        dataSfGet(
            context, CLOSURES_DATASET_ID,
            encodeParams(
                "\$select" to CLOSURES_SELECT,
                "\$where" to where,
                "\$order" to ":id", // Socrata's own row id: a stable order, so paging can't skip or repeat rows
                "\$limit" to limit.toString(),
                "\$offset" to offset.toString()
            ),
            "closures offset $offset"
        )
    }

/** The server's own count for the same WHERE clause, used to prove a fetch was complete (see pruneSkipReason). */
private suspend fun fetchClosuresTotal(context: Context, where: String): Int? =
    retrySweeping(context, "closures count query") {
        parseSocrataCount(
            dataSfGet(context, CLOSURES_DATASET_ID, encodeParams("\$select" to "count(*)", "\$where" to where), "closures count query")
        )
    }

/** Every permitted closure that hasn't ended by [cutoffMillis], stamped with [syncId]. */
internal suspend fun fetchAllStreetClosures(
    context: Context,
    cutoffMillis: Long,
    syncId: Long,
    onPage: suspend (List<StreetClosure>) -> Unit
): FeedFetch {
    val where = closuresWhereClause(cutoffMillis)
    val hasToken = ApiKeys.dataSfAppToken(context).isNotBlank()
    return collectSocrataPages(
        pageSize = CLOSURES_PAGE_SIZE,
        // Same pacing as the sweeping feed (see fetchAllSegments): data.sf.gov rate-limits bursts.
        pause = { delay(if (hasToken) 1800L else 2800L) },
        fetchPage = { limit, offset -> fetchClosuresPage(context, where, limit, offset) },
        parse = { parseStreetClosures(it, syncId) },
        fetchServerTotal = { fetchClosuresTotal(context, where) },
        onPage = onPage,
        log = { Log.d("ClosureSync", it) }
    )
}

/**
 * The Socrata paging loop, generic over the row type, with the network, pacing and logging injected
 * so a unit test can drive it with a fake server. Same rules as collectSegmentPages: a page shorter
 * than [pageSize] ends the walk, and the server's own count is read afterwards so the caller can
 * check the fetch was complete. Paging advances by the RAW row count (what the server returned),
 * never the parsed count: [parse] may drop junk rows, and advancing by the parsed size is exactly
 * the RPP paging bug.
 */
internal suspend fun <T> collectSocrataPages(
    pageSize: Int,
    pause: suspend () -> Unit,
    fetchPage: suspend (limit: Int, offset: Int) -> String,
    parse: (String) -> List<T>,
    fetchServerTotal: suspend () -> Int?,
    onPage: suspend (List<T>) -> Unit,
    log: (String) -> Unit = {}
): FeedFetch {
    var offset = 0
    var kept = 0
    var complete = false
    var pages = 0
    while (pages < MAX_FEED_PAGES) {
        if (offset > 0) pause()
        val json = fetchPage(pageSize, offset)
        val rawCount = JSONArray(json).length()
        val page = parse(json)
        pages++
        onPage(page)
        kept += page.size
        log("Fetched page at offset $offset: $rawCount raw, ${page.size} kept")
        offset += rawCount
        if (rawCount < pageSize) {
            complete = true
            break
        }
    }
    val serverTotal = if (complete) {
        pause()
        readServerTotalOrNull(log) { fetchServerTotal() }
    } else null
    return FeedFetch(keptRows = kept, rawRows = offset, complete = complete, serverTotal = serverTotal)
}

/**
 * Parses one page of the closures feed. A row is dropped (and only counted as raw) if it lacks an
 * id, a CNN, or a readable start/end time: without those it can't be matched or placed in time.
 * A row with no usable geometry is KEPT, since the CNN alone still matches a parked car's block.
 */
internal fun parseStreetClosures(json: String, syncId: Long?): List<StreetClosure> {
    val array = JSONArray(json)
    val result = mutableListOf<StreetClosure>()
    for (i in 0 until array.length()) {
        val row = array.optJSONObject(i) ?: continue
        val closure = try {
            parseStreetClosureRow(row, syncId)
        } catch (e: Exception) {
            null // one malformed row (e.g. broken geometry) must not lose the whole page
        } ?: continue
        result.add(closure)
    }
    return result
}

private fun parseStreetClosureRow(row: JSONObject, syncId: Long?): StreetClosure? {
    val objectId = row.optCleanString("objectid") ?: return null
    val cnn = row.optCleanString("cnn") ?: return null
    val start = feedMillis(local = row.optCleanString("start_dt"), utc = row.optCleanString("start_utc")) ?: return null
    val end = feedMillis(local = row.optCleanString("end_dt"), utc = row.optCleanString("end_utc")) ?: return null
    if (end <= start) return null
    val points = row.optJSONObject("shape")?.let { parseLineString(it.toString()) } ?: emptyList()
    return StreetClosure(
        objectId = objectId,
        caseNum = row.optCleanString("case_num"),
        caseName = row.optCleanString("case_name"),
        type = row.optCleanString("type"),
        cnn = cnn,
        street = row.optCleanString("street"),
        fromStreet = row.optCleanString("from_st"),
        toStreet = row.optCleanString("to_st"),
        vehicleImpact = row.optCleanString("veh_imp"),
        startMillis = start,
        endMillis = end,
        points = points,
        centroidLat = if (points.isNotEmpty()) points.map { it.lat }.average() else 0.0,
        centroidLng = if (points.isNotEmpty()) points.map { it.lng }.average() else 0.0,
        lastSeenSyncId = syncId
    )
}

/**
 * Epoch ms for one of the feed's times. Prefers the LOCAL column (start_dt / end_dt) read as San
 * Francisco time, with DST handled by SF_ZONE; falls back to the UTC column. Null if neither parses.
 *
 * Why not the UTC column: on the live feed (2026-09-23) about 10% of daylight-saving-time rows have a
 * *_utc value of local + 8 h instead of + 7 h, i.e. an hour late. Trusting it would show those
 * closures starting an hour later than they really do. The local columns were correct on every row.
 */
internal fun feedMillis(local: String?, utc: String?): Long? {
    local?.let { parseFloatingTimestamp(it) }?.let { return it.atZone(SF_ZONE).toInstant().toEpochMilli() }
    utc?.let { parseFloatingTimestamp(it) }?.let { return it.toInstant(ZoneOffset.UTC).toEpochMilli() }
    return null
}

/** "2026-09-15T08:00:00.000" -> LocalDateTime, ignoring fractional seconds. Null if unreadable. */
private fun parseFloatingTimestamp(text: String): LocalDateTime? =
    try {
        LocalDateTime.parse(text.take(19), SOCRATA_TIMESTAMP)
    } catch (e: Exception) {
        null
    }
