package com.example.park

import android.content.Context
import kotlinx.coroutines.sync.Mutex

/** Mirrors StreetSegmentRepository's shape — a mutex-guarded refresh so a manual "Sync Now"
 *  tap and the periodic background worker can never both be mid-fetch against the same feed at
 *  once. Simpler than StreetSegmentRepository since SFMTA's ArcGIS endpoint hasn't shown the
 *  aggressive per-IP throttling data.sf.gov's Socrata endpoint does — no inter-page delay or
 *  app-token dance needed, just per-page retry. */
class RppZoneRepository(private val context: Context) {
    suspend fun refreshFromNetwork(): Int {
        if (!fetchMutex.tryLock()) {
            android.util.Log.d("RppSync", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return AppDatabase.getInstance(context).rppZoneRegulationDao().count()
        }
        try {
            val db = AppDatabase.getInstance(context)
            // Stamp/prune bookkeeping — see StaleRowPruning.kt.
            val syncId = System.currentTimeMillis()
            val existingCount = db.rppZoneRegulationDao().count()
            StreetDataSyncCenter.onRppSyncAttemptStarted()
            var fetchedThisAttempt = 0
            try {
                val fetch = fetchAllRppRegulations { page ->
                    db.rppZoneRegulationDao().insertAll(page.map { it.copy(lastSeenSyncId = syncId) })
                    fetchedThisAttempt += page.size
                    StreetDataSyncCenter.onRppSyncAttemptProgress(fetchedThisAttempt)
                }
                // fetchAllRppRegulations throws on an unrecoverable page failure, but reaching here is
                // still not enough to delete: the fetch must also be provably complete (reached the last
                // page and matched the server's own count) — see pruneSkipReason.
                try {
                    pruneStaleRppRegulations(context, syncId, existingCount, fetch)
                } catch (e: Exception) {
                    android.util.Log.w("RppSync", "Stale-RPP cleanup failed", e)
                }
                return fetch.keptRows
            } finally {
                StreetDataSyncCenter.onRppSyncAttemptEnded()
            }
        } finally {
            fetchMutex.unlock()
        }
    }

    companion object {
        private val fetchMutex = Mutex()
    }
}
