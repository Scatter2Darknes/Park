package com.example.park

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tracks street-segment sync status (segment count, whether a sync is running, whether the
 * first-ever sync has settled) and runs manual refresh/import — all app-scoped via
 * ParkApp's appScope, not any one screen's rememberCoroutineScope(). This used to live as
 * local state inside MapScreen, which caused two real problems: the status was invisible on
 * every other screen, and navigating away from Map while a manual refresh or import was
 * in-flight actually cancelled it (rememberCoroutineScope()'s scope is cancelled when its
 * composable leaves composition — appScope never is). Mirrors BluetoothConnectionCenter's
 * shape for the same reason that one exists: a single source of truth any screen can
 * collectAsState() from.
 */
object StreetDataSyncCenter {
    private val _totalSegmentCount = MutableStateFlow<Int?>(null)
    val totalSegmentCount: StateFlow<Int?> = _totalSegmentCount.asStateFlow()

    // Rows fetched during the CURRENT sync attempt specifically (manual or periodic) — null
    // when no attempt is in flight, 0 the instant one starts, climbing with every page. Kept
    // separate from totalSegmentCount because insertAll uses REPLACE: re-walking a table that
    // already has data (any fresh attempt does this, since there's no persisted "resume from
    // offset X" checkpoint) doesn't move the DB's total row count at all until the crawl passes
    // into genuinely new rows — so totalSegmentCount alone looked frozen/stale during a re-sync
    // of an already-populated table, even while pages were visibly landing in Logcat.
    private val _currentAttemptFetchedCount = MutableStateFlow<Int?>(null)
    val currentAttemptFetchedCount: StateFlow<Int?> = _currentAttemptFetchedCount.asStateFlow()

    // Same idea, for the RPP zone regulation feed — a separate fetch that runs sequentially
    // after the sweeping sync (see triggerManualRefresh and SweepingDataRefreshWorker), so its
    // progress is tracked independently rather than folded into the count above.
    private val _rppCurrentAttemptFetchedCount = MutableStateFlow<Int?>(null)
    val rppCurrentAttemptFetchedCount: StateFlow<Int?> = _rppCurrentAttemptFetchedCount.asStateFlow()

    private val _isSyncRunning = MutableStateFlow(false)
    val isSyncRunning: StateFlow<Boolean> = _isSyncRunning.asStateFlow()

    // True once there's confidently nothing left to wait for — either the database already
    // had data the moment the app started (the common case: periodic refreshes happening
    // 24h+ apart against an already-populated table), or a from-empty first sync has settled.
    // Everything gating the "not synced yet" UI (the dialog, the persistent indicator, dimmed
    // location-dependent buttons) keys off this rather than a raw segment count, so a routine
    // periodic re-sync of an already-populated table never re-triggers any of that.
    private val _isFullySynced = MutableStateFlow(false)
    val isFullySynced: StateFlow<Boolean> = _isFullySynced.asStateFlow()

    // Whether the person has manually closed the "still syncing" dialog while not yet fully
    // synced. A small persistent indicator stays up regardless so it's never fully hidden.
    private val _dialogDismissed = MutableStateFlow(false)
    val dialogDismissed: StateFlow<Boolean> = _dialogDismissed.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    // A manual refresh or import currently in flight (mutually exclusive in practice, since
    // both check this before starting).
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private var appContext: Context? = null
    private var appScope: CoroutineScope? = null
    private var initialized = false

    fun initialize(context: Context, scope: CoroutineScope) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        appScope = scope

