package com.example.park

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.first
import java.time.ZoneId

@Database(
    entities = [StreetSegment::class, Car::class, ParkedState::class, SavedLocation::class, ScheduleOverride::class, RppZoneRegulation::class, MeteredZone::class, StreetClosure::class, TowZone::class, StreetUsePermit::class],
    version = 23,
    exportSchema = true
)
@TypeConverters(LatLngListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streetSegmentDao(): StreetSegmentDao
    abstract fun carDao(): CarDao
    abstract fun parkedStateDao(): ParkedStateDao
    abstract fun savedLocationDao(): SavedLocationDao
    abstract fun scheduleOverrideDao(): ScheduleOverrideDao
    abstract fun rppZoneRegulationDao(): RppZoneRegulationDao
    abstract fun meteredZoneDao(): MeteredZoneDao
    abstract fun streetClosureDao(): StreetClosureDao
    abstract fun towZoneDao(): TowZoneDao
    abstract fun streetUsePermitDao(): StreetUsePermitDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "park_database"
                )
                    // Versions 1-4 predate any exported schema or Migration objects — every
                    // bump before this one destructively recreated the database, so there's
                    // no historical schema to migrate FROM for them even if we wanted to.
                    // Version 5 is what's actually installed on-device right now, and is the
                    // new baseline: destructive migration is still permitted as a fallback
                    // FROM versions 1-4 (nobody should still be on those, and there's no
                    // correct way to migrate them regardless), but NOT from 5 onward. Any
                    // future version bump without a matching entry in ALL_MIGRATIONS (see
                    // DatabaseMigrations.kt) now throws at startup instead of silently
                    // wiping car profiles and parked state.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
        }

        /**
         * Tests only: makes [getInstance] return [db] (typically an in-memory database), or forget the
         * current instance when [db] is null, so each Robolectric test starts from an empty database.
         * Never called by the app, so runtime behavior is unchanged.
         */
        @androidx.annotation.VisibleForTesting
        internal fun replaceInstanceForTests(db: AppDatabase?) {
            synchronized(this) {
                INSTANCE?.takeIf { it !== db }?.close()
                INSTANCE = db
            }
        }
    }
}

