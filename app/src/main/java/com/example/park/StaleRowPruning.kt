package com.example.park

import android.content.Context

/**
 * Removing rows that have vanished from the upstream feeds.
 *
 * Both syncs upsert with REPLACE, which never deletes, so a street segment or RPP blockface that
 * SFMTA / DataSF retires would otherwise stay in the local database forever and keep colouring the
 * map (or triggering RPP reminders) for a regulation that no longer exists.
 *
 * How it works: each successful-looking network sync picks a sync id (its start time) and stamps
 * every row it upserts with it (`lastSeenSyncId`). Once the sync has FULLY succeeded, anything not
 * stamped with that id — a row the feed no longer returns, or one from before the column existed
 * (NULL) — is deleted. A manual JSON/CSV import stamps rows too but never deletes, since a file is
 * not authoritative about what else exists.
 *
 * Guards against deleting on bad data ([pruneSkipReason]): a sync may only delete when it provably saw
 * the WHOLE feed — it reached the last page, its raw row count equals the count the server itself
 * reports, and it didn't return far fewer rows than the table already held ([isSafeToPrune]). A fetch
 * that merely "looks finished" is not enough: an early stop is exactly how a paging bug once deleted
 * 2,504 good RPP rows.
 */

/** What one full pass over a feed saw. Row counts are RAW (before the parser drops anything). */
data class FeedFetch(
    /** Rows handed to the database after parsing (junk rows already dropped) — what the UI reports. */
    val keptRows: Int,
    /** Rows the server actually returned. This, not [keptRows], is what must match [serverTotal]. */
    val rawRows: Int,
    /** True only if paging ended because the server said (or showed) there was nothing more. */
    val complete: Boolean,
    /** The server's own total for the same filter; null if it couldn't be read. */
    val serverTotal: Int?
)

/**
 * A sync that returned at least this fraction of the rows already stored is trusted to prune. Only a
 * backstop now: the real proof of completeness is the count match in [pruneSkipReason], so this just has
 * to catch a wildly wrong feed while still allowing a large legitimate retirement (up to 80%).
 */
const val PRUNE_MIN_FETCHED_FRACTION = 0.2

/**
 * Why a finished fetch must NOT delete unseen rows, or null when it is safe. Pure, so it's unit tested.
 * [existingCount] is the number of rows stored before the sync started.
 */
fun pruneSkipReason(existingCount: Int, fetch: FeedFetch): String? = when {
    !fetch.complete -> "fetch stopped before the server ran out of rows (${fetch.rawRows} fetched)"
    fetch.serverTotal == null -> "could not read the server's row count (${fetch.rawRows} fetched)"
    fetch.rawRows != fetch.serverTotal ->
        "fetched ${fetch.rawRows} rows but the server reports ${fetch.serverTotal}"
    !isSafeToPrune(existingCount, fetch.rawRows) ->
        "returned ${fetch.rawRows} rows but $existingCount were stored " +
                "(under ${(PRUNE_MIN_FETCHED_FRACTION * 100).toInt()}%) — looks truncated"
    else -> null
}

/** Reads the server's own row count, or null (logged) if that request fails — never fails the sync. */
internal suspend fun readServerTotalOrNull(log: (String) -> Unit, read: suspend () -> Int?): Int? =
    try {
        read()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Couldn't read the server's row count: ${e.message}")
        null
    }

/** Upper bound on pages per sync, so a server that keeps saying "more" can't loop forever. */
internal const val MAX_FEED_PAGES = 200

/**
 * Whether a finished sync that fetched [fetchedCount] rows may delete the rows it didn't see, given
 * [existingCount] rows were stored before it started. True when the table was empty, or when at
 * least [PRUNE_MIN_FETCHED_FRACTION] of the existing rows were re-fetched. Pure, so it's unit tested.
 */
fun isSafeToPrune(existingCount: Int, fetchedCount: Int): Boolean =
    existingCount <= 0 || fetchedCount >= existingCount * PRUNE_MIN_FETCHED_FRACTION

/**
 * The decide-then-delete step both feeds share, with the delete and logging injected so a unit test can
 * run it against an in-memory table. Runs [deleteUnseen] only when [pruneSkipReason] says the fetch is
 * provably complete; otherwise logs the reason (as [warn]) and deletes nothing. Returns the number of
 * rows removed, or null when it skipped.
 */
internal suspend fun pruneIfSafe(
    what: String,
    existingCount: Int,
    fetch: FeedFetch,
    deleteUnseen: suspend () -> Int,
    warn: (String) -> Unit,
    info: (String) -> Unit
): Int? {
    val reason = pruneSkipReason(existingCount, fetch)
    if (reason != null) {
        warn("Skipping stale-$what cleanup: $reason")
        return null
    }
    val removed = deleteUnseen()
    info("Stale-$what cleanup removed $removed rows (fetched ${fetch.rawRows}, had $existingCount)")
    return removed
}

/** Prunes street segments not seen by sync [syncId]; skips (and logs the reason) if the guard says no. */
suspend fun pruneStaleStreetSegments(context: Context, syncId: Long, existingCount: Int, fetch: FeedFetch) {
    pruneIfSafe(
        "segment", existingCount, fetch,
        deleteUnseen = { AppDatabase.getInstance(context).streetSegmentDao().deleteNotSeenSince(syncId) },
        warn = { android.util.Log.w("DataSF", it) },
        info = { android.util.Log.d("DataSF", it) }
    )
}

/** RPP counterpart of [pruneStaleStreetSegments]. */
suspend fun pruneStaleRppRegulations(context: Context, syncId: Long, existingCount: Int, fetch: FeedFetch) {
    pruneIfSafe(
        "RPP", existingCount, fetch,
        deleteUnseen = { AppDatabase.getInstance(context).rppZoneRegulationDao().deleteNotSeenSince(syncId) },
        warn = { android.util.Log.w("RppSync", it) },
        info = { android.util.Log.d("RppSync", it) }
    )
}
