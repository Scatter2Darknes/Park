package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * SFMTA "Enforced Temporary Tow Zones" (DataSF 6r5h-j298), fetched from data.sf.gov. What the feed
 * actually contains was checked on 2026-09-23/24 (docs/park-closures-spec.md §9).
 *
 * Privacy: the query is always CITYWIDE. It filters by date only, never by place, so DataSF's logs
 * never see where the car is. Matching to the parked block happens on-device (TowAlerts.kt).
 *
 * Freshness: in September 2026 the feed had stopped receiving new permits (newest entry July 20),
 * although DataSF still republished it daily. So every sync also reads the NEWEST entry date
 * (fetchTowNewestEntryMillis), and the app never says "no tow zones" when that is old (TowAlerts.kt).
 */

private const val TOW_DATASET_ID = "6r5h-j298"
private const val TOW_PAGE_SIZE = 5000
private const val TOW_SELECT =
    ":id,casenumber,permitnumber,cnn,address,streetfrontagename,streetfrontagefrom,streetfrontageto," +
        "startdate,enddate,starttime,endtime,notes,_24hourenforcement,datetimeentered"

private val SOCRATA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'00:00:00")

/**
 * The feed's WHERE clause for one sync: every zone whose last day is [fromDate] or later. The feed
 * holds 170,000 rows of history back to 2005, so the date filter is what keeps a sync small. Status is
 * NOT filtered: live rows are "Approved", "Installed" or blank, and none of those means "cancelled".
 */
internal fun towWhereClause(fromDate: LocalDate): String = "enddate >= '${fromDate.format(SOCRATA_DATE)}'"

private fun encodeTowParams(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

private suspend fun fetchTowPage(context: Context, where: String, limit: Int, offset: Int): String =
    retrySweeping(context, "tow zones offset $offset") {
        dataSfGet(
            context, TOW_DATASET_ID,
            encodeTowParams(
                "\$select" to TOW_SELECT,
                "\$where" to where,
                "\$order" to ":id",
                "\$limit" to limit.toString(),
                "\$offset" to offset.toString()
            ),
            "tow zones offset $offset"
        )
    }

private suspend fun fetchTowTotal(context: Context, where: String): Int? =
    retrySweeping(context, "tow zones count query") {
        parseSocrataCount(
            dataSfGet(context, TOW_DATASET_ID, encodeTowParams("\$select" to "count(*)", "\$where" to where), "tow zones count query")
        )
    }

/** Every tow zone whose last day is [fromDate] or later, stamped with [syncId]. */
internal suspend fun fetchAllTowZones(
    context: Context,
    fromDate: LocalDate,
    syncId: Long,
    onPage: suspend (List<TowZone>) -> Unit
): FeedFetch {
    val where = towWhereClause(fromDate)
    val hasToken = ApiKeys.dataSfAppToken(context).isNotBlank()
    return collectSocrataPages(
        pageSize = TOW_PAGE_SIZE,
        pause = { delay(if (hasToken) 1800L else 2800L) },
        fetchPage = { limit, offset -> fetchTowPage(context, where, limit, offset) },
        parse = { parseTowZones(it, syncId) },
        fetchServerTotal = { fetchTowTotal(context, where) },
        onPage = onPage,
        log = { Log.d("TowSync", it) }
    )
}

/**
 * When the newest permit in the WHOLE feed was entered (epoch ms), or null if unreadable. Citywide and
 * unfiltered, like the sync itself. This is the staleness signal: a working feed gets new permits
 * every day, so a newest entry weeks old means SFMTA's upstream has stopped, not that the city has
 * no tow zones.
 */
internal suspend fun fetchTowNewestEntryMillis(context: Context): Long? =
    parseTowNewestEntry(
        retrySweeping(context, "tow zones newest entry") {
            dataSfGet(
                context, TOW_DATASET_ID,
                encodeTowParams("\$select" to "max(datetimeentered) as newest"),
                "tow zones newest entry"
            )
        }
    )

/** `[{"newest":"2026-07-20T17:01:33.000"}]` -> epoch ms (read as SF local time), or null. */
internal fun parseTowNewestEntry(json: String): Long? =
    JSONArray(json).optJSONObject(0)?.optCleanString("newest")?.let { feedLocalMillis(it) }

/**
 * Parses one page. A row is dropped (and only counted as raw) if it lacks a row id, any CNN, or a
 * readable start/end date: without those it can't be matched or placed in time. Anything else that
 * is missing is read the conservative way: unreadable days = every day, unreadable hours = all day.
 */
internal fun parseTowZones(json: String, syncId: Long?): List<TowZone> {
    val array = JSONArray(json)
    val result = mutableListOf<TowZone>()
    for (i in 0 until array.length()) {
        val row = array.optJSONObject(i) ?: continue
        val zone = try {
            parseTowZoneRow(row, syncId)
        } catch (e: Exception) {
            null // one malformed row must not lose the whole page
        } ?: continue
        result.add(zone)
    }
    return result
}

private fun parseTowZoneRow(row: JSONObject, syncId: Long?): TowZone? {
    val rowId = row.optCleanString(":id") ?: return null
    val cnns = towCnnList(row.optCleanString("cnn")?.split(',') ?: return null)
    if (cnns.length <= 2) return null // only commas: no real CNN
    val startDate = row.optCleanString("startdate")?.let { parseFeedDate(it) } ?: return null
    val endDate = row.optCleanString("enddate")?.let { parseFeedDate(it) } ?: return null
    if (endDate.isBefore(startDate)) return null
    val startMinute = parseTowClockTime(row.optCleanString("starttime"))
    val endMinute = parseTowClockTime(row.optCleanString("endtime"))
    val twentyFourHour = row.optCleanString("_24hourenforcement").equals("Yes", ignoreCase = true)
    val daysText = row.optCleanString("notes")
    return TowZone(
        rowId = rowId,
        caseNumber = row.optCleanString("casenumber"),
        permitNumber = row.optCleanString("permitnumber"),
        cnns = cnns,
        address = row.optCleanString("address"),
        streetName = row.optCleanString("streetfrontagename"),
        fromStreet = row.optCleanString("streetfrontagefrom"),
        toStreet = row.optCleanString("streetfrontageto"),
        startEpochDay = startDate.toEpochDay(),
        endEpochDay = endDate.toEpochDay(),
        startMinute = startMinute ?: 0,
        endMinute = endMinute ?: 0,
        // Either hour missing: treat the whole day as enforced rather than guess a window.
        allDay = twentyFourHour || startMinute == null || endMinute == null,
        daysMask = parseTowDays(daysText),
        daysText = daysText,
        enteredMillis = row.optCleanString("datetimeentered")?.let { feedLocalMillis(it) },
        lastSeenSyncId = syncId
    )
}

/** "2026-09-01T00:00:00.000" -> the date. Null if unreadable. */
private fun parseFeedDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text.take(10))
    } catch (e: Exception) {
        null
    }

/** A floating (zone-less) feed timestamp read as SF local time. Null if unreadable. */
private fun feedLocalMillis(text: String): Long? =
    try {
        LocalDateTime.parse(text.take(19)).atZone(SF_ZONE).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