        scope.launch {
            // Whether a WorkManager-observed run is happening right now, checked before
            // deciding anything below — avoids racing a proactive trigger (below) against an
            // already-in-progress periodic run this coroutine just hasn't observed yet, since
            // getWorkInfosForUniqueWorkFlow's first emission reflects current state right away.
            val firstWorkInfos = WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(SWEEPING_REFRESH_WORK_NAME)
                .first()
            _isSyncRunning.value = firstWorkInfos.any { it.state == WorkInfo.State.RUNNING }

            val initialCount = AppDatabase.getInstance(context.applicationContext).streetSegmentDao().count()
            _totalSegmentCount.value = initialCount

            // lastRefreshMillis (not segment count) is the ground truth for "has a sync ever
            // actually finished" — it's only ever set at the very end of a fully successful
            // refreshFromNetwork(), never on a partial one. Segment count alone can't tell
            // "genuinely complete" apart from "interrupted partway with some rows already
            // saved" (e.g. force-stopping the app mid-fetch) — that gap previously meant a
            // partial sync got misread as done and never resumed.
            val everCompletedASync = SettingsRepository(context.applicationContext).lastRefreshMillis.first() != null
            _isFullySynced.value = everCompletedASync

            if (!everCompletedASync && !_isSyncRunning.value) {
                // No confirmed-complete sync yet, and nothing is currently running to handle
                // it. Normally the periodic schedule would pick this up on its own, but a
                // force-stop specifically puts the app into a state where Android won't run
                // any of its scheduled background work again until the app is reopened — so
                // without this, reopening the app after a force-stop mid-sync could otherwise
                // wait up to a full REFRESH_INTERVAL_HOURS before anything resumed on its own.
                triggerManualRefresh()
            }

            pollUntilSettled(context.applicationContext) // no-op if already settled above
        }

