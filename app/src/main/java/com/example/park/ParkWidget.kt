package com.example.park

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.ContentScale
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Home screen widget. Design: an outer colored frame (the parked segment's current
 * road-status color, same colors configured in Settings) around the car's photo as a
 * full-bleed background — or its own highlight color as a background if no photo is set —
 * with name/countdown/next-sweep text drawn on top in a color chosen per-car in Manage Cars
 * (Customize > Widget Text Color), since neither a photo nor a status color has a predictable
 * brightness to auto-contrast against reliably.
 *
 * IMPORTANT: Glance is a different composition framework from the rest of this app's UI, not
 * regular Jetpack Compose — widgets render via RemoteViews under the hood, so only Glance's
 * own curated composables work here, not androidx.compose.material3/foundation. This uses
 * LocalSize, SizeMode.Exact, Image/ImageProvider, and cornerRadius, none of which I can
 * compile-test from here — if this doesn't build, these are the first things to check
 * against your exact glance-appwidget version. cornerRadius specifically I'm least certain
 * of; androidx.glance.appwidget.cornerRadius is my best guess at the import path, since
 * rounded corners are an app-widget-specific background concern rather than a generic
 * Glance layout modifier.
 */
class ParkWidget : GlanceAppWidget() {
    // Testing a theory: switched from SizeMode.Exact to Single. Exact tracks the widget's
    // real current dp size and can maintain size-bucketed compositions internally — exactly
    // the kind of feature that could explain the pattern seen across every test so far
    // (provideGlance renders correctly exactly once, right after cold start, and never
    // again for the rest of the process's life, regardless of what triggers the next
    // updateAll() call — ruling out timing, cancellation, and session-identity issues we'd
    // already tested). Single uses one fixed composition regardless of the widget's actual
    // on-screen size, which is the simplest, most battle-tested Glance size mode.
    //
    // TRADE-OFF: this drops LocalSize.current tracking the real placed size, so the
    // size.height >= 80.dp check below (hiding the next-sweep line on a short widget) no
    // longer reflects reality — it'll always see whatever Single's fixed default size is.
    // Worth confirming this actually fixes the sync issue before deciding how to bring back
    // size-based content (SizeMode.Responsive with fixed breakpoints is the middle ground).
    override val sizeMode = SizeMode.Single

    companion object {
        // A single stable instance shared by ParkWidgetReceiver and every updateParkWidget()
        // call, rather than each side creating its own separate ParkWidget(). Confirmed via
        // Logcat: calling updateAll() on a freshly-created ParkWidget() instance completes
        // without error but never actually triggers provideGlance() again after the widget's
        // first cold-start render (which goes through the receiver's own instance) — pointing
        // at Glance's session bookkeeping caring about which widget instance is being updated,
        // not just the GlanceId. Routing every update through this same instance is a direct,
        // low-risk test of that theory.
        val instance = ParkWidget()
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // CONFIRMED via Glance's own documented behavior (not something visible from code
        // alone — see the long comment on updateParkWidget below for the full debugging
        // history): update()/updateAll() do NOT restart provideGlance if a composition
        // session is already running — Glance keeps a session alive for ~45 seconds after
        // each render specifically so the intended pattern is to OBSERVE a live data source
        // from inside the composition, not to expect a fresh top-to-bottom provideGlance
        // execution on every update() call. This is why every previous fix (mutex, shared
        // instance, ProcessLifecycleOwner, dropping the redraw nudge, SizeMode) still left the
        // widget stuck showing whatever was true when its current session started, for as long
        // as edits kept landing inside that session's live window.
        //
        // widgetSummaryFlow below re-polls the DB every few seconds for as long as this
        // session stays alive, so the widget catches up on its own without needing a fresh
        // provideGlance call at all. updateParkWidget()/enqueueWidgetRefresh() are still
        // useful for the case where NO session is currently running (nothing polling, e.g.
        // the widget hasn't been touched in a while) — that's the case where update() DOES
        // start a fresh provideGlance per Glance's own docs.
        val initial = loadWidgetSummary(context)
        provideContent {
            val summary by remember { widgetSummaryFlow(context) }.collectAsState(initial = initial)
            LaunchedEffect(summary) {
                android.util.Log.d(
                    "ParkWidget",
                    "provideGlance: rendering id=$id car=${summary.carName} countdown=${summary.countdownText} extra=${summary.extraParkedCount}"
                )
            }
            WidgetContent(summary)
        }
    }
}

