package com.example.park

import android.content.Context
import kotlinx.coroutines.sync.Mutex

class StreetSegmentRepository(private val context: Context) {
    suspend fun refreshFromNetwork(): Int {
        // tryLock rather than trusting callers to have checked isSyncRunning first — that's a
        // check-then-act race across a suspend boundary (WorkManager's first WorkInfo
        // emission can lag just long enough for two independent callers to both see "nothing
        // running" and both proceed), and that race is almost certainly what produced two
        // genuinely interleaved fetch sequences hammering the same endpoint at once (one
        // around offset 1000, one around offset 7000, in the same Logcat window). A Mutex is
        // atomic, so only one caller can ever actually be inside the fetch at a time —
        // whoever loses just gets back the current count with no wasted network activity.
        if (!fetchMutex.tryLock()) {
            android.util.Log.d("DataSF", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return AppDatabase.getInstance(context).streetSegmentDao().count()
        }
        try {
            val db = AppDatabase.getInstance(context)
            // "Segments fetched this attempt" is tracked separately from the DB's total row count
            // (StreetDataSyncCenter.currentAttemptFetchedCount) because insertAll uses REPLACE —
            // re-walking pages that already exist in the table (which any fresh attempt does,
            // since there's no persisted "resume from offset X" checkpoint) doesn't move the DB's
            // total count at all until the crawl passes into genuinely new rows. Without this
            // separate counter, re-syncing an already-partially-populated table looked frozen at
            // the old total for however long it took to re-walk back past where it left off,
            // even though pages were visibly landing in Logcat the whole time.
            // Stamps every row this sync upserts, so a later cleanup can tell which rows the feed
            // no longer returns (see StaleRowPruning.kt). existingCount is read BEFORE the sync
            // starts writing, for the "did we get suspiciously few rows back" guard.
            val syncId = System.currentTimeMillis()
            val existingCount = db.streetSegmentDao().count()
            StreetDataSyncCenter.onSyncAttemptStarted()
            var fetchedThisAttempt = 0
            try {
                // Inserted page-by-page rather than accumulated in memory and written once at
                // the end: previously, a single page failing after retries were exhausted
                // (e.g. the HTTP 425 throttling seen in testing) threw away every row already
                // fetched in that sync, even if 5+ pages had already succeeded — the next
                // attempt (and the one after that, backing off further each time) started from
                // zero with nothing on the map to show for it. Now whatever synced before a
                // failure stays saved.
                val count = fetchAllSegments(context) { page ->
                    db.streetSegmentDao().insertAll(page.map { it.copy(lastSeenSyncId = syncId) })
                    fetchedThisAttempt += page.size
                    StreetDataSyncCenter.onSyncAttemptProgress(fetchedThisAttempt)
                }
                // Recorded here (not at each call site) so manual refresh and the periodic
                // background worker both update the same timestamp automatically. Only reached
                // on a fully successful sync (fetchAllSegments throws on an unrecoverable page
                // failure), consistent with "last synced" meaning a complete sync, not a
                // partial one.
                SettingsRepository(context).setLastRefreshMillis(System.currentTimeMillis())
                // Same condition as the timestamp above — only reached after a FULLY successful sync.
                // A failure here must not turn a good sync into a failed one.
                try {
                    pruneStaleStreetSegments(context, syncId, existingCount, count)
                } catch (e: Exception) {
                    android.util.Log.w("DataSF", "Stale-segment cleanup failed", e)
                }
                refreshParkedSchedulesAfterSync(context)
                return count
            } finally {
                StreetDataSyncCenter.onSyncAttemptEnded()
            }
        } finally {
            fetchMutex.unlock()
        }
    }

    companion object {
        // Shared across every StreetSegmentRepository instance (a new one is created per call
        // site — the periodic worker, StreetDataSyncCenter, etc.) since the lock needs to
        // cover ALL callers, not just repeated calls through the same instance.
        private val fetchMutex = Mutex()
    }
}