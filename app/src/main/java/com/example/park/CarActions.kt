package com.example.park

import android.content.Context
import kotlinx.coroutines.flow.first
import java.time.ZoneId

// rppDeadline is resolved once here (at load time), same point-in-time-snapshot approach
// parkedState.nextSweepAtMillis already uses — not continuously live, but refreshed on every
// reload() the same way sweep data already is.
// closureStatus: street closures affecting the parked spot (see ClosureAlerts.kt), resolved at load
// time like rppDeadline. Null when the car isn't parked. NOT a deadline, so soonestDeadline ignores it.
// towDeadlineMillis / towStatus: temporary tow zones (see TowAlerts.kt), resolved at load time too.
// The deadline IS a deadline (soonestDeadline ranks it); the status is the banner's tow line (in
// effect now, maybe nearby, data unavailable or out of date). Both null when tow checks are off.
data class CarWithStatus(
    val car: Car,
    val parkedState: ParkedState?,
    val rppDeadline: RppWarning? = null,
    val closureStatus: ClosureStatus? = null,
    val towDeadlineMillis: Long? = null,
    val towStatus: TowStatus? = null
)

enum class DeadlineKind { SWEEP, RPP, METER, TOW }
data class CarDeadline(val millis: Long, val kind: DeadlineKind)

/**
 * Whichever binding deadline is soonest for this car — sweep start, RPP non-permit move-by, a
 * manually-set meter timer, or the next tow-zone enforcement window — since a parked car can have
 * any combination of these, or none. Each curb source contributes its own (CurbRestrictionSource.deadline;
 * closures never do). Backs every place that ranks/displays "the thing this car needs to move for"
 * (the map's priority banner, the widget), so none of them silently ignore a deadline that's more
 * urgent than (or the only) one sweep data alone would show. A tie goes to the earlier source in
 * CurbSources.all.
 *
 * A deadline that has already PASSED still counts, on purpose: it means overdue, and the banner shows it
 * as "Now" (formatCountdown). The case that matters is RPP: a car still parked past its non-permit limit
 * keeps that passed move-by time for as long as the enforcement window is open, and must rank as the most
 * urgent thing on the screen, not drop out of it. (Sweep and meter are only briefly past, until the
 * roll-forward or the meter alarm clears them.)
 */
fun CarWithStatus.soonestDeadline(): CarDeadline? =
    CurbSources.all.mapNotNull { it.deadline(this) }.minByOrNull { it.millis }

/** Every car with its parked state and each curb source's status for it (see CurbRestrictionSource.resolve). */
suspend fun loadCarsWithStatus(context: Context): List<CarWithStatus> {
    val db = AppDatabase.getInstance(context)
    val settings = loadSourceSettings(context)
    return db.carDao().getAll().map { car ->
        val parked = db.parkedStateDao().getForCar(car.id)
        // Each source on its own: a failing one shows "can't tell" (its `unresolved`), and the rest still show.
        val results = if (parked == null) emptyList() else CurbSources.all.map { source ->
            isolated("Resolving ${source.id} for car ${car.id}") { source.resolve(context, parked, car, settings) } ?: source.unresolved
        }
        val tow = results.firstNotNullOfOrNull { it as? SourceResult.Tow }
        CarWithStatus(
            car, parked,
            rppDeadline = results.firstNotNullOfOrNull { (it as? SourceResult.Rpp)?.warning },
            closureStatus = results.firstNotNullOfOrNull { (it as? SourceResult.Closure)?.status },
            towDeadlineMillis = tow?.deadlineMillis,
            towStatus = tow?.status
        )
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
    refreshBluetoothLinks(context) // a deleted car must not stay "driving" if its device is connected
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
    // Show it as driving again if its device is connected, but don't unpark the parked state just restored.
    refreshBluetoothLinks(context, runMissedConnect = false)
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