// Polling rather than a proper reactive Room/DataStore Flow chain — lower-risk than
// restructuring the DAOs this depends on (loadActiveParkedCars, sweepThresholdsSnapshot,
// etc.) into Flow-returning queries, while still satisfying Glance's documented guidance to
// "observe your sources of data within the composition" rather than relying on repeated
// update() calls to force a fresh provideGlance run. Only ticks for as long as the Glance
// session that's collecting it stays alive (~45s after the last render or update() call, per
// Glance's docs) — it isn't a perpetual background loop outliving the widget's own session.
private fun widgetSummaryFlow(context: Context): Flow<WidgetSummary> = flow {
    while (true) {
        emit(loadWidgetSummary(context))
        delay(5_000L)
    }
}

private data class WidgetSummary(
    val carName: String?,
    val countdownText: String?,
    val nextSweepText: String?,
    val photoBitmap: Bitmap?,
    val frameColor: Color,
    val fallbackBackgroundColor: Color,
    val textColor: Color,
    val extraParkedCount: Int,
    // Only set for the "NEEDS_CONFIRMATION" transient state — lets the widget's tap deep-link
    // straight into that confirm flow (same extras MainActivity already reads to resolve
    // pendingAutoDetect), instead of just opening the app generically like every other state.
    val pendingConfirmCarId: Long? = null,
    val pendingConfirmPoint: LatLng? = null
)

private suspend fun loadWidgetSummary(context: Context): WidgetSummary {
    // Idle-state colors need to respect the app's own Color Theme setting rather than being
    // hardcoded — a hardcoded white/black pairing looked out of place sitting on a dark home
    // screen when everything else in the app (map, Settings, etc.) already follows this same
    // Day/Night/Automatic setting. provideGlance() has a plain Context, not a Composable one,
    // so isSystemInDarkTheme() isn't available here — Configuration.uiMode is the equivalent
    // non-Composable check.
    val mapStyleMode = SettingsRepository(context).mapStyleMode.first()
    val systemDark = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    val isDark = resolveIsDarkColorTheme(mapStyleMode, systemDark)
    val idleBackground = if (isDark) Color(0xFF2C2C2C) else Color(0xFFF5F5F5)
    val idleText = if (isDark) Color.White else Color.Black

    val activeCars = loadActiveParkedCars(context) // already sorted soonest-first

    // Brief override: a recent Bluetooth connect/disconnect takes priority over the normal
    // "most urgent parked car" display for a short window, then falls back automatically —
    // no timer/alarm needed, since this is just re-checked fresh on every 5s poll.
    val transientWindowMillis = 20_000L
    val transientEvent = SettingsRepository(context).transientConnectionEventSnapshot()
    if (transientEvent != null && System.currentTimeMillis() - transientEvent.atMillis < transientWindowMillis) {
        val car = AppDatabase.getInstance(context).carDao().getAll().firstOrNull { it.id == transientEvent.carId }
        if (car != null) {
            val photoBitmap = car.photoPath?.let { path ->
                try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
            }
            return WidgetSummary(
                carName = car.name,
                countdownText = when (transientEvent.type) {
                    "DRIVING" -> "Driving"
                    "NEEDS_CONFIRMATION" -> "Tap to confirm parking"
                    else -> "Just parked" // "PARKED"
                },
                nextSweepText = null,
                photoBitmap = photoBitmap,
                frameColor = carComposeColor(car),
                fallbackBackgroundColor = carComposeColor(car),
                textColor = widgetTextColor(car),
                // The displayed car is only actually present in activeCars when a real
                // subscription was saved ("PARKED"/Subscribed) — for DRIVING it's not parked
                // at all, and for NEEDS_CONFIRMATION nothing was saved, so in both of those
                // cases activeCars.size on its own is already the "other cars" count.
                extraParkedCount = (activeCars.size - (if (transientEvent.type == "PARKED") 1 else 0)).coerceAtLeast(0),
                pendingConfirmCarId = if (transientEvent.type == "NEEDS_CONFIRMATION") car.id else null,
                pendingConfirmPoint = if (transientEvent.type == "NEEDS_CONFIRMATION") transientEvent.point else null
            )
        }
    }

    val mostUrgent = activeCars.firstOrNull()
        ?: return WidgetSummary(null, null, null, null, idleBackground, idleBackground, idleText, 0)

    val deadline = mostUrgent.soonestDeadline()
    val countdown = deadline?.let { formatCountdown(it.millis - System.currentTimeMillis()) }
    val nextText = deadline?.let {
        val base = formatSweepDateTime(Instant.ofEpochMilli(it.millis).atZone(SF_ZONE))
        if (it.kind == DeadlineKind.RPP) "RPP limit: $base" else base
    }

    val db = AppDatabase.getInstance(context)
    val segment = mostUrgent.parkedState?.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
    val frameColor = if (segment != null) {
        val settings = SettingsRepository(context)
        sweepStatusColor(
            sweepStatus(segment, thresholds = settings.sweepThresholdsSnapshot()),
            settings.sweepStatusColorsSnapshot()
        )
    } else {
        idleBackground
    }

    val photoBitmap = mostUrgent.car.photoPath?.let { path ->
        try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
    }

    return WidgetSummary(
        carName = mostUrgent.car.name,
        countdownText = countdown,
        nextSweepText = nextText,
        photoBitmap = photoBitmap,
        frameColor = frameColor,
        fallbackBackgroundColor = carComposeColor(mostUrgent.car),
        textColor = widgetTextColor(mostUrgent.car),
        // The widget only has room to show one car's status — this is how many OTHER cars
        // are also currently parked, so multi-car households don't get the false impression
        // that only one car is being tracked. Same underlying data as the map's parked-status
        // banner, which has the equivalent "only shows the default car" gap.
        extraParkedCount = (activeCars.size - 1).coerceAtLeast(0)
    )
}

