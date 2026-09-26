package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate

/**
 * Sync of the temporary-occupancy permits (StreetUsePermitApi.kt), shaped exactly like TowZoneRepository:
 * full citywide fetch, time-based pruning first, then the guarded prune of rows that vanished (pruneIfSafe).
 * A permit cancelled early may linger until its end date because of that guard: an extra "check the signs",
 * the conservative direction.
 */
class StreetUsePermitRepository(private val context: Context) {

    /** Fetches every live permit citywide. Returns the number stored by this sync. Throws on a network failure. */
    suspend fun refreshFromNetwork(): Int {
        val dao = AppDatabase.getInstance(context).streetUsePermitDao()
        if (!fetchMutex.tryLock()) {
            Log.d("PermitSync", "refreshFromNetwork: another sync is already in progress, skipping duplicate")
            return dao.count()
        }
        try {
            val now = System.currentTimeMillis()
            val syncId = now

            val ended = dao.deleteEndedBy(now)
            if (ended > 0) Log.d("PermitSync", "Removed $ended ended permits")
            val existingCount = dao.count()

            val fetch = fetchAllStreetUsePermits(context, LocalDate.now(SF_ZONE), syncId) { page -> dao.insertAll(page) }
            Log.d("PermitSync", "Permit fetch complete=${fetch.complete}, ${fetch.keptRows} kept of ${fetch.rawRows} raw, server says ${fetch.serverTotal}")

            try {
                pruneIfSafe(
                    "permit", existingCount, fetch,
                    deleteUnseen = { dao.deleteNotSeenSince(syncId) },
                    warn = { Log.w("PermitSync", it) },
                    info = { Log.d("PermitSync", it) }
                )
            } catch (e: Exception) {
                Log.w("PermitSync", "Stale-permit cleanup failed", e)
            }

            // Only a fetch that provably saw the whole feed counts as "checked" (same rule as closures and tow).
            if (fetch.complete && fetch.serverTotal != null && fetch.rawRows == fetch.serverTotal) {
                SettingsRepository(context).setPermitsLastSyncMillis(now)
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