suspend fun saveParkedState(
    context: Context,
    carId: Long,
    segment: StreetSegment,
    point: LatLng,
    exactPinLat: Double? = null,
    exactPinLng: Double? = null,
    sideConfirmed: Boolean = true
) {
    val db = AppDatabase.getInstance(context)
    val parkedAtMillis = System.currentTimeMillis()
    val parkedAt = java.time.Instant.ofEpochMilli(parkedAtMillis).atZone(SF_ZONE).toLocalDateTime()
    // A meter timer belongs to the specific spot it was set at, not the car in general — stale
    // the instant the car re-parks anywhere, same as the Bluetooth "Did X just park?" prompt
    // cancelled further down. Cancelled unconditionally (not just when nextMillis == null like
    // cancelSweepReminder below) since this has nothing to do with sweep data; a caller that's
    // about to set a NEW meter timer for this fresh row (see finishWithMeterTimer in
    // MapScreen.kt) does so afterward, so this can't clobber it.
    cancelMeterTimer(context, carId)
    // The previous spot's closure and tow alarms and notices. The new spot's are armed by the park-time
    // check started at the end of this function, once it has refreshed their data.
    clearOldSpotForSourcesArmedLater(context, carId)
    // The whole CURB's schedule, not just the matched row's: a curb is often described by several rows (one per
    // sweep weekday / week pattern), and parking on one must cover them all — see CurbSchedule.
    val curbRows = loadCurbRows(context, segment)
    val next = CurbSchedule.nextSweepDateTime(curbRows)
    val nextMillis = next?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()

    // RPP is matched against the CONFIRMED segment's curb-side location, not the raw [point] —
    // point is whatever GPS fix (or map tap) started this parking flow, which the manual-pick
    // and select-from-map escape hatches exist specifically to override when it's wrong. Using
    // it anyway for RPP matching meant picking a corrected segment never actually corrected
    // what RPP lookup searched near, silently reproducing the exact inaccuracy the manual path
    // was meant to fix. The segment's own offset-polyline midpoint is what every other
    // curb-side-aware computation in this app already treats as "the real location" of a
    // confirmed segment (see MapUtils.kt's overlays/matching), so RPP matching now agrees with
    // that instead of trusting a point the user may have just explicitly rejected.
    val rppMatchOrigin = if (segment.points.size >= 2) {
        midpointAlongPath(offsetPolylineForSide(segment.points, segment.cnnRightLeft))
    } else point
    val rppRegulation = findConfidentRppMatch(context, rppMatchOrigin)
    android.util.Log.d(
        "RppSync",
        "saveParkedState: RPP match = " + (rppRegulation?.let { "zone=${it.zoneLetters} objectId=${it.objectId} days=${it.days} hrsBegin=${it.hrsBegin} hrsEnd=${it.hrsEnd} hrLimit=${it.hrLimit}" } ?: "none within 30m of $rppMatchOrigin (raw point was $point)")
    )

    val parked = ParkedState(
        carId = carId,
        segmentBlockSweepId = segment.blockSweepId,
        sideConfirmed = sideConfirmed,
        parkedLat = point.lat,
        parkedLng = point.lng,
        exactPinLat = exactPinLat,
        exactPinLng = exactPinLng,
        parkedAtMillis = parkedAtMillis,
        nextSweepAtMillis = nextMillis,
        notificationScheduled = false, // updated below once scheduling has actually run
        rppRegulationId = rppRegulation?.objectId
    )
    db.parkedStateDao().upsert(parked)

    // Sweep and RPP for the new row, through their curb sources (CurbSources.kt): the same code every
    // re-arm runs, each independent of the other (a block can be swept AND RPP-zoned, or one, or neither).
    val car = db.carDao().getAll().firstOrNull { it.id == carId }
    val scheduled = armNewParkedRowOrClear(context, parked, car)

    // Parked while a sweep is ALREADY under way: the scheduling above only knows about the NEXT
    // occurrence (next week's), so without this the user would get no warning at all that they've
    // just parked in the middle of one. Posted on top of, not instead of, the normal scheduling.
    // Any notice left from a previous spot is cleared first (posting again would replace it anyway,
    // but if this spot isn't being swept the old one has to go). This function is also the path the
    // Bluetooth auto-park takes, so the notice covers that too.
    NotificationHelper.cancel(context, reminderNotificationId(carId, ReminderKind.SWEEP_ACTIVE))
    // A stale Bluetooth "Did X just park?" prompt from an earlier spot is replaced by this new park.
    // (The Bluetooth flows post their own notice after saveParkedState returns, so this can't erase it.)
    NotificationHelper.cancel(context, NotificationIds.forCar(carId, NotificationIds.Purpose.BLUETOOTH_AUTO_DETECT))
    val sweepEnd = CurbSchedule.sweepInProgressEnd(curbRows, parkedAt)
    if (sweepEnd != null) {
        val endMillis = sweepEnd.atZone(SF_ZONE).toInstant().toEpochMilli()
        val carName = car?.name ?: "Your car"
        val (title, text) = buildReminderContent(carName, segment.corridor, endMillis, ReminderKind.SWEEP_ACTIVE)
        NotificationHelper.showReminder(
            context = context,
            notificationId = reminderNotificationId(carId, ReminderKind.SWEEP_ACTIVE),
            kind = ReminderKind.SWEEP_ACTIVE,
            title = title,
            text = text,
            carId = carId,
            carName = carName,
            corridor = segment.corridor,
            nextSweepAtMillis = endMillis
        )
    }

    // Records what actually happened (an alarm was set — exact or the inexact fallback — or a
    // reminder fired right away), not merely that a deadline existed. Guarded by parkedAtMillis
    // like the delivery markers, so it can't land on a newer row from a racing re-park.
    db.parkedStateDao().setNotificationScheduled(carId, parkedAtMillis, scheduled)

    enqueueWidgetRefresh(context)
    // Runs in the background AFTER the save: never delays it (spec §3, park-time fetch).
    ClosureCheckCenter.start(context, carId, parkedAtMillis)
}

/**
 * Arms the save-time sources (sweep, RPP) for a row just written; see armNewParkedRow. [car] is null only
 * when the car was deleted while the park was being saved: then there's nothing to remind about, and the
 * previous spot's sweep and RPP alarms are cleared instead of being left behind.
 */