        scope.launch {
            WorkManager.getInstance(context.applicationContext)
                .getWorkInfosForUniqueWorkFlow(SWEEPING_REFRESH_WORK_NAME)
                .collect { infos ->
                    _isSyncRunning.value = infos.any { it.state == WorkInfo.State.RUNNING }
                }
        }
    }

    // Polls the running count until it's confident the sync is done. Deliberately doesn't
    // rely on isSyncRunning alone: a page that fails after all retries returns Result.retry(),
    // which puts WorkManager's WorkInfo back in ENQUEUED (not RUNNING) for the entire backoff
    // wait before the next attempt. That backoff wait can now run up to ~24 seconds worst case
    // (3 attempts at up to 4s/8s/12s) — comfortably longer than a single 2-second poll tick, so
    // requiring just one poll of "count unchanged" was actually still a false positive
    // waiting to happen: any single 2-second slice landing inside a mid-backoff gap looks
    // exactly like "done" to a one-poll check, even though a real retry is seconds away. This
    // instead requires the count to have been stable for a full SETTLE_STABILITY_MILLIS
    // stretch — comfortably longer than the worst-case backoff gap — before calling it done.
    private suspend fun pollUntilSettled(context: Context) {
        var previousCount = _totalSegmentCount.value ?: 0
        var lastChangeAtMillis = System.currentTimeMillis()
        // Backs off up to POLL_INTERVAL_MAX_MILLIS whenever the count hasn't moved, and resets
        // back to the fast interval the moment it does — an active sync keeps landing pages
        // often enough to stay on the fast interval throughout, so this only actually slows
        // polling down during genuine dead time (no network for an extended stretch, DataSF
        // erroring out repeatedly, etc.). Without this, a sync that never completes polled a DB
        // count query every 2s for as long as the app process stayed alive, with no cap at all.
        var pollIntervalMillis = POLL_INTERVAL_START_MILLIS
        while (!_isFullySynced.value) {
            delay(pollIntervalMillis)
            val current = AppDatabase.getInstance(context).streetSegmentDao().count()
            _totalSegmentCount.value = current
            if (current != previousCount) {
                lastChangeAtMillis = System.currentTimeMillis()
                pollIntervalMillis = POLL_INTERVAL_START_MILLIS
            } else {
                pollIntervalMillis = (pollIntervalMillis * 2).coerceAtMost(POLL_INTERVAL_MAX_MILLIS)
            }
            val stableForMillis = System.currentTimeMillis() - lastChangeAtMillis
            if (!_isSyncRunning.value && current > 0 && stableForMillis >= SETTLE_STABILITY_MILLIS) {
                _isFullySynced.value = true
            }
            previousCount = current
        }
    }

    private const val SETTLE_STABILITY_MILLIS = 30_000L
    private const val POLL_INTERVAL_START_MILLIS = 2000L
    private const val POLL_INTERVAL_MAX_MILLIS = 60_000L

    fun dismissDialog() {
        _dialogDismissed.value = true
    }

    // Called by StreetSegmentRepository.refreshFromNetwork() — the single place both the
    // periodic worker and manual refresh actually fetch through — so this reflects whichever
    // one is currently running without either caller needing to know about the other.
    fun onSyncAttemptStarted() {
        _currentAttemptFetchedCount.value = 0
    }

    fun onSyncAttemptProgress(fetchedSoFar: Int) {
        _currentAttemptFetchedCount.value = fetchedSoFar
    }

    fun onSyncAttemptEnded() {
        _currentAttemptFetchedCount.value = null
    }

    // Same idea as the three functions above, but for the RPP zone regulation feed — a
    // separate fetch that runs sequentially after the sweeping sync (see
    // RppZoneRepository.refreshFromNetwork()), so this progresses independently rather than
    // being folded into currentAttemptFetchedCount.
    fun onRppSyncAttemptStarted() {
        _rppCurrentAttemptFetchedCount.value = 0
    }

    fun onRppSyncAttemptProgress(fetchedSoFar: Int) {
        _rppCurrentAttemptFetchedCount.value = fetchedSoFar
    }

    fun onRppSyncAttemptEnded() {
        _rppCurrentAttemptFetchedCount.value = null
    }

    /**
     * For testing the setup UI itself, not for end users — there's no other way to see the
     * bar/dialog again once isFullySynced has ever flipped true, since nothing else resets it
     * (by design: it's meant to disappear for good once setup is done). Doesn't touch the
     * actual segment data, so pressing "Sync Now" afterward re-syncs correctly and this
     * settles back to true exactly like a real first sync would.
     */
    fun forceShowSetupUiForTesting() {
        _isFullySynced.value = false
        _dialogDismissed.value = false
    }

    fun reopenDialog() {
        _dialogDismissed.value = false
    }

    fun triggerManualRefresh() {
        val context = appContext ?: return
        val scope = appScope ?: return
        if (_isBusy.value) return
        if (_isSyncRunning.value) {
            // The periodic background worker is already mid-fetch — starting a second,
            // independent fetch loop on top of it doesn't make it finish faster; it means two
            // separate request streams hit the same endpoint at once, roughly halving the real
            // gap between requests the server actually sees even though each stream still
            // thinks it's pacing itself correctly. This was very likely the real cause of a
            // sudden jump to near-every-other-request throttling during testing.
            _statusMessage.value = "Already syncing in the background \u2014 no need to start another."
            return
        }
        _isBusy.value = true
        _statusMessage.value = null
        scope.launch {
            try {
                val count = StreetSegmentRepository(context).refreshFromNetwork()
                _totalSegmentCount.value = AppDatabase.getInstance(context).streetSegmentDao().count()
                _isFullySynced.value = true // a manual refresh that didn't throw is as good a "done" signal as the poll's own heuristic

                // Own try/catch — RPP zone data is a secondary layer on top of the sweeping
                // sync this center exists to gate the UI on, so a failure fetching it shouldn't
                // turn an otherwise-successful "Sync Now" into a reported failure.
                val rppStatusSuffix = try {
                    val rppCount = RppZoneRepository(context).refreshFromNetwork()
                    " (+${"%,d".format(rppCount)} RPP zone regulations)"
                } catch (e: Exception) {
                    android.util.Log.e("RppSync", "Manual RPP refresh failed", e)
                    ""
                }
                // Same reasoning as RPP above — meter badges are a secondary layer, and
                // MeteredZoneRepository already degrades to location-only zones on its own
                // (see its doc comment) rather than throwing for the expected data.sf.gov
                // cert-chain risk, so this catch is only for a genuinely unexpected failure.
                val meterStatusSuffix = try {
                    val meterCount = MeteredZoneRepository(context).refreshFromNetwork()
                    " (+${"%,d".format(meterCount)} metered zones)"
                } catch (e: Exception) {
                    android.util.Log.e("MeterSync", "Manual meter refresh failed", e)
                    ""
                }
                _statusMessage.value = "Synced ${"%,d".format(count)} segments.$rppStatusSuffix$meterStatusSuffix"
            } catch (e: Exception) {
                // Previously uncaught in the old Settings-only button, which crashed the app
                // on any failure (a bad connection, DataSF throttling, a malformed row) — the
                // background worker already handled this the right way; this now matches it.
                android.util.Log.e("DataSF", "Manual refresh failed", e)
                // Still re-read the count even on failure — StreetSegmentRepository inserts
                // page-by-page, so a failure partway through still leaves real progress saved.
                // Previously this branch left totalSegmentCount frozen at whatever it was
                // before this attempt started, which looked like the counter had silently
                // stopped updating even while Logcat showed pages still landing (the periodic
                // worker's own independent retries, kept running in the background regardless
                // of this manual attempt's outcome).
                _totalSegmentCount.value = AppDatabase.getInstance(context).streetSegmentDao().count()
                _statusMessage.value = "Refresh failed (${e.message ?: "unknown error"}). " +
                        "Segments fetched before the failure were still saved \u2014 try again in a moment."
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun importFromUri(uri: Uri) {
        val context = appContext ?: return
        val scope = appScope ?: return
        if (_isBusy.value) return
        _isBusy.value = true
        _statusMessage.value = null
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text == null) {
                    _statusMessage.value = "Could not read the selected file."
                } else {
                    // Detected from content, not the file's name/extension/MIME type \u2014
                    // none of those are reliable coming from a mobile browser's "Download"
                    // action (the import picker itself now accepts "*/*" for exactly this
                    // reason). The JSON export always starts a top-level array with '['
                    // (ignoring leading whitespace); anything else is treated as this
                    // dataset's CSV export shape instead.
                    val parsed = if (text.trimStart().startsWith("[")) {
                        parseSegments(text)
                    } else {
                        parseSegmentsFromCsv(text)
                    }
                    if (parsed.isEmpty()) {
                        _statusMessage.value = "That file didn't contain any recognizable street segments \u2014 " +
                                "is it a DataSF street sweeping export (yhqp-riqs), as JSON or CSV?"
                    } else {
                        // Stamped like a network sync (so a later network sync's cleanup treats these
                        // rows as seen-at-this-time) but NEVER pruned: a file isn't authoritative about
                        // what else exists, so an import must not delete anything (see StaleRowPruning.kt).
                        val importId = System.currentTimeMillis()
                        AppDatabase.getInstance(context).streetSegmentDao()
                            .insertAll(parsed.map { it.copy(lastSeenSyncId = importId) })
                        _totalSegmentCount.value = AppDatabase.getInstance(context).streetSegmentDao().count()
                        _isFullySynced.value = true // data's here now; stop waiting on the network sync
                        // Also persisted, not just set in memory \u2014 without this, a
                        // force-stop and relaunch re-read lastRefreshMillis as still null on
                        // cold start (see initialize() below), which re-triggered the "still
                        // syncing" dialog and pill even though the data was already fully
                        // imported and sitting in the database the whole time. Matches
                        // exactly what a successful network refresh already does in
                        // StreetSegmentRepository \u2014 a manual import is just as valid a
                        // "sync completed" event as a network one.
                        SettingsRepository(context).setLastRefreshMillis(System.currentTimeMillis())
                        refreshParkedSchedulesAfterSync(context)
                        _statusMessage.value = "Imported ${"%,d".format(parsed.size)} segments."
                    }
                }
            } catch (e: Exception) {
                _statusMessage.value = "Import failed \u2014 the file doesn't look like a valid export (${e.message ?: "parse error"})."
            } finally {
                _isBusy.value = false
            }
        }
    }
}