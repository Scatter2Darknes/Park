package com.example.park

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Error isolation (docs/park-sources-refactor-spec.md, Part B, Tests §3): one curb source failing, or one
 * car failing, must never stop the others from being armed, shown or notified.
 *
 * The failures are real ones, not test hooks: a stored row whose geometry is not valid JSON makes Room's
 * type converter throw when it is read, the way a corrupt row or a bad sync would. Each scenario here threw
 * straight through v1.04's arming (see ArmingCharacterizationTest for the setup this mirrors).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SourceIsolationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var today: LocalDate
    private var now = 0L

    private val s1Points = listOf(LatLng(37.7800, -122.4600), LatLng(37.7800, -122.4590))
    private val s2Points = listOf(LatLng(37.7700, -122.4500), LatLng(37.7700, -122.4490))
    private val p2 = LatLng(37.7700, -122.4495)

    @Before
    fun setUp() { runBlocking {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.replaceInstanceForTests(db)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        NotificationHelper.createChannel(context)
        now = System.currentTimeMillis()
        today = LocalDate.now(SF_ZONE)
        context.dataStore.edit { it.clear() }
        val settings = SettingsRepository(context)
        settings.setClosureParkTimeCheck(true)
        settings.setClosureBackgroundSync(false)
        settings.setClosuresLastSyncMillis(now)
        settings.setTowLastSyncMillis(now)
        settings.setTowNewestEntryMillis(now)
        db.carDao().insert(Car(id = 1, name = "Civic", isDefault = true))
        db.carDao().insert(Car(id = 2, name = "Bike rack"))
    } }

    @After
    fun tearDown() = AppDatabase.replaceInstanceForTests(null)

    private val sweepDay get() = today.plusDays(3)
    private fun dayName(d: DayOfWeek) = d.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun segment(id: String, cnn: String, points: List<LatLng>) = StreetSegment(
        blockSweepId = id, cnn = cnn, corridor = "FELL ST", limits = "", cnnRightLeft = "L", blockSide = "North",
        fullName = dayName(sweepDay.dayOfWeek), fromHour = 9, toHour = 11,
        week1 = true, week2 = true, week3 = true, week4 = true, week5 = true, holidays = false,
        points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    private fun rpp(id: String, points: List<LatLng>): RppZoneRegulation {
        val d = mapOf(DayOfWeek.MONDAY to "M", DayOfWeek.TUESDAY to "Tu", DayOfWeek.WEDNESDAY to "W", DayOfWeek.THURSDAY to "Th",
            DayOfWeek.FRIDAY to "F", DayOfWeek.SATURDAY to "Sa", DayOfWeek.SUNDAY to "Su").getValue(sweepDay.dayOfWeek)
        return RppZoneRegulation(objectId = id, zoneLetters = "A", days = "$d-$d", hrsBegin = 0, hrsEnd = 2359, hrLimit = 2f,
            points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average())
    }

    private fun tow(id: String, cnn: String) = TowZone(
        rowId = id, caseNumber = null, permitNumber = null, cnns = towCnnList(listOf(cnn)), address = null, streetName = "FELL ST",
        fromStreet = null, toStreet = null, startEpochDay = today.plusDays(1).toEpochDay(), endEpochDay = sweepDay.toEpochDay(),
        startMinute = 7 * 60, endMinute = 17 * 60, allDay = false, daysMask = ALL_DAYS_MASK, daysText = null, enteredMillis = now
    )

    private fun closure(id: String, cnn: String, points: List<LatLng>, hoursAhead: Long) = StreetClosure(
        objectId = id, caseNum = id, caseName = null, type = null, cnn = cnn, street = "FELL ST", fromStreet = null, toStreet = null,
        vehicleImpact = null, startMillis = now + hoursAhead * 3_600_000L, endMillis = now + (hoursAhead + 10) * 3_600_000L,
        points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    private fun parked(carId: Long, segmentId: String?, point: LatLng, rppId: String? = null) = ParkedState(
        carId = carId, segmentBlockSweepId = segmentId, sideConfirmed = true, parkedLat = point.lat, parkedLng = point.lng,
        parkedAtMillis = now - 3_600_000L, nextSweepAtMillis = null, rppRegulationId = rppId
    )

    /** Makes a stored row unreadable: its geometry column no longer parses as JSON. */
    private fun corrupt(table: String, keyColumn: String, key: String) =
        db.openHelper.writableDatabase.execSQL("UPDATE $table SET points = 'not json' WHERE $keyColumn = '$key'")

    private fun alarmCodes(): Set<Int> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { shadowOf(it.operation).requestCode }.toSet()

    private fun notificationIds(): Set<Int> = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()

    private fun code(carId: Long, kind: ReminderKind) = reminderRequestCode(carId, kind)
    private fun code(carId: Long, purpose: NotificationIds.Purpose) = NotificationIds.forCar(carId, purpose)

    @Test
    fun aFailingSweepSource_stillArmsRpp() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S1", "100", s1Points)))
        db.rppZoneRegulationDao().insertAll(listOf(rpp("R1", s1Points)))
        db.parkedStateDao().upsert(parked(1, "S1", s1Points[0], rppId = "R1"))
        corrupt("street_segment", "blockSweepId", "S1")

        rearmAllActiveReminders(context)

        val codes = alarmCodes()
        assertTrue("RPP armed despite the sweep failure: $codes", code(1, ReminderKind.RPP_NORMAL) in codes && code(1, ReminderKind.RPP_URGENT) in codes)
        assertTrue("no sweep reminder from an unreadable segment", code(1, ReminderKind.NORMAL) !in codes)
    }

    @Test
    fun aFailingRppSource_stillArmsTheSourcesAfterIt_andTheOtherCar() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S1", "100", s1Points), segment("S2", "200", s2Points)))
        db.rppZoneRegulationDao().insertAll(listOf(rpp("R1", s1Points)))
        db.towZoneDao().insertAll(listOf(tow("T1", "100"), tow("T2", "200")))
        db.streetClosureDao().insertAll(listOf(closure("C1", "100", s1Points, hoursAhead = 72)))
        db.parkedStateDao().upsert(parked(1, "S1", s1Points[0], rppId = "R1"))
        db.parkedStateDao().upsert(parked(2, "S2", s2Points[0]))
        corrupt("rpp_zone_regulation", "objectId", "R1")

        rearmAllActiveReminders(context)

        val codes = alarmCodes()
        for (expected in listOf(
            code(1, ReminderKind.NORMAL), // before RPP in the order
            code(1, NotificationIds.Purpose.CLOSURE_ALERT), // after it
            code(1, ReminderKind.TOW_NORMAL),
            code(2, ReminderKind.NORMAL), // the other car
            code(2, ReminderKind.TOW_NORMAL)
        )) assertTrue("expected alarm $expected in $codes", expected in codes)
    }

    @Test
    fun aFailingResolve_showsTheOtherSourcesStatus_andTheOtherCar() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S1", "100", s1Points), segment("S2", "200", s2Points)))
        db.rppZoneRegulationDao().insertAll(listOf(rpp("R1", s1Points)))
        db.towZoneDao().insertAll(listOf(tow("T1", "100")))
        // Sweep a week out, so the tow zone's first window (tomorrow 7 AM SF) is the soonest deadline at any hour.
        // (It was "now + 24 h", which comes BEFORE tomorrow 7 AM when the test runs between midnight and 7 AM SF.)
        db.parkedStateDao().upsert(parked(1, "S1", s1Points[0], rppId = "R1").copy(nextSweepAtMillis = now + 7 * 86_400_000L))
        db.parkedStateDao().upsert(parked(2, "S2", s2Points[0]))
        corrupt("rpp_zone_regulation", "objectId", "R1")

        val statuses = loadCarsWithStatus(context).associateBy { it.car.id }

        val car1 = statuses.getValue(1)
        assertEquals(null, car1.rppDeadline) // RPP couldn't be read: no RPP line
        assertEquals(ClosureStatus.Clear, car1.closureStatus)
        assertTrue("tow still resolved", car1.towDeadlineMillis != null)
        assertEquals(DeadlineKind.TOW, car1.soonestDeadline()?.kind)
        assertEquals(ClosureStatus.Clear, statuses.getValue(2).closureStatus)
    }

    @Test
    fun aFailingParkTimeClosureNotice_stillPostsTheTowNotice() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S2", "200", s2Points)))
        db.towZoneDao().insertAll(listOf(tow("T2", "200")))
        db.streetClosureDao().insertAll(listOf(closure("C9", "900", s2Points.map { LatLng(it.lat + 0.0009, it.lng) }, hoursAhead = 24)))
        corrupt("street_closure", "objectId", "C9")

        saveUnmanagedParkedState(context, 1, p2) // uncertain tow match: a park-time "check signs" notice
        ClosureCheckCenter.awaitFor(1, 30_000)

        assertTrue("tow notice posted despite the closure failure", code(1, NotificationIds.Purpose.TOW_NEARBY) in notificationIds())
    }
}
