package com.example.park

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.first
import java.time.ZoneId

@Database(
    entities = [StreetSegment::class, Car::class, ParkedState::class, SavedLocation::class, ScheduleOverride::class, RppZoneRegulation::class],
    version = 13,
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
    val next = NextSweepCalculator.nextSweepDateTime(segment)
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

    db.parkedStateDao().upsert(
        ParkedState(
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
    )

    val car = db.carDao().getAll().firstOrNull { it.id == carId }
    val settingsRepo = SettingsRepository(context)
    val offsetMinutes = settingsRepo.notificationOffsetMinutes.first()
    val urgentEnabled = settingsRepo.urgentReminderEnabled.first()
    val urgentOffsetMinutes = settingsRepo.urgentOffsetMinutes.first()
    val reminderOffsetMillis = offsetMinutes * 60_000L
    val urgentOffsetMillis = if (urgentEnabled) urgentOffsetMinutes * 60_000L else null

    val sweepHandled = if (nextMillis != null) {
        scheduleParkingReminders(
            context = context,
            carId = carId,
            carName = car?.name ?: "Your car",
            corridor = segment.corridor,
            nextSweepAtMillis = nextMillis,
            parkedAtMillis = parkedAtMillis, // markers default to null: this is a brand-new row, nothing delivered yet
            reminderOffsetMillis = reminderOffsetMillis,
            urgentOffsetMillis = urgentOffsetMillis
        )
    } else {
        cancelSweepReminder(context, carId) // re-parked somewhere with no upcoming sweep: drop the previous spot's alarms
        false
    }

    // Independent of the sweep reminder above — a block can be both swept AND RPP-zoned, or
    // only one, or neither. car is looked up fresh (not passed a default) since
    // nextRppDeadline needs its permitZoneLetters to know whether a warning even applies.
    val rppDeadline = if (rppRegulation != null && car != null) {
        nextRppDeadline(rppRegulation, car, parkedSince = parkedAt, from = parkedAt)
    } else null
    android.util.Log.d(
        "RppSync",
        "saveParkedState: RPP deadline = " + (rppDeadline?.moveByDateTime?.toString()
            ?: if (rppRegulation == null) "n/a (no match)" else "n/a (car holds a permit for this zone, its DAYS didn't parse, hrLimit=${rppRegulation.hrLimit} leaves no usable limit, or no violation is possible in its window)")
    )
    val rppHandled = if (rppDeadline != null) {
        scheduleRppReminders(
            context = context,
            carId = carId,
            carName = car?.name ?: "Your car",
            zoneLabel = "RPP Zone ${rppDeadline.zoneLetters.sorted().joinToString("/")}",
            moveByAtMillis = rppDeadline.moveByDateTime.atZone(SF_ZONE).toInstant().toEpochMilli(),
            parkedAtMillis = parkedAtMillis,
            reminderOffsetMillis = reminderOffsetMillis,
            urgentOffsetMillis = urgentOffsetMillis,
            rollForwardAtMillis = rppRegulation?.let { rppWindowEndMillis(it, rppDeadline.moveByDateTime) }
                ?: rppDeadline.moveByDateTime.atZone(SF_ZONE).toInstant().toEpochMilli()
        )
    } else {
        cancelRppReminder(context, carId) // no RPP match here, car holds a permit, or re-parking away from a previous RPP spot
        false
    }

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
    val sweepEnd = NextSweepCalculator.sweepInProgressEndDateTime(segment, parkedAt)
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
    db.parkedStateDao().setNotificationScheduled(carId, parkedAtMillis, sweepHandled || rppHandled)

    enqueueWidgetRefresh(context)
}