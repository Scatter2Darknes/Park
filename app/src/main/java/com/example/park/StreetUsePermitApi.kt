package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * Public Works "Street-Use Permits" (DataSF b6tj-gt35), temporary-occupancy permits only. What the data
 * actually contains was checked on 2026-09-25 (docs/park-sources-refactor-spec.md, "Part A findings"):
 *  - one row per permit per block, with the block's CNN (the newer Clariti system, fxfq-npa9, has only a
 *    house address, which the app can't match to a block yet, so it isn't used);
 *  - about 430 rows are live at any time, so every sync downloads them all;
 *  - "Street space" permits were left out on purpose: they are months-long construction staging, not
 *    short-notice no-parking signs.
 *
 * Privacy: the query is CITYWIDE, filtered by type, date and status only, never by place. Matching to the
 * parked block happens on the phone.
 */

private const val PERMIT_DATASET_ID = "b6tj-gt35"
private const val PERMIT_PAGE_SIZE = 5000
private const val PERMIT_SELECT =
    "permit_number,cnn,streetname,cross_street_1,cross_street_2,permit_purpose,status,permit_start_date,permit_end_date"

/** Statuses that clearly mean "no permit": everything else (APPROVED, ONLINE, RENEWED, still in review, ...) is
 *  kept, because a missed warning costs more than an extra "check the signs". */
internal val DEAD_PERMIT_STATUSES = listOf("VOID", "WITHDRAW", "CANCELLED", "CLOSED", "EXPIRED")

private val SOCRATA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'00:00:00")

/**
 * The feed's WHERE clause for one sync: live temporary-occupancy permits whose last day is [fromDate] or later.
 * `IS NULL OR NOT IN` because a plain NOT IN would also drop rows whose status is blank.
 */
internal fun permitWhereClause(fromDate: LocalDate): String =
    "permit_type = 'TempOccup' AND permit_end_date >= '${fromDate.format(SOCRATA_DATE)}' AND " +
        "(status IS NULL OR status NOT IN (${DEAD_PERMIT_STATUSES.joinToString(",") { "'$it'" }}))"

private fun encodePermitParams(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

private suspend fun fetchPermitPage(context: Context, where: String, limit: Int, offset: Int): String =
    retrySweeping(context, "street-use permits offset $offset") {
        dataSfGet(
            context, PERMIT_DATASET_ID,
            encodePermitParams(
                "\$select" to PERMIT_SELECT,
                "\$where" to where,
                "\$order" to "permit_number,cnn",
                "\$limit" to limit.toString(),
                "\$offset" to offset.toString()
            ),
            "street-use permits offset $offset"
        )
    }

private suspend fun fetchPermitTotal(context: Context, where: String): Int? =
    retrySweeping(context, "street-use permits count query") {
        parseSocrataCount(
            dataSfGet(context, PERMIT_DATASET_ID, encodePermitParams("\$select" to "count(*)", "\$where" to where), "street-use permits count query")
        )
    }

/** Every live temporary-occupancy permit whose last day is [fromDate] or later, stamped with [syncId]. */
internal suspend fun fetchAllStreetUsePermits(
    context: Context,
    fromDate: LocalDate,
    syncId: Long,
    onPage: suspend (List<StreetUsePermit>) -> Unit
): FeedFetch {
    val where = permitWhereClause(fromDate)
    val hasToken = ApiKeys.dataSfAppToken(context).isNotBlank()
    return collectSocrataPages(
        pageSize = PERMIT_PAGE_SIZE,
        pause = { delay(if (hasToken) 1800L else 2800L) },
        fetchPage = { limit, offset -> fetchPermitPage(context, where, limit, offset) },
        parse = { parseStreetUsePermits(it, syncId) },
        fetchServerTotal = { fetchPermitTotal(context, where) },
        onPage = onPage,
        log = { Log.d("PermitSync", it) }
    )
}

/**
 * Parses one page. A row is dropped (only counted as raw) if it lacks a permit number, a CNN, or a readable
 * start and end: without those it can't be matched or placed in time.
 */
internal fun parseStreetUsePermits(json: String, syncId: Long?): List<StreetUsePermit> {
    val array = JSONArray(json)
    val result = mutableListOf<StreetUsePermit>()
    for (i in 0 until array.length()) {
        val row = array.optJSONObject(i) ?: continue
        val permit = try {
            parsePermitRow(row, syncId)
        } catch (e: Exception) {
            null // one malformed row must not lose the whole page
        } ?: continue
        result.add(permit)
    }
    return result
}

private fun parsePermitRow(row: JSONObject, syncId: Long?): StreetUsePermit? {
    val number = row.optCleanString("permit_number") ?: return null
    val cnn = row.optCleanString("cnn")?.removeSuffix(".0") ?: return null
    val start = row.optCleanString("permit_start_date")?.let { parseFeedTime(it) } ?: return null
    val endRaw = row.optCleanString("permit_end_date")?.let { parseFeedTime(it) } ?: return null
    // A date with no time ("2026-10-03T00:00:00") means that whole day, so the permit runs to the next midnight.
    val end = if (endRaw.toLocalTime() == LocalTime.MIDNIGHT) endRaw.plusDays(1) else endRaw
    if (!end.isAfter(start)) return null
    return StreetUsePermit(
        rowKey = "${number}_$cnn",
        permitNumber = number,
        cnn = cnn,
        streetName = row.optCleanString("streetname"),
        crossStreet1 = row.optCleanString("cross_street_1"),
        crossStreet2 = row.optCleanString("cross_street_2"),
        purpose = row.optCleanString("permit_purpose"),
        status = row.optCleanString("status"),
        startMillis = start.atZone(SF_ZONE).toInstant().toEpochMilli(),
        endMillis = end.atZone(SF_ZONE).toInstant().toEpochMilli(),
        lastSeenSyncId = syncId
    )
}

/** A floating (zone-less) feed timestamp ("2026-09-24T07:00:00.000"), read as SF local time. Null if unreadable. */
private fun parseFeedTime(text: String): LocalDateTime? =
    try {
        LocalDateTime.parse(text.take(19))
    } catch (e: Exception) {
        null
    }
