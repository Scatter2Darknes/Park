package com.example.park

import android.content.Context
import kotlinx.coroutines.flow.first
import java.time.ZoneId

// rppDeadline is resolved once here (at load time), same point-in-time-snapshot approach
// parkedState.nextSweepAtMillis already uses — not continuously live, but refreshed on every
// reload() the same way sweep data already is.
// closureStatus: street closures affecting the parked spot (see ClosureAlerts.kt), resolved at load
// time like rppDeadline. Null when the car isn't parked. NOT a deadline, so soonestDeadline ignores it.
data class CarWithStatus(
    val car: Car,
    val parkedState: ParkedState?,
    val rppDeadline: RppWarning? = null,
    val closureStatus: ClosureStatus? = null
)

enum class DeadlineKind { SWEEP, RPP, METER }
data class CarDeadline(val millis: Long, val kind: DeadlineKind)

/**
 * Whichever binding deadline is sooner for this car — sweep start, RPP non-permit move-by, or
 * a manually-set meter timer — since a parked car can have any combination of these, or none.
 * Backs every place that ranks/displays "the thing this car needs to move for" (the map's
 * priority banner, the widget), so none of them silently ignore a deadline that's more urgent
 * than (or the only) one sweep data alone would show.
 */
fun CarWithStatus.soonestDeadline(): CarDeadline? {
    val sweep = parkedState?.nextSweepAtMillis?.let { CarDeadline(it, DeadlineKind.SWEEP) }
    val rpp = rppDeadline?.moveByDateTime
        ?.atZone(SF_ZONE)?.toInstant()?.toEpochMilli()
        ?.let { CarDeadline(it, DeadlineKind.RPP) }
    val meter = parkedState?.meterTimerAtMillis?.let { CarDeadline(it, DeadlineKind.METER) }
    return listOfNotNull(sweep, rpp, meter).minByOrNull { it.millis }
}

suspend fun loadCarsWithStatus(context: Context): List<CarWithStatus> {
    val db = AppDatabase.getInstance(context)
    val closuresLastSync = SettingsRepository(context).closuresLastSyncMillis.first()
    return db.carDao().getAll().map { car ->
        val parked = db.parkedStateDao().getForCar(car.id)
        val rppDeadline = parked?.let { resolveRppDeadline(context, it, car) }
        val closureStatus = parked?.let {
            try {
                resolveClosureStatus(context, it, closuresLastSync)
            } catch (e: Exception) {
                android.util.Log.w("ClosureAlert", "Resolving closure status for car ${car.id} failed", e)
                ClosureStatus.Unchecked // can't tell, so never "clear"
            }
        }
        CarWithStatus(car, parked, rppDeadline, closureStatus)
    }
}

/**
 * Cars with a live parked state, sorted by whichever deadline (sweep or RPP) is soonest for
 * each. Backs the map's top summary banner, which needs to show every currently-parked car
 * (not just the default one) ranked by urgency. Cars with no computable deadline at all sort
 * last, since there's nothing to rank them by.
 */
suspend fun loadActiveParkedCars(context: Context): List<CarWithStatus> =
    loadCarsWithStatus(context)
        .filter { it.parkedState != null }
        .sortedBy { it.soonestDeadline()?.millis ?: Long.MAX_VALUE }

// Widget refresh goes through enqueueWidgetRefresh (a WorkManager job), not a direct
// updateParkWidget() call — see the comment on enqueueWidgetRefresh in Parkwidget.kt for why
// a direct call from a screen-scoped coroutine can silently drop the update if the app leaves
// the foreground right after (confirmed via Logcat: updateAll() reporting success is not the
// same as provideGlance actually having run).
suspend fun unsubscribeParking(context: Context, carId: Long) {
    cancelParkingReminder(context, carId)
    AppDatabase.getInstance(context).parkedStateDao().clearForCar(carId)
    enqueueWidgetRefresh(context)
}

/** Enough to fully restore a deleted car (profile + parked state) via [undoDeleteCar] — the
 *  car's own row (customization, Bluetooth link) plus whatever parked state it had, if any. */
data class DeletedCarSnapshot(val car: Car, val parkedState: ParkedState?)

suspend fun deleteCarCompletely(context: Context, car: Car): DeletedCarSnapshot {
    val db = AppDatabase.getInstance(context)
    val parkedState = db.parkedStateDao().getForCar(car.id)
    cancelParkingReminder(context, car.id)
    db.parkedStateDao().clearForCar(car.id)
    db.carDao().delete(car)
    if (car.isDefault) {
        db.carDao().getAll().firstOrNull()?.let { db.carDao().setDefault(it.id) }
    }
    enqueueWidgetRefresh(context)
    return DeletedCarSnapshot(car, parkedState)
}

/**
 * Undoes [deleteCarCompletely] from a snapshot taken right before the delete — re-inserts the
 * car (a fresh row/id, since Room's autoincrement won't reuse the old one) with all its
 * customization and Bluetooth link intact, restores its parked state under the new id if it
 * had one, and reschedules reminders for it. Only meaningful within the snackbar's own short
 * undo window; the caller is responsible for not offering undo after that.
 */
suspend fun undoDeleteCar(context: Context, snapshot: DeletedCarSnapshot) {
    val db = AppDatabase.getInstance(context)
    val newCarId = db.carDao().insert(snapshot.car.copy(id = 0, isDefault = false))
    if (snapshot.car.isDefault) {
        db.carDao().setDefault(newCarId)
    }
    snapshot.parkedState?.let { ps ->
        db.parkedStateDao().upsert(ps.copy(id = 0, carId = newCarId))
    }
    // Reuses the same reschedule-everything path Settings changes already rely on, rather than
    // re-deriving carName/corridor/nextSweepAtMillis here just to call scheduleParkingReminders
    // directly — correctness matters more than avoiding one extra query pass for an undo action
    // that only runs on an explicit tap.
    rescheduleAllActiveReminders(context)
    enqueueWidgetRefresh(context)
}

suspend fun setDefaultCar(context: Context, carId: Long) {
    AppDatabase.getInstance(context).carDao().setDefault(carId)
}

suspend fun unsetDefaultCar(context: Context, carId: Long) {
    AppDatabase.getInstance(context).carDao().clearDefaultById(carId)
}

/**
 * Best available point to center the map on for "See Location": an exact pin the user
 * dropped (most precise), else the matched segment's centroid (accurate regardless of where
 * the phone is now), else the raw GPS point used at parking time as a last resort.
 */
suspend fun resolveCarLocation(context: Context, parkedState: ParkedState): LatLng {
    if (parkedState.exactPinLat != null && parkedState.exactPinLng != null) {
        return LatLng(parkedState.exactPinLat, parkedState.exactPinLng)
    }
    val segment = parkedState.segmentBlockSweepId?.let {
        AppDatabase.getInstance(context).streetSegmentDao().getById(it)
    }
    if (segment != null) {
        return LatLng(segment.centroidLat, segment.centroidLng)
    }
    return LatLng(parkedState.parkedLat, parkedState.parkedLng)
}