package com.example.park

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Proves the Robolectric setup works before the characterization tests rely on it: the app's real Room
 * database runs in memory through AppDatabase.getInstance, and AlarmManager's simulated ("shadow") version
 * records the alarms set on it so a test can read them back.
 *
 * @RunWith(RobolectricTestRunner::class) is what makes a JUnit test a Robolectric one: it loads the
 * Android framework classes (normally stubs that throw on the JVM) from a real Android build.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, not ParkApp: ParkApp.onCreate inserts a default car in the background, which could
// land in this test's empty database (it did, intermittently).
@Config(application = android.app.Application::class)
class RobolectricSmokeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.replaceInstanceForTests(db)
    }

    @After
    fun tearDown() {
        AppDatabase.replaceInstanceForTests(null)
    }

    @Test
    fun roomRunsInMemoryThroughGetInstance() = runBlocking {
        val dao = AppDatabase.getInstance(context).carDao()
        dao.insert(Car(name = "Civic"))
        assertEquals(listOf("Civic"), dao.getAll().map { it.name })
    }

    @Test
    fun eachTestStartsWithAnEmptyDatabase() = runBlocking {
        assertEquals(0, AppDatabase.getInstance(context).carDao().count())
    }

    @Test
    fun shadowAlarmManagerRecordsAlarms() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 42, Intent("test"), PendingIntent.FLAG_IMMUTABLE)
        alarmManager.set(AlarmManager.RTC_WAKEUP, 1_000L, pi)
        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(1_000L, scheduled[0].triggerAtTime)
    }
}
