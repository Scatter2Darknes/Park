package com.example.park

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
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
import java.time.LocalDate

/**
 * The permit source end to end, on the real arming code (Robolectric, in-memory Room): the heads-up, the
 * park-time notice, the banner status, the switch and off-street parks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PermitSourceTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var now = 0L

    private val s1Points = listOf(LatLng(37.7800, -122.4600), LatLng(37.7800, -122.4590))
    private val carId = 1L

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
        context.dataStore.edit { it.clear() }
        val settings = SettingsRepository(context)
        settings.setClosureParkTimeCheck(true)
        settings.setClosureBackgroundSync(false)
        settings.setClosureAlertLeadHours(48)
        // Fresh data everywhere, so the park-time step never goes to the network.
        settings.setClosuresLastSyncMillis(now)
        settings.setTowLastSyncMillis(now)
        settings.setTowNewestEntryMillis(now)
        settings.setPermitsLastSyncMillis(now)
        db.carDao().insert(Car(id = carId, name = "Civic", isDefault = true))
        db.streetSegmentDao().insertAll(listOf(StreetSegment(
            blockSweepId = "S1", cnn = "100", corridor = "FELL ST", limits = "", cnnRightLeft = "L", blockSide = "North",
            fullName = "Holiday", fromHour = 9, toHour = 11, // no weekday: no sweep, so only the permit arms anything
            week1 = true, week2 = true, week3 = true, week4 = true, week5 = true, holidays = false,
            points = s1Points, centroidLat = 37.7800, centroidLng = -122.4595
        )))
    } }

    @After
    fun tearDown() = AppDatabase.replaceInstanceForTests(null)

    private fun permit(number: String, cnn: String, startMillis: Long, endMillis: Long) = StreetUsePermit(
        rowKey = "${number}_$cnn", permitNumber = number, cnn = cnn, streetName = "FELL ST", crossStreet1 = null,
        crossStreet2 = null, purpose = "Parking/Staging", status = "APPROVED", startMillis = startMillis, endMillis = endMillis
    )

    private suspend fun parkOnS1() = db.parkedStateDao().upsert(ParkedState(
        carId = carId, segmentBlockSweepId = "S1", sideConfirmed = true, parkedLat = 37.7800, parkedLng = -122.4595,
        parkedAtMillis = now - 3_600_000L, nextSweepAtMillis = null
    ))

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private fun notifications() = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()
    private val advanceCode get() = reminderRequestCode(carId, ReminderKind.PERMIT_ADVANCE)
    private val noticeId get() = NotificationIds.forCar(carId, NotificationIds.Purpose.PERMIT_NOTICE)

    /** A permit start on a round minute, [hours] from now. */
    private fun startIn(hours: Long) = (now + hours * 3_600_000L) / 60_000L * 60_000L

    @Test
    fun aPermitStartingWithinTheLeadTime_headsUpFiresAtOnce_onlyOnce_andTheBannerShowsIt() = runBlocking {
        val start = startIn(30)
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-1", "100", start, start + 8 * 3_600_000L)))
        parkOnS1()

        rearmAllActiveReminders(context)

        val posted = notifications().single { it.id == advanceCode }
        val (title, text) = buildReminderContent("Civic", "FELL ST", start, ReminderKind.PERMIT_ADVANCE)
        assertEquals(title, posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(text, posted.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(NotificationHelper.CHANNEL_ID_NORMAL, posted.notification.channelId)
        assertEquals(start, db.parkedStateDao().getForCar(carId)!!.permitAdvanceDeliveredForMillis)

        NotificationHelper.cancel(context, advanceCode) // the user swipes it away
        rearmAllActiveReminders(context)
        assertTrue("not re-posted for the same permit", notifications().none { it.id == advanceCode })

        val status = loadCarsWithStatus(context).single().permitStatus
        assertTrue(status is PermitStatus.Posted && !status.inEffect)
        assertNull("never a deadline", loadCarsWithStatus(context).single().soonestDeadline())
    }

    @Test
    fun aPermitFurtherOut_getsAnAlarmAtTheLeadTime() = runBlocking {
        val start = startIn(5 * 24)
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-2", "100", start, start + 8 * 3_600_000L)))
        parkOnS1()

        rearmAllActiveReminders(context)

        val alarm = alarms().single { shadowOf(it.operation).requestCode == advanceCode }
        assertEquals(start - 48 * 3_600_000L, alarm.triggerAtTime)
        assertEquals(ReminderKind.PERMIT_ADVANCE.name, shadowOf(alarm.operation).savedIntent.getStringExtra("kind"))
        assertTrue(notifications().isEmpty())
        assertEquals(PermitStatus.Clear, loadCarsWithStatus(context).single().permitStatus) // beyond the lead: no banner line yet
    }

    @Test
    fun aPermitAlreadyInEffect_getsTheParkTimeNotice_andNoHeadsUp() = runBlocking {
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-3", "100", now - 3_600_000L, now + 5 * 3_600_000L)))
        val segment = db.streetSegmentDao().getById("S1")!!

        saveParkedState(context, carId, segment, LatLng(37.7800, -122.4595))
        ClosureCheckCenter.awaitFor(carId, 30_000)

        assertTrue("park-time notice", notifications().any { it.id == noticeId })
        assertTrue("no heads-up for a permit that already started", alarms().none { shadowOf(it.operation).requestCode == advanceCode })
        val status = loadCarsWithStatus(context).single().permitStatus
        assertTrue(status is PermitStatus.Posted && status.inEffect)
    }

    @Test
    fun switchedOff_nothingArmedOrShown_andSwitchingOffClearsWhatWasThere() = runBlocking {
        val start = startIn(30)
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-4", "100", start, start + 8 * 3_600_000L)))
        parkOnS1()
        rearmAllActiveReminders(context)
        assertTrue(notifications().any { it.id == advanceCode })

        SettingsRepository(context).setPermitChecksEnabled(false)
        rescheduleAllActiveReminders(context)

        assertTrue(notifications().none { it.id == advanceCode })
        assertTrue(alarms().none { shadowOf(it.operation).requestCode == advanceCode })
        assertNull(loadCarsWithStatus(context).single().permitStatus)
    }

    @Test
    fun offStreetSavedLocation_getsNoPermitWarnings() = runBlocking {
        val start = startIn(30)
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-5", "100", start, start + 8 * 3_600_000L)))
        val garage = db.savedLocationDao().insert(SavedLocation(name = "Garage", lat = 37.7800, lng = -122.4595, isOffStreet = true))
        db.parkedStateDao().upsert(ParkedState(
            carId = carId, segmentBlockSweepId = null, sideConfirmed = true, parkedLat = 37.7800, parkedLng = -122.4595,
            parkedAtMillis = now - 3_600_000L, nextSweepAtMillis = null, parkedViaSafeLocationId = garage
        ))

        rearmAllActiveReminders(context)

        assertTrue(notifications().isEmpty())
        assertTrue(alarms().none { shadowOf(it.operation).requestCode == advanceCode })
        assertEquals(PermitStatus.Clear, loadCarsWithStatus(context).single().permitStatus)
    }

    @Test
    fun unparkClearsPermitNotices() = runBlocking {
        db.streetUsePermitDao().insertAll(listOf(permit("26TOC-6", "100", startIn(30), startIn(38))))
        parkOnS1()
        rearmAllActiveReminders(context)
        assertFalse(notifications().isEmpty())

        unsubscribeParking(context, carId)

        assertTrue(notifications().isEmpty())
        assertTrue(alarms().isEmpty())
    }

    @Test
    fun theDataIsOld_bannerSaysUnchecked() = runBlocking {
        SettingsRepository(context).setPermitsLastSyncMillis(now - 4 * 24 * 3_600_000L)
        parkOnS1()
        assertEquals(PermitStatus.Unchecked, loadCarsWithStatus(context).single().permitStatus)
        assertEquals(LocalDate.now(SF_ZONE), LocalDate.now(SF_ZONE)) // (keeps the import honest if the date helpers move)
    }
}