@SuppressLint("RestrictedApi")
@Composable
private fun WidgetContent(summary: WidgetSummary) {
    val size = LocalSize.current
    // Rough heuristic (same spirit as the existing size.height >= 80.dp gate below) for a
    // genuinely narrow widget — a 1-cell-wide placement — where a car name plus a "+N" badge
    // wouldn't have room to lay out without wrapping or clipping. Below this, only the single
    // most urgent piece of information (the countdown) is shown.
    val isNarrow = size.width < 100.dp

    // During "Tap to confirm parking" (an ambiguous auto-detect with nothing saved yet), tap
    // straight into that confirm flow — same autoDetect* extras MainActivity already reads to
    // resolve pendingAutoDetect for the equivalent notification tap — rather than just opening
    // the app generically like every other widget state does.
    val context = LocalContext.current
    val clickAction = if (summary.pendingConfirmCarId != null && summary.pendingConfirmPoint != null) {
        actionStartActivity(
            Intent(context, MainActivity::class.java).apply {
                putExtra("autoDetectCarId", summary.pendingConfirmCarId)
                putExtra("autoDetectLat", summary.pendingConfirmPoint.lat)
                putExtra("autoDetectLng", summary.pendingConfirmPoint.lng)
            }
        )
    } else {
        actionStartActivity<MainActivity>()
    }

    // "Frame" effect: an outer colored Box with a small inset revealing a colored border
    // around an inner Box holding the photo (or fallback color) and text. Glance/RemoteViews
    // has no direct stroke/border modifier, so this padding-reveals-color trick is the
    // standard way to fake one.
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(summary.frameColor)
            .padding(4.dp)
            .clickable(clickAction)
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(if (summary.photoBitmap == null) summary.fallbackBackgroundColor else Color.Black)
        ) {
            summary.photoBitmap?.let { bitmap ->
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = summary.carName,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = GlanceModifier.fillMaxSize().padding(6.dp)) {
                if (summary.carName == null) {
                    Text(
                        "No car parked",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(summary.textColor))
                    )
                } else if (isNarrow) {
                    // Too narrow for the name/badge row below to lay out cleanly — the
                    // countdown alone (plus the frame color itself) is still the single most
                    // useful glance-value piece of information at this size.
                    Text(
                        summary.countdownText ?: "\u2014",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(summary.textColor))
                    )
                } else {
                    // maxLines = 1 on the name/badge and countdown rows \u2014 a long car name
                    // (or a wide "+N" badge) could otherwise wrap and push the countdown out
                    // of a short widget entirely instead of just truncating gracefully.
                    Text(
                        if (summary.extraParkedCount > 0) {
                            "${summary.carName}  +${summary.extraParkedCount}"
                        } else {
                            summary.carName
                        },
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(summary.textColor)),
                        maxLines = 1
                    )
                    Text(
                        summary.countdownText ?: "\u2014",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = ColorProvider(summary.textColor)),
                        maxLines = 1
                    )
                    if (size.height >= 80.dp && summary.nextSweepText != null) {
                        Text(
                            summary.nextSweepText,
                            style = TextStyle(color = ColorProvider(summary.textColor)),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Call after anything that changes parked state (save, unsubscribe, delete car) so the
 * widget reflects it immediately rather than waiting for the next periodic update. Native
 * Android widget periodic updates are OS-clamped to a 30-minute minimum regardless of what
 * updatePeriodMillis requests (see park_widget_info.xml) — this explicit call is what makes
 * the widget feel responsive to actual parking events rather than stale for up to half an hour.
 *
 * Logged at every step below (filter Logcat on "ParkWidget") because several rounds of
 * guessing at the root cause (missing call sites, coroutine cancellation, wrong widget
 * instance, a since-removed redraw-options nudge) each turned out to be incomplete on their
 * own — the logging is what actually let each one get ruled in or out from real device data
 * rather than more guessing:
 *   - "updateParkWidget: called" never appears -> the caller isn't reaching this function.
 *   - it appears but "updateAll() completed" doesn't -> updateAll() itself is throwing.
 *   - both appear, but "provideGlance: rendering" below never follows -> the recomposition
 *     request isn't reaching Glance's session for this widget at all.
 *
 * CONFIRMED, in order, across the debugging history that got this function to its current
 * shape: (1) provideGlance was rendering the WRONG car — a race between overlapping updateAll()
 * calls, fixed by widgetUpdateMutex below serializing every call through this function.
 * (2) provideGlance simply wasn't being re-invoked AT ALL after the widget's first cold-start
 * render, regardless of triggering source (a screen's coroutine, WorkManager, even
 * ProcessLifecycleOwner.onStop() calling this directly) — ruling out timing/lifecycle
 * entirely. (3) Comparing timestamps against the launcher's own "getActivityLaunchOptions"
 * log lines pinned this on the updateAppWidgetOptions() "redraw nudge" this function used to
 * end with (added earlier on an unconfirmed resize-redraw theory): Samsung's launcher
 * throttles/queues that specific call heavily (observed delays up to ~70 seconds, matching
 * the originally reported 20-60s lag), and while one nudge was still stuck pending, the
 * launcher wasn't picking up the actual content update either. Removed entirely. (4) Also
 * switched ParkWidgetReceiver and this function to share one ParkWidget.instance rather than
 * each creating its own separate ParkWidget() — a session-identity mismatch that compounded
 * the above. updateAll() alone, on the shared instance, with no nudge, is the current theory.
 */
private val widgetUpdateMutex = Mutex()

suspend fun updateParkWidget(context: Context) = widgetUpdateMutex.withLock {
    android.util.Log.d("ParkWidget", "updateParkWidget: called")
    try {
        ParkWidget.instance.updateAll(context)
        android.util.Log.d("ParkWidget", "updateParkWidget: updateAll() completed")
    } catch (e: Exception) {
        android.util.Log.e("ParkWidget", "updateParkWidget: updateAll() threw", e)
    }
}

/**
 * CONFIRMED root cause (via the "provideGlance: rendering" logging above): updateAll()
 * returning successfully only means Glance accepted a recomposition request — it does NOT
 * mean provideGlance actually ran. That work happens asynchronously on Glance's own internal
 * session, decoupled from whatever coroutine called updateAll(). Logcat showed updateAll()
 * "completing" twice in a row with zero provideGlance invocation until the app process was
 * later killed and restarted — the recomposition was silently dropped because the app left
 * the foreground before Glance's async session got a chance to run it, and nothing surfaced
 * an error since updateAll() itself had already returned successfully.
 *
 * Calling updateParkWidget() directly from a screen's own coroutine can't fix this — no
 * amount of NonCancellable or mutex wrapping changes that the ASYNC WORK GLANCE ITSELF
 * SCHEDULES is what's getting cut short, not our calling coroutine. WorkManager is the
 * standard Android mechanism for exactly this: it negotiates with the OS to guarantee a unit
 * of work actually completes, independent of whether the app that enqueued it is still in
 * the foreground (or even still alive) by the time it runs. This project already depends on
 * it for the periodic sweep-data refresh (see SweepingDataRefreshWorker/ParkApp.kt), so this
 * mirrors an established, working pattern rather than introducing a new one.
 *
 * Use this (not updateParkWidget directly) from anywhere UI-triggered — car edits, deletes,
 * parking-flow confirmations. ExistingWorkPolicy.REPLACE means a burst of rapid edits
 * collapses to just the latest one actually running, rather than queueing up redundant work.
 */
fun enqueueWidgetRefresh(context: Context) {
    // No .setExpedited() here (there was one) — on Android <12, WorkManager implements
    // expedited work via a temporary foreground service, which requires the worker to
    // override getForegroundInfo() with a real notification. WidgetRefreshWorker never did,
    // so any attempt to actually run this as expedited on pre-12 (confirmed: Galaxy S9,
    // API 29) hit CoroutineWorker's default getForegroundInfo() — which just throws
    // IllegalStateException("Not implemented") — crashing the app outright. This never
    // surfaced on Android 12+ test devices because expedited work there runs through
    // JobScheduler's own quota system and never needs a foreground service at all. The
    // actual goal here was reliability (guaranteed eventual execution via WorkManager, even
    // if the app leaves the foreground), not raw speed — a plain, non-expedited request
    // already provides that; expedited was only ever a minor speed optimization, not
    // something correctness depended on, so dropping it entirely is a straightforward, safe
    // fix on every Android version rather than one that needs a real foreground notification
    // built just to keep this working on API <31.
    val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        "widget_refresh",
        ExistingWorkPolicy.REPLACE,
        request
    )
}

class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            updateParkWidget(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("ParkWidget", "WidgetRefreshWorker: failed", e)
            Result.retry()
        }
    }
}