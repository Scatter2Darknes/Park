package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex

/**
 * Syncs the street-closure feed into the street_closure table. Same shape as RppZoneRepository:
 * a Mutex so two syncs never run at once, page-by-page upserts stamped with a sync id, then pruning.
 *
 * Nothing calls this yet. The park-time check and the Tier 2 background worker (later steps of
 * docs/park-closures-spec.md) are what will.
 */
class StreetClosureRepository(private val context: Context) {

    /** Fetches the whole citywide feed. Returns the number of closures stored by this sync. Throws on a network failure. */
    suspend fun refreshFromNetwork(): Int {
        val dao = AppDatabase.getInstance(context).streetClosureDao()
        if (!fetchMutex.tryLock()) {
            Log.d("ClosureSync", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return dao.count()
        }
        try {
            val now = System.currentTimeMillis()
            val syncId = now

            // Ended closures first: always safe, and it keeps existingCount (the prune guard's
            // baseline) to rows the fetch below could actually return.
            val ended = dao.deleteEndedBy(now)
            if (ended > 0) Log.d("ClosureSync", "Removed $ended ended closures")
            val existingCount = dao.count()

            val fetch = fetchAllStreetClosures(context, cutoffMillis = now, syncId = syncId) { page ->
                dao.insertAll(page)
            }
            Log.d("ClosureSync", "Closure fetch complete=${fetch.complete}, ${fetch.keptRows} kept of ${fetch.rawRows} raw, server says ${fetch.serverTotal}")

            try {
                pruneStaleStreetClosures(context, syncId, existingCount, fetch)
            } catch (e: Exception) {
                Log.w("ClosureSync", "Stale-closure cleanup failed", e)
            }
            // Only a fetch that provably saw the whole feed counts as "checked": a partial one could
            // be missing exactly the closure on the user's block.
            if (fetch.complete && fetch.serverTotal != null && fetch.rawRows == fetch.serverTotal) {
                SettingsRepository(context).setClosuresLastSyncMillis(now)
            }
            return fetch.keptRows
        } finally {
            fetchMutex.unlock()
        }
    }

    companion object {
        private val fetchMutex = Mutex()
    }
}
