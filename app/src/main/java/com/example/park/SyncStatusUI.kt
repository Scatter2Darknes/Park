package com.example.park

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** "1,234 of 5,678 segments fetched…" when the feed's total is known (read upfront — see
 *  StreetSegmentRepository/RppZoneRepository/MeteredZoneRepository), else just "1,234 segments
 *  fetched…" — shared by the Setup UI (this file) and Settings' "Refresh Data Now" section so
 *  the two never drift apart on wording. */
fun formatFetchProgress(label: String, fetched: Int, total: Int?): String =
    if (total != null && total > 0) "${"%,d".format(fetched)} of ${"%,d".format(total)} $label fetched…"
    else "${"%,d".format(fetched)} $label fetched…"

/**
 * A full-width bar rendered once in MainActivity, above whichever screen is currently showing
 * (not floating on top of it) — so it persists across every screen instead of the "syncing"
 * indicator only existing on the map, and pushes screen content down rather than risking
 * collision with each screen's own top bar/buttons. Tapping it reopens the dialog if the
 * person has dismissed it. Only rendered at all while !isFullySynced (call site's job).
 */
@Composable
fun SyncStatusBar(onClick: () -> Unit) {
    val totalSegmentCount by StreetDataSyncCenter.totalSegmentCount.collectAsState()
    val currentAttemptFetchedCount by StreetDataSyncCenter.currentAttemptFetchedCount.collectAsState()
    val currentAttemptTotalCount by StreetDataSyncCenter.currentAttemptTotalCount.collectAsState()
    val isSyncRunning by StreetDataSyncCenter.isSyncRunning.collectAsState()

    Surface(
        // enableEdgeToEdge() (MainActivity) means content draws behind the system status bar
        // by default — without this, the bar rendered right at y=0, directly under the OS
        // clock/battery/signal icons instead of below them.
        modifier = Modifier.fillMaxWidth().statusBarsPadding().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (isSyncRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                // Plain Unicode glyph, matching this project's existing convention of avoiding
                // a Material Icons dependency for simple indicators.
                Text("\u26A0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(10.dp))
            // currentAttemptFetchedCount (not totalSegmentCount) whenever a sync is actively
            // in flight — see StreetDataSyncCenter's doc comment on it: a re-sync of an
            // already-populated table can leave totalSegmentCount looking frozen at the old
            // number for a while (insertAll is a REPLACE, so re-walking rows that already
            // exist doesn't move the DB's total), which read as "nothing is happening" even
            // while Logcat showed pages actively landing.
            val attemptCount = currentAttemptFetchedCount
            val count = totalSegmentCount
            Text(
                when {
                    attemptCount != null -> "Syncing street data\u2026 ${formatFetchProgress("segments", attemptCount, currentAttemptTotalCount)}"
                    isSyncRunning && (count ?: 0) > 0 -> "Syncing street data\u2026 ${"%,d".format(count)} loaded"
                    isSyncRunning -> "Syncing street data\u2026"
                    (count ?: 0) > 0 -> "${"%,d".format(count)} segments loaded so far \u2014 tap for options"
                    else -> "Street data not fully loaded \u2014 tap for options"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * The interactable dialog behind the status bar above — status/progress, manual sync, file
 * import, and (once built) a link into Settings for configuring a personal API key. Unlike
 * the old full-screen takeover this replaces, the map (and every other screen) stays fully
 * usable underneath; this is dismissible and the bar above brings it back.
 */
@Composable
fun SyncStatusDialog(onDismiss: () -> Unit, onGoToSettings: () -> Unit) {
    val totalSegmentCount by StreetDataSyncCenter.totalSegmentCount.collectAsState()
    val currentAttemptFetchedCount by StreetDataSyncCenter.currentAttemptFetchedCount.collectAsState()
    val currentAttemptTotalCount by StreetDataSyncCenter.currentAttemptTotalCount.collectAsState()
    val rppCurrentAttemptFetchedCount by StreetDataSyncCenter.rppCurrentAttemptFetchedCount.collectAsState()
    val rppCurrentAttemptTotalCount by StreetDataSyncCenter.rppCurrentAttemptTotalCount.collectAsState()
    val meterCurrentAttemptPhase by StreetDataSyncCenter.meterCurrentAttemptPhase.collectAsState()
    val meterCurrentAttemptFetchedCount by StreetDataSyncCenter.meterCurrentAttemptFetchedCount.collectAsState()
    val meterCurrentAttemptTotalCount by StreetDataSyncCenter.meterCurrentAttemptTotalCount.collectAsState()
    val isSyncRunning by StreetDataSyncCenter.isSyncRunning.collectAsState()
    val isBusy by StreetDataSyncCenter.isBusy.collectAsState()
    val statusMessage by StreetDataSyncCenter.statusMessage.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { StreetDataSyncCenter.importFromUri(it) } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                if (isSyncRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "Street sweeping data",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                // currentAttemptFetchedCount while a sync is actively in flight — see
                // StreetDataSyncCenter's doc comment: totalSegmentCount can look frozen during
                // a re-sync of an already-populated table (insertAll is a REPLACE, so
                // re-walking existing rows doesn't move the DB's total), so this shows the
                // honest in-flight number instead, including it visibly resetting on a fresh
                // attempt rather than silently displaying a stale total.
                val attemptCount = currentAttemptFetchedCount
                val count = totalSegmentCount ?: 0
                Text(
                    when {
                        attemptCount != null -> formatFetchProgress("segments", attemptCount, currentAttemptTotalCount) + " this sync\u2026"
                        count > 0 -> "${"%,d".format(count)} segments loaded so far\u2026"
                        isSyncRunning -> "Downloading now \u2014 this can take a minute on first launch."
                        else -> "Not synced yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                // Runs sequentially AFTER the segment fetch above completes (see
                // triggerManualRefresh/SweepingDataRefreshWorker) — shown as its own line so
                // "Sync Now" doesn't look like it's finished/hung during this second phase,
                // which previously had no progress indication of its own at all.
                rppCurrentAttemptFetchedCount?.let { count ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatFetchProgress("RPP zone regulations", count, rppCurrentAttemptTotalCount) + " this sync…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                // Meter sync runs as its own two-phase pass (locations, then operating
                // schedules — see MeteredZoneRepository) after RPP — same reasoning as the RPP
                // line above: without this, "Sync Now" looks hung during this third phase.
                meterCurrentAttemptPhase?.let { phase ->
                    val fetched = meterCurrentAttemptFetchedCount ?: 0
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatFetchProgress(phase, fetched, meterCurrentAttemptTotalCount) + " this sync…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                statusMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠\uFE0F This can take a few minutes \u2014 force-quitting the app before it finishes " +
                            "will interrupt it (it'll pick back up next time you open the app, but " +
                            "leaving it running is faster).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { StreetDataSyncCenter.triggerManualRefresh() },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isBusy) "Working\u2026" else "Sync Now")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import from a file instead")
                }
                // Both buttons share isBusy (a sync and an import can't safely run at once —
                // they'd both be writing to the same table), but only Sync Now's own label
                // change ("Working…") explained that. Import just went grey with nothing
                // saying why, which read as broken rather than "busy" — this makes the reason
                // explicit instead of relying on the person to notice the other button's text.
                if (isBusy) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A sync is already in progress \u2014 wait for it to finish before importing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Settings doesn't have an API-key section yet (see the project note on
                // sharing this app with friends/family) — this already routes there so it
                // starts working the moment that section exists, rather than needing this
                // dialog touched again later.
                TextButton(onClick = onGoToSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Configure API key in Settings")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Hide")
                }
            }
        }
    }
}