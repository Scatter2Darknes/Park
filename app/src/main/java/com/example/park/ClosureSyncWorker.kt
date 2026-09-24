package com.example.park

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/*
 * Tier 2 background sync of the street-closure feed (docs/park-closures-spec.md §3/§4 "Background
 * sync"). Its own periodic job, independent of the sweep/RPP/meter one (SweepingDataRefreshWorker):
 * closures change daily, sweeping schedules rarely. Runs whether or not a car is parked, so the data
 * is fresh the moment one is. Off unless the user opts in (Settings, or the one-time offer).
 */

/** How often the closure feed is re-synced in the background. One constant; the spec's
 *  socrata_publish_times.py measurements are meant to tune it (12–24 h). */
const val CLOSURE_BACKGROUND_SYNC_HOURS = 12L

const val CLOSURE_SYNC_WORK_NAME = "closure_background_sync"

class ClosureSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            // Turned off since this run was scheduled (the cancel may race a run already started).
            if (!SettingsRepository(applicationContext).closureBackgroundSync.first()) return Result.success()
            val count = StreetClosureRepository(applicationContext).refreshFromNetwork()
            android.util.Log.d("ClosureSync", "Background closure sync complete: $count closures")
            // Parked cars pick up new, moved or cancelled closures right away.
            refreshParkedSchedulesAfterSync(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e // stopped by the system; the periodic schedule runs it again (see SweepingDataRefreshWorker)
        } catch (e: Exception) {
            // failure(), not retry(), for the same reason as SweepingDataRefreshWorker: the next
            // period comes soon enough, without WorkManager's unbounded backoff on top.
            android.util.Log.e("ClosureSync", "Background closure sync failed", e)
            Result.failure()
        }
    }
}

/**
 * Makes the background closure job match Settings: scheduled when Tier 2 is on (honouring
 * Wi-Fi only), cancelled when it is off. [policy] KEEP at app start (don't reset the timer on
 * every launch); REPLACE when a setting that shapes the job just changed.
 */
suspend fun applyClosureSyncSchedule(
    context: Context,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
) {
    val settings = SettingsRepository(context)
    val workManager = WorkManager.getInstance(context)
    if (!settings.closureBackgroundSync.first()) {
        workManager.cancelUniqueWork(CLOSURE_SYNC_WORK_NAME)
        return
    }
    val wifiOnly = settings.wifiOnlyRefresh.first()
    val request = PeriodicWorkRequestBuilder<ClosureSyncWorker>(CLOSURE_BACKGROUND_SYNC_HOURS, TimeUnit.HOURS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()
    workManager.enqueueUniquePeriodicWork(CLOSURE_SYNC_WORK_NAME, policy, request)
}
