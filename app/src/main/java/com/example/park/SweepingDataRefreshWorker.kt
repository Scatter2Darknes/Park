package com.example.park

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class SweepingDataRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val repository = StreetSegmentRepository(applicationContext)
            val count = repository.refreshFromNetwork()
            android.util.Log.d("DataSF", "Background refresh complete: $count segments")

            // Own try/catch, deliberately not allowed to fail this worker's Result — RPP data
            // is a secondary feature layered on top of the sweeping sync this worker exists
            // for, so a failure fetching it (a bad connection, SFMTA's feed erroring out) should
            // never mark an otherwise-successful sweeping sync as failed.
            try {
                val rppCount = RppZoneRepository(applicationContext).refreshFromNetwork()
                android.util.Log.d("RppSync", "Background refresh complete: $rppCount RPP regulations")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RppSync", "Background RPP refresh failed", e)
            }

            // Same pattern as RPP above — a secondary layer, own try/catch so it can never fail
            // this worker's Result.
            try {
                val meterCount = MeteredZoneRepository(applicationContext).refreshFromNetwork()
                android.util.Log.d("MeterSync", "Background refresh complete: $meterCount metered zones")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MeterSync", "Background meter refresh failed", e)
            }

            Result.success()
        } catch (e: CancellationException) {
            // The system stopped this worker (a constraint like network connectivity
            // momentarily not holding, the execution-time budget for ordinary background work
            // being exceeded, the process being torn down, etc.) rather than the fetch itself
            // failing. Previously this fell into the generic catch below and got logged as
            // "Background refresh failed", which is misleading — it's expected, normal
            // background-execution behavior, and WorkManager reschedules a stopped periodic
            // worker on its own. Rethrowing (rather than swallowing it into a returned Result)
            // is also just correct structured-concurrency practice: CancellationException is
            // coroutines' own propagation mechanism, not an ordinary error to catch and handle.
            android.util.Log.d("DataSF", "Background refresh was cancelled by the system \u2014 will resume on the next scheduled attempt")
            throw e
        } catch (e: Exception) {
            // Result.failure() rather than Result.retry() — retry() triggers WorkManager's
            // own exponential backoff (unconfigured here, so it uses Android's default, which
            // grows unboundedly with repeated retries) stacked ON TOP of the in-app retry
            // backoff fetchWithRetry already did per page. That extra, opaque outer layer
            // produced quiet gaps longer than pollUntilSettled's stability window could safely
            // assume, which is what let a mid-backoff pause get misread as "sync is done."
            // failure() just lets this scheduled run end; the periodic schedule still fires
            // again at its normal interval, and StreetDataSyncCenter's own proactive
            // app-launch check (see its initialize()) already covers "try again soon" without
            // needing WorkManager's own hidden retry timing at all.
            android.util.Log.e("DataSF", "Background refresh failed", e)
            Result.failure()
        }
    }
}