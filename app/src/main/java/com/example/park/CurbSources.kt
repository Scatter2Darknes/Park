package com.example.park

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first

/*
 * Every curb rule Park knows about, behind one shape (docs/park-sources-refactor-spec.md, Part B).
 *
 * A "source" is one kind of curb restriction: street sweeping, the RPP non-permit time limit, the
 * user's meter timer, street closures, temporary tow zones. Each one is an `object` (a Kotlin singleton:
 * exactly one instance, created on first use) implementing CurbRestrictionSource, and CurbSources.all
 * lists them in a fixed order. The code that arms reminders (armParkedState), builds the banner status
 * (loadCarsWithStatus), ranks deadlines (soonestDeadline), cancels on unpark (cancelParkingReminder)
 * and runs the park-time step (runParkTimeClosureCheck) loops over that list instead of naming each
 * source, so adding one means writing one object and registering it.
 *
 * The sources call the same functions as before (scheduleTiers, armClosureAlert, armTowReminders, ...):
 * this is a restructure, not a rewrite. Alarm request codes, notification ids, ReminderKind and
 * RollForwardKind names and the delivery-marker columns are all unchanged, because v1.04 alarms already
 * armed on phones carry them. ArmingCharacterizationTest pins the observable behavior.
 */

/** One per source. The registry order below, not this enum's, is what the loops follow. */
enum class SourceId { SWEEP, RPP, METER, CLOSURE, TOW, PERMIT }

/** The settings the sources read, loaded ONCE per pass (one arm pass, or one status pass) and handed to each. */
class SourceSettings(
    val reminderOffsetMillis: Long,
    /** Null when the urgent tier is switched off. */
    val urgentOffsetMillis: Long?,
    val closuresEnabled: Boolean,
    /** How far ahead closure alerts and the tow advance alert go out (Settings: closureAlertLeadHours). */
    val closureLeadMillis: Long,
    val towEnabled: Boolean,
    val closuresLastSyncMillis: Long?,
    val permitsEnabled: Boolean
)

suspend fun loadSourceSettings(context: Context): SourceSettings {
    val settingsRepo = SettingsRepository(context)
    val offsetMinutes = settingsRepo.notificationOffsetMinutes.first()
    val urgentEnabled = settingsRepo.urgentReminderEnabled.first()
    val urgentOffsetMinutes = settingsRepo.urgentOffsetMinutes.first()
    return SourceSettings(
        reminderOffsetMillis = offsetMinutes * 60_000L,
        urgentOffsetMillis = if (urgentEnabled) urgentOffsetMinutes * 60_000L else null,
        closuresEnabled = settingsRepo.closuresEnabled(),
        closureLeadMillis = closureLeadMillis(context),
        towEnabled = settingsRepo.towEnabled(),
        closuresLastSyncMillis = settingsRepo.closuresLastSyncMillis.first(),
        permitsEnabled = settingsRepo.permitsEnabled()
    )
}

/**
 * What arming one source did. [parked] is the row as the NEXT source should see it: the sweep source
 * stores a recomputed deadline on it. [deadlineChanged] tells armAllParkedStates to redraw the widget.
 * [scheduled]: a reminder is taken care of (an alarm set, or fired now), which the save path records in
 * ParkedState.notificationScheduled.
 */
class ArmOutcome(val parked: ParkedState, val deadlineChanged: Boolean = false, val scheduled: Boolean = false)

/**
 * What one source contributes to a car's status line, from stored data. A `sealed interface` is a
 * closed set of types: the compiler knows every possible kind, so a `when` over them can't miss one.
 * Each source has its own shape rather than one flat type, because they don't share one.
 */
sealed interface SourceResult {
    /** Nothing beyond what the parked row already holds (sweep, meter). */
    object None : SourceResult
    data class Rpp(val warning: RppWarning?) : SourceResult
    /** Null status: closures switched off. Unchecked is never "clear". */
    data class Closure(val status: ClosureStatus?) : SourceResult
    /** Null status: tow checks switched off. Unchecked / Stale are never "clear". */
    data class Tow(val deadlineMillis: Long?, val status: TowStatus?) : SourceResult
    /** Null status: permit warnings switched off. Unchecked is never "clear". */
    data class Permit(val status: PermitStatus?) : SourceResult
}

interface CurbRestrictionSource {
    val id: SourceId

    /** What this source owns, so nothing is owned twice (see CurbSourcesTest). */
    val reminderKinds: Set<ReminderKind>
    val rollForwardKinds: Set<RollForwardKind>
    val deadlineKinds: Set<DeadlineKind>
    val notificationPurposes: Set<NotificationIds.Purpose>

    /** Whether the user has this source switched on. A switched-off source still cancels what it owns. */
    fun isEnabled(settings: SourceSettings): Boolean

    /**
     * Armed the moment a park is saved (saveParkedState), from the data already on the phone. False for the
     * sources whose data the park-time step refreshes first (closures, tow): arming them at save time from
     * older data could post an alert the refresh then shows was wrong. Those are only cancelled at save time
     * (the old spot's), and armed by the park-time step's re-arm.
     */
    val armsAtSave: Boolean

    /**
     * Arms, fires or cancels this source's reminders for one parked car. Called by armParkedState for
     * every source in registry order, under its mutex. [clearStaleNotifications] is the schedule-fresh
     * path (true) versus the re-arm path (false), see rescheduleAllActiveReminders / rearmAllActiveReminders.
     */
    suspend fun arm(
        context: Context,
        db: AppDatabase,
        parked: ParkedState,
        car: Car,
        settings: SourceSettings,
        clearStaleNotifications: Boolean
    ): ArmOutcome

    /** This source's status for one parked car, from stored data only (no network). */
    suspend fun resolve(context: Context, parked: ParkedState, car: Car, settings: SourceSettings): SourceResult =
        SourceResult.None

    /** What to show when [resolve] threw: "can't tell", never "clear". */
    val unresolved: SourceResult get() = SourceResult.None

    /** This source's deadline for soonestDeadline(), read from an already-built status. Null: none (closures never). */
    fun deadline(status: CarWithStatus): CarDeadline? = null

    /** Cancels every alarm AND notification this source owns for the car: the "not parked here any more" path. */
    fun cancelAll(context: Context, carId: Long)

    /** Park-time step, phase 1: refresh this source's data. All sources run in parallel, each within the fetch budget. */
    suspend fun refreshForPark(context: Context) {}

    /** Park-time step, phase 2: this source's once-per-park notice, AFTER the car has been re-armed once. */
    suspend fun parkTimeNotice(context: Context, carId: Long, parkedAtMillis: Long) {}
}

object CurbSources {
    /**
     * Fixed order. Arming, status and notices follow it, and soonestDeadline breaks a tie in favor of the
     * earlier one (sweep, RPP, meter, tow), exactly as the hand-written list it replaces did.
     */
    val all: List<CurbRestrictionSource> = listOf(SweepSource, RppSource, MeterSource, ClosureSource, TowSource, PermitSource)
}

/**
 * Runs [block] for one source (or one car) so that its failure can't stop the others: a corrupt row or a
 * bug in one source must never cost the user every other reminder. Logs and returns null on failure.
 * A CancellationException is rethrown, never swallowed: it means the coroutine was cancelled on purpose
 * (e.g. a newer park replaced this park-time check), and swallowing it would keep running work nobody wants.
 */
internal inline fun <T> isolated(what: String, block: () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Park", "$what failed; the other curb sources are unaffected", e)
        null
    }

// --- the sources ------------------------------------------------------------------------------------

/**
 * Street sweeping. The one source that CHANGES the parked row: it recomputes the next sweep from the
 * curb as it is now (so a "Fix schedule" override, refreshed street data, or a sweep that has passed are
 * all picked up) and stores it when it moved.
 */
object SweepSource : CurbRestrictionSource {
    override val id = SourceId.SWEEP
    override val reminderKinds = setOf(ReminderKind.NORMAL, ReminderKind.URGENT, ReminderKind.SWEEP_ACTIVE)
    override val rollForwardKinds = setOf(RollForwardKind.SWEEP)
    override val deadlineKinds = setOf(DeadlineKind.SWEEP)
    override val notificationPurposes = setOf(
        NotificationIds.Purpose.REMINDER_NORMAL, NotificationIds.Purpose.REMINDER_URGENT,
        NotificationIds.Purpose.ROLL_FORWARD_SWEEP, NotificationIds.Purpose.SWEEP_ACTIVE
    )

    override fun isEnabled(settings: SourceSettings) = true
    override val armsAtSave = true

    /**
     * A notification for a deadline that was MOVED before it happened (an override or data change while it
     * was still ahead) is stale, so those are cleared even on the re-arm path. A deadline that merely PASSED
     * and rolled on is not stale: its notification is what the user is looking at.
     */
    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ): ArmOutcome {
        val now = System.currentTimeMillis()
        var row = parked
        var deadlineChanged = false
        var scheduleMoved = false

        val segment = row.segmentBlockSweepId?.let { db.streetSegmentDao().getById(it) }
        // A segment that's gone (never synced, or retired upstream) keeps its stored deadline: a
        // reminder that might be stale beats silently dropping one that might be real.
        if (segment != null) {
            // Across every row of the curb (see CurbSchedule), so a sweep day carried by a sibling row is never lost.
            val recomputed = CurbSchedule.nextSweepDateTime(loadCurbRows(context, segment))
                ?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()
            val old = row.nextSweepAtMillis
            if (recomputed != old) {
                Log.d("Park", "Recomputed sweep deadline for car ${row.carId}: $old -> $recomputed")
                db.parkedStateDao().updateNextSweep(row.carId, row.parkedAtMillis, recomputed)
                row = row.copy(nextSweepAtMillis = recomputed)
                deadlineChanged = true
                scheduleMoved = old != null && old > now
            }
        }
        val clearStale = clearStaleNotifications || scheduleMoved

        val nextMillis = row.nextSweepAtMillis
        var scheduled = false
        if (nextMillis != null) {
            scheduled = scheduleTiers(
                context, row.carId, car.name, segment?.corridor ?: "your parked street",
                nextMillis, row.parkedAtMillis,
                ReminderKind.NORMAL, ReminderKind.URGENT, settings.reminderOffsetMillis, settings.urgentOffsetMillis,
                row.normalDeliveredForMillis, row.urgentDeliveredForMillis, clearStale,
                RollForwardKind.SWEEP, nextMillis
            )
        } else {
            // No upcoming occurrence: nothing to remind about, and any alarm left over is for a deadline that no longer exists.
            cancelAlarm(context, row.carId, ReminderKind.NORMAL)
            cancelAlarm(context, row.carId, ReminderKind.URGENT)
            cancelRollForward(context, row.carId, RollForwardKind.SWEEP)
            if (clearStale) {
                NotificationHelper.cancel(context, reminderNotificationId(row.carId, ReminderKind.NORMAL))
                NotificationHelper.cancel(context, reminderNotificationId(row.carId, ReminderKind.URGENT))
            }
        }
        return ArmOutcome(row, deadlineChanged, scheduled)
    }

    override fun deadline(status: CarWithStatus) =
        status.parkedState?.nextSweepAtMillis?.let { CarDeadline(it, DeadlineKind.SWEEP) }

    override fun cancelAll(context: Context, carId: Long) {
        cancelSweepReminder(context, carId)
        NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.SWEEP_ACTIVE))
    }
    // No parkTimeNotice: the "sweeping in progress" notice is posted by saveParkedState at save time, on every
    // park, whatever the closure settings. The park-time step is skipped when closures are off, so it can't live there.
}

/** The RPP non-permit time limit. Independent of sweeping: a block can be both swept and RPP-zoned. */
object RppSource : CurbRestrictionSource {
    override val id = SourceId.RPP
    override val reminderKinds = setOf(ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT)
    override val rollForwardKinds = setOf(RollForwardKind.RPP)
    override val deadlineKinds = setOf(DeadlineKind.RPP)
    override val notificationPurposes = setOf(
        NotificationIds.Purpose.RPP_NORMAL, NotificationIds.Purpose.RPP_URGENT, NotificationIds.Purpose.ROLL_FORWARD_RPP
    )

    override fun isEnabled(settings: SourceSettings) = true
    override val armsAtSave = true

    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ): ArmOutcome {
        val rpp = resolveRpp(context, parked, car)
        var scheduled = false
        if (rpp != null) {
            scheduled = scheduleTiers(
                context, parked.carId, car.name,
                rppZoneLabel(rpp.warning),
                rpp.moveByMillis, parked.parkedAtMillis,
                ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, settings.reminderOffsetMillis, settings.urgentOffsetMillis,
                parked.rppNormalDeliveredForMillis, parked.rppUrgentDeliveredForMillis, clearStaleNotifications,
                RollForwardKind.RPP, rpp.rollForwardAtMillis
            )
        } else if (clearStaleNotifications) {
            cancelRppReminder(context, parked.carId) // no regulation matched, car holds a permit, or nothing found
        } else {
            // Re-arm never touches notifications — only the (now pointless) alarms.
            cancelAlarm(context, parked.carId, ReminderKind.RPP_NORMAL)
            cancelAlarm(context, parked.carId, ReminderKind.RPP_URGENT)
            cancelRollForward(context, parked.carId, RollForwardKind.RPP)
        }
        return ArmOutcome(parked, scheduled = scheduled)
    }

    override suspend fun resolve(context: Context, parked: ParkedState, car: Car, settings: SourceSettings): SourceResult =
        SourceResult.Rpp(resolveRppDeadline(context, parked, car))

    // RPP has no "can't tell" state of its own: a failed resolve shows no RPP line (the reminders are armed separately).
    override val unresolved: SourceResult get() = SourceResult.Rpp(null)

    override fun deadline(status: CarWithStatus) = status.rppDeadline?.moveByDateTime
        ?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()
        ?.let { CarDeadline(it, DeadlineKind.RPP) }

    override fun cancelAll(context: Context, carId: Long) = cancelRppReminder(context, carId)
}

/**
 * The user's own meter timer (MeterTimer.kt). Only a deadline for ranking: its alarm and notification are
 * set by the user and have their own path, which the unpark cleanup cancels directly (cancelParkingReminder),
 * so this source owns no alarm or notification id.
 */
object MeterSource : CurbRestrictionSource {
    override val id = SourceId.METER
    override val reminderKinds = emptySet<ReminderKind>()
    override val rollForwardKinds = emptySet<RollForwardKind>()
    override val deadlineKinds = setOf(DeadlineKind.METER)
    override val notificationPurposes = emptySet<NotificationIds.Purpose>()

    override fun isEnabled(settings: SourceSettings) = true
    override val armsAtSave = false // nothing to arm: the user sets the timer after parking

    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ) = ArmOutcome(parked)

    override fun deadline(status: CarWithStatus) =
        status.parkedState?.meterTimerAtMillis?.let { CarDeadline(it, DeadlineKind.METER) }

    override fun cancelAll(context: Context, carId: Long) {}
}

/** Street closures (ClosureAlerts.kt). A banner line and its own alert slot, never a deadline. */
object ClosureSource : CurbRestrictionSource {
    override val id = SourceId.CLOSURE
    override val reminderKinds = emptySet<ReminderKind>()
    override val rollForwardKinds = emptySet<RollForwardKind>()
    override val deadlineKinds = emptySet<DeadlineKind>()
    override val notificationPurposes = setOf(NotificationIds.Purpose.CLOSURE_ALERT, NotificationIds.Purpose.CLOSURE_NEARBY)

    override fun isEnabled(settings: SourceSettings) = settings.closuresEnabled
    override val armsAtSave = false // the park-time step refreshes closure data, then arms

    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ): ArmOutcome {
        armClosureAlert(context, parked, car.name, isEnabled(settings), settings.closureLeadMillis)
        return ArmOutcome(parked)
    }

    override val unresolved: SourceResult get() = SourceResult.Closure(ClosureStatus.Unchecked)

    override suspend fun resolve(context: Context, parked: ParkedState, car: Car, settings: SourceSettings): SourceResult {
        if (!isEnabled(settings)) return SourceResult.Closure(null) // both closure features off = no closure line at all
        val status = try {
            resolveClosureStatus(context, parked, settings.closuresLastSyncMillis, settings.closureLeadMillis)
        } catch (e: Exception) {
            Log.w("ClosureAlert", "Resolving closure status for car ${car.id} failed", e)
            ClosureStatus.Unchecked // can't tell, so never "clear"
        }
        return SourceResult.Closure(status)
    }

    override fun cancelAll(context: Context, carId: Long) = cancelClosureAlert(context, carId)

    override suspend fun refreshForPark(context: Context) =
        refreshIfOlderThan(SettingsRepository(context).closuresLastSyncMillis.first(), "closure") {
            StreetClosureRepository(context).refreshFromNetwork()
        }

    override suspend fun parkTimeNotice(context: Context, carId: Long, parkedAtMillis: Long) =
        notifyNearbyClosureOnPark(context, carId, parkedAtMillis)
}

/** Temporary tow zones (TowAlerts.kt): a deadline family like sweep and RPP, plus the advance alert and park-time notices. */
object TowSource : CurbRestrictionSource {
    override val id = SourceId.TOW
    override val reminderKinds = setOf(ReminderKind.TOW_NORMAL, ReminderKind.TOW_URGENT, ReminderKind.TOW_ADVANCE, ReminderKind.TOW_ACTIVE)
    override val rollForwardKinds = setOf(RollForwardKind.TOW)
    override val deadlineKinds = setOf(DeadlineKind.TOW)
    override val notificationPurposes = setOf(
        NotificationIds.Purpose.TOW_NORMAL, NotificationIds.Purpose.TOW_URGENT, NotificationIds.Purpose.TOW_ADVANCE,
        NotificationIds.Purpose.TOW_ACTIVE, NotificationIds.Purpose.TOW_NEARBY, NotificationIds.Purpose.ROLL_FORWARD_TOW
    )

    /** Its own switch, plus one of the closure switches that fetch the data (SettingsRepository.towEnabled). */
    override fun isEnabled(settings: SourceSettings) = settings.towEnabled
    override val armsAtSave = false // the park-time step refreshes tow data, then arms

    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ): ArmOutcome {
        armTowReminders(context, parked, car, settings, clearStaleNotifications)
        return ArmOutcome(parked)
    }

    override val unresolved: SourceResult get() = SourceResult.Tow(null, TowStatus.Unchecked)

    override suspend fun resolve(context: Context, parked: ParkedState, car: Car, settings: SourceSettings): SourceResult {
        if (!isEnabled(settings)) return SourceResult.Tow(null, null)
        var deadline: Long? = null
        val status = try {
            deadline = resolveTowDeadlineMillis(context, parked)
            resolveTowStatus(context, parked, settings.closureLeadMillis)
        } catch (e: Exception) {
            Log.w("TowAlert", "Resolving tow status for car ${car.id} failed", e)
            TowStatus.Unchecked // can't tell, so never "clear"
        }
        return SourceResult.Tow(deadline, status)
    }

    override fun deadline(status: CarWithStatus) = status.towDeadlineMillis?.let { CarDeadline(it, DeadlineKind.TOW) }

    override fun cancelAll(context: Context, carId: Long) = cancelTowReminder(context, carId)

    // The park-time step runs whenever a closure switch is on; tow can be off within that, so both check.
    override suspend fun refreshForPark(context: Context) {
        val settings = SettingsRepository(context)
        if (!settings.towEnabled()) return
        refreshIfOlderThan(settings.towLastSyncMillis.first(), "tow-zone") {
            TowZoneRepository(context).refreshFromNetwork()
        }
    }

    override suspend fun parkTimeNotice(context: Context, carId: Long, parkedAtMillis: Long) {
        if (!SettingsRepository(context).towEnabled()) return
        notifyTowOnPark(context, carId, parkedAtMillis)
    }
}

/**
 * Public Works temporary no-parking permits (PermitAlerts.kt): a "check the signs" source. A heads-up at the
 * lead time, a park-time notice when one is in effect, a banner line. Never a deadline (no reliable hours).
 * Added as the sixth source: nothing outside this object and its own files had to change to arm it, show it
 * or cancel it, only the registry list, the banner and Settings.
 */
object PermitSource : CurbRestrictionSource {
    override val id = SourceId.PERMIT
    override val reminderKinds = setOf(ReminderKind.PERMIT_ADVANCE)
    override val rollForwardKinds = emptySet<RollForwardKind>()
    override val deadlineKinds = emptySet<DeadlineKind>()
    override val notificationPurposes = setOf(NotificationIds.Purpose.PERMIT_ADVANCE, NotificationIds.Purpose.PERMIT_NOTICE)

    /** Its own switch, plus one of the closure switches that fetch the data (SettingsRepository.permitsEnabled). */
    override fun isEnabled(settings: SourceSettings) = settings.permitsEnabled
    override val armsAtSave = false // the park-time step refreshes permit data, then arms

    override suspend fun arm(
        context: Context, db: AppDatabase, parked: ParkedState, car: Car, settings: SourceSettings, clearStaleNotifications: Boolean
    ): ArmOutcome {
        armPermitAlert(context, parked, car.name, isEnabled(settings), settings.closureLeadMillis)
        return ArmOutcome(parked)
    }

    override suspend fun resolve(context: Context, parked: ParkedState, car: Car, settings: SourceSettings): SourceResult =
        SourceResult.Permit(if (isEnabled(settings)) resolvePermitStatus(context, parked, settings.closureLeadMillis) else null)

    override val unresolved: SourceResult get() = SourceResult.Permit(PermitStatus.Unchecked)

    override fun cancelAll(context: Context, carId: Long) = cancelPermitAlerts(context, carId)

    override suspend fun refreshForPark(context: Context) {
        val settings = SettingsRepository(context)
        if (!settings.permitsEnabled()) return
        refreshIfOlderThan(settings.permitsLastSyncMillis.first(), "permit") {
            StreetUsePermitRepository(context).refreshFromNetwork()
        }
    }

    override suspend fun parkTimeNotice(context: Context, carId: Long, parkedAtMillis: Long) {
        if (!SettingsRepository(context).permitsEnabled()) return
        notifyPermitOnPark(context, carId, parkedAtMillis)
    }
}
