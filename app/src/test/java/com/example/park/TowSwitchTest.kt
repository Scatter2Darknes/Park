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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The separate tow switch ("Tow-away zone reminders"): off, with closures still on, means no tow alarms, no tow
 * notice at park time and no tow banner line, while closures keep working. On (the default) is covered by
 * ArmingCharacterizationTest, which never touches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TowSwitchTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var today: LocalDate
    private var now = 0L

    private val s1Points = listOf(LatLng(37.7800, -122.4600), LatLng(37.7800, -122.4590))
    private val s2Points = listOf(LatLng(37.7700, -122.4500), LatLng(37.7700, -122.4490))

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
        settings.setTowChecksEnabled(false) // the switch under test
        db.carDao().insert(Car(id = 1, name = "Civic", isDefault = true))
    } }

    @After
    fun tearDown() = AppDatabase.replaceInstanceForTests(null)

    private fun segment(id: String, cnn: String, points: List<LatLng>) = StreetSegment(
        blockSweepId = id, cnn = cnn, corridor = "FELL ST", limits = "", cnnRightLeft = "L", blockSide = "North",
        fullName = today.plusDays(3).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }, fromHour = 9, toHour = 11,
        week1 = true, week2 = true, week3 = true, week4 = true, week5 = true, holidays = false,
        points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    private fun tow(id: String, cnn: String) = TowZone(
        rowId = id, caseNumber = null, permitNumber = null, cnns = towCnnList(listOf(cnn)), address = null, streetName = "FELL ST",
        fromStreet = null, toStreet = null, startEpochDay = today.plusDays(1).toEpochDay(), endEpochDay = today.plusDays(3).toEpochDay(),
        startMinute = 7 * 60, endMinute = 17 * 60, allDay = false, daysMask = ALL_DAYS_MASK, daysText = null, enteredMillis = now
    )

    private fun closure(id: String, cnn: String, points: List<LatLng>) = StreetClosure(
        objectId = id, caseNum = id, caseName = null, type = null, cnn = cnn, street = "FELL ST", fromStreet = null, toStreet = null,
        vehicleImpact = null, startMillis = now + 72 * 3_600_000L, endMillis = now + 82 * 3_600_000L,
        points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    private fun alarmCodes(): Set<Int> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { shadowOf(it.operation).requestCode }.toSet()

    private fun notificationIds(): Set<Int> = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id }.toSet()

    @Test
    fun towOff_closuresOn_noTowAlarmsOrBannerLine_closureStillArmed() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S1", "100", s1Points)))
        db.towZoneDao().insertAll(listOf(tow("T1", "100"))) // starts tomorrow: would arm and fire the advance alert
        db.streetClosureDao().insertAll(listOf(closure("C1", "100", s1Points)))
        db.parkedStateDao().upsert(ParkedState(carId = 1, segmentBlockSweepId = "S1", sideConfirmed = true,
            parkedLat = s1Points[0].lat, parkedLng = s1Points[0].lng, parkedAtMillis = now - 3_600_000L, nextSweepAtMillis = null))

        rearmAllActiveReminders(context)

        val codes = alarmCodes()
        for (kind in listOf(ReminderKind.TOW_NORMAL, ReminderKind.TOW_URGENT, ReminderKind.TOW_ADVANCE)) {
            assertFalse("$kind must not be armed with tow off", reminderRequestCode(1, kind) in codes)
        }
        assertFalse(reminderNotificationId(1, ReminderKind.TOW_ADVANCE) in notificationIds())
        assertTrue("closure alert still armed", NotificationIds.forCar(1, NotificationIds.Purpose.CLOSURE_ALERT) in codes)
        assertTrue("sweep still armed", reminderRequestCode(1, ReminderKind.NORMAL) in codes)

        val status = loadCarsWithStatus(context).single()
        assertNull(status.towStatus)
        assertNull(status.towDeadlineMillis)
        assertEquals(ClosureStatus.Affected::class, status.closureStatus!!::class)
    }

    @Test
    fun towOff_noTowNoticeAtPark() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S2", "200", s2Points)))
        db.towZoneDao().insertAll(listOf(tow("T2", "200"))) // an uncertain match: would get a "check signs" notice

        saveUnmanagedParkedState(context, 1, LatLng(37.7700, -122.4495))
        ClosureCheckCenter.awaitFor(1, 30_000)

        assertFalse(NotificationIds.forCar(1, NotificationIds.Purpose.TOW_NEARBY) in notificationIds())
    }

    @Test
    fun towNeedsAClosureSwitchToo() = runBlocking {
        val settings = SettingsRepository(context)
        settings.setTowChecksEnabled(true)
        assertTrue(settings.towEnabled())
        settings.setClosureParkTimeCheck(false) // both closure switches off: nothing fetches tow data
        assertFalse(settings.towEnabled())
    }
}