private suspend fun armNewParkedRowOrClear(context: Context, parked: ParkedState, car: Car?): Boolean {
    if (car != null) return armNewParkedRow(context, parked, car)
    cancelSweepReminder(context, parked.carId)
    cancelRppReminder(context, parked.carId)
    return false
}

/**
 * For a spot with no nearby street-cleaning data at all (a garage, driveway, private lot — see
 * ParkingFlowState.NoStreetNearby) — deliberately NOT an extension of saveParkedState, which
 * requires a confirmed StreetSegment and uses it unconditionally (curb-schedule lookup, the
 * RPP-match origin, notification text). Most of that is irrelevant here; this is the much
 * smaller subset that still applies.
 *
 * Still runs an independent RPP check even though there's no sweep segment: "not a street
 * cleaning risk" says nothing about whether this spot also sits inside an RPP zone's non-permit
 * time limit — a garage's curb apron can still be on a permit street. Skipping that check would
 * silently drop a real reminder for the sake of a spot that genuinely has no sweep risk, which
 * runs against this app's core value of preferring conservative behavior when unsure (see
 * CLAUDE.md). Matched against the raw [point] directly — there's no confirmed segment's
 * curb-side midpoint to prefer here, unlike saveParkedState's rppMatchOrigin.
 */
suspend fun saveUnmanagedParkedState(
    context: Context,
    carId: Long,
    point: LatLng,
    exactPinLat: Double? = null,
    exactPinLng: Double? = null,
    // Non-null only when this save is happening BECAUSE [point] matched a safe-tagged
    // SavedLocation (see findSafeSavedLocation) — recorded so SavedLocationRecompute.kt can
    // later find exactly this row if that location's safe flag changes or it's deleted. Left
    // null for the manual "not a street cleaning risk spot" (NoStreetNearby) flow, which has
    // nothing to do with any SavedLocation.
    viaSafeLocationId: Long? = null
) {
    val db = AppDatabase.getInstance(context)
    val parkedAtMillis = System.currentTimeMillis()
    val parkedAt = java.time.Instant.ofEpochMilli(parkedAtMillis).atZone(SF_ZONE).toLocalDateTime()
    // See saveParkedState's identical call for why this is unconditional and safe to run
    // before a caller sets a fresh meter timer for this same row.
    cancelMeterTimer(context, carId)
    clearOldSpotForSourcesArmedLater(context, carId) // as in saveParkedState

    val rppRegulation = findConfidentRppMatch(context, point)
    android.util.Log.d(
        "RppSync",
        "saveUnmanagedParkedState: RPP match = " + (rppRegulation?.let { "zone=${it.zoneLetters} objectId=${it.objectId}" } ?: "none within 30m of $point")
    )

    val parked = ParkedState(
        carId = carId,
        segmentBlockSweepId = null,
        sideConfirmed = true,
        parkedLat = point.lat,
        parkedLng = point.lng,
        exactPinLat = exactPinLat,
        exactPinLng = exactPinLng,
        parkedAtMillis = parkedAtMillis,
        nextSweepAtMillis = null,
        notificationScheduled = false, // updated below once RPP scheduling (if any) has run
        // An off-street saved location (garage, lot) gets no RPP reminder, but the match is still STORED, so
        // switching the location's off-street flag off later brings the reminder back (see isParkedOffStreet,
        // which the RPP source checks).
        rppRegulationId = rppRegulation?.objectId,
        parkedViaSafeLocationId = viaSafeLocationId
    )
    db.parkedStateDao().upsert(parked)

    // Through the same curb sources as saveParkedState: with no segment the sweep source has no deadline, so
    // it just clears a previous real park's sweep alarms; RPP gets its own independent check.
    val car = db.carDao().getAll().firstOrNull { it.id == carId }
    val scheduled = armNewParkedRowOrClear(context, parked, car)

    db.parkedStateDao().setNotificationScheduled(carId, parkedAtMillis, scheduled)
    enqueueWidgetRefresh(context)
    // Closures still matter here: a closed street can block a garage exit (spec §4).
    ClosureCheckCenter.start(context, carId, parkedAtMillis)
}