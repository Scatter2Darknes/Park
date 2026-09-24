package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate

/**
 * Sync of the temporary tow-zone feed (docs/park-closures-spec.md §3), shaped like
 * StreetClosureRepository: full citywide fetch, time-based pruning first, then the guarded prune of
 * rows that vanished (pruneIfSafe). A permit cancelled early may linger until its end date because of
 * that guard, giving a false tow warning — the conservative direction, accepted in the spec.
 */
class TowZoneRepository(private val context: Context) {

    /** Fetches the whole citywide feed. Returns the number of zones stored by this sync. Throws on a network failure. */
    suspend fun refreshFromNetwork(): Int {
        val dao = AppDatabase.getInstance(context).towZoneDao()
        if (!fetchMutex.tryLock()) {
            Log.d("TowSync", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return dao.count()
        }
        try {
            val now = System.currentTimeMillis()
            val syncId = now
            // Yesterday, not today: a zone whose last day was yesterday can still have an overnight
            // window running into this morning.
            val fromDate = LocalDate.now(SF_ZONE).minusDays(1)

            val ended = dao.deleteEndedBefore(fromDate.toEpochDay())
            if (ended > 0) Log.d("TowSync", "Removed $ended ended tow zones")
            val existingCount = dao.count()

            val fetch = fetchAllTowZones(context, fromDate, syncId) { page -> dao.insertAll(page) }
            Log.d("TowSync", "Tow fetch complete=${fetch.complete}, ${fetch.keptRows} kept of ${fetch.rawRows} raw, server says ${fetch.serverTotal}")

            try {
                pruneStaleTowZones(context, syncId, existingCount, fetch)
            } catch (e: Exception) {
                Log.w("TowSync", "Stale-tow-zone cleanup failed", e)
            }

            val settings = SettingsRepository(context)
            // Never fails the sync: without it the app simply can't vouch for freshness, and says so.
            try {
                fetchTowNewestEntryMillis(context)?.let {
                    settings.setTowNewestEntryMillis(it)
                    Log.d("TowSync", "Newest tow permit in the feed was entered at ${java.time.Instant.ofEpochMilli(it)}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TowSync", "Couldn't read the tow feed's newest entry", e)
            }
            // Same rule as closures: only a fetch that provably saw the whole feed counts as "checked".
            if (fetch.complete && fetch.serverTotal != null && fetch.rawRows == fetch.serverTotal) {
                settings.setTowLastSyncMillis(now)
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
