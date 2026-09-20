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
 * Guards against deleting on bad data: [isSafeToPrune] refuses when the sync returned far fewer rows
 * than the table already held, which is what a truncated or partly-failed response looks like.
 */

/** A sync that returned at least this fraction of the rows already stored is trusted to prune. */
const val PRUNE_MIN_FETCHED_FRACTION = 0.5

/**
 * Whether a finished sync that fetched [fetchedCount] rows may delete the rows it didn't see, given
 * [existingCount] rows were stored before it started. True when the table was empty, or when at
 * least half of the existing rows were re-fetched. Pure, so it's unit tested.
 */
fun isSafeToPrune(existingCount: Int, fetchedCount: Int): Boolean =
    existingCount <= 0 || fetchedCount >= existingCount * PRUNE_MIN_FETCHED_FRACTION

/** Prunes street segments not seen by sync [syncId]; skips (and logs) if the guard says no. */
suspend fun pruneStaleStreetSegments(context: Context, syncId: Long, existingCount: Int, fetchedCount: Int) {
    if (!isSafeToPrune(existingCount, fetchedCount)) {
        android.util.Log.w(
            "DataSF",
            "Skipping stale-segment cleanup: sync returned $fetchedCount rows but $existingCount were stored " +
                    "(under ${(PRUNE_MIN_FETCHED_FRACTION * 100).toInt()}%) — looks truncated"
        )
        return
    }
    val removed = AppDatabase.getInstance(context).streetSegmentDao().deleteNotSeenSince(syncId)
    android.util.Log.d("DataSF", "Stale-segment cleanup removed $removed rows (fetched $fetchedCount, had $existingCount)")
}

/** RPP counterpart of [pruneStaleStreetSegments]. */
suspend fun pruneStaleRppRegulations(context: Context, syncId: Long, existingCount: Int, fetchedCount: Int) {
    if (!isSafeToPrune(existingCount, fetchedCount)) {
        android.util.Log.w(
            "RppSync",
            "Skipping stale-RPP cleanup: sync returned $fetchedCount rows but $existingCount were stored " +
                    "(under ${(PRUNE_MIN_FETCHED_FRACTION * 100).toInt()}%) — looks truncated"
        )
        return
    }
    val removed = AppDatabase.getInstance(context).rppZoneRegulationDao().deleteNotSeenSince(syncId)
    android.util.Log.d("RppSync", "Stale-RPP cleanup removed $removed rows (fetched $fetchedCount, had $existingCount)")
}
