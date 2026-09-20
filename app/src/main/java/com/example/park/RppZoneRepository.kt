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
            StreetDataSyncCenter.onRppSyncAttemptStarted()
            var fetchedThisAttempt = 0
            try {
                return fetchAllRppRegulations { page ->
                    db.rppZoneRegulationDao().insertAll(page)
                    fetchedThisAttempt += page.size
                    StreetDataSyncCenter.onRppSyncAttemptProgress(fetchedThisAttempt)
                }
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
