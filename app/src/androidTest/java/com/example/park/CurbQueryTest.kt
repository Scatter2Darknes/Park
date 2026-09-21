package com.example.park

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The curb query against a real (in-memory) Room database: it returns every row of one curb, only that curb, with overrides applied. */
@RunWith(AndroidJUnit4::class)
class CurbQueryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun row(id: String, cnn: String, side: String, name: String, from: Int = 6, to: Int = 8) = StreetSegment(
        blockSweepId = id, cnn = cnn, corridor = "Main St", limits = "A to B", cnnRightLeft = side, blockSide = "North",
        fullName = name, fromHour = from, toHour = to, week1 = true, week2 = true, week3 = true, week4 = true, week5 = true,
        holidays = false, points = listOf(LatLng(37.1, -122.1), LatLng(37.2, -122.2)), centroidLat = 37.15, centroidLng = -122.15
    )

    @Test
    fun getByCurb_returnsAllRowsOfThatCurbOnly_withOverridesApplied() = runBlocking {
        db.streetSegmentDao().insertAll(
            listOf(
                row("mon", "123", "R", "Monday"),
                row("thu", "123", "R", "Thursday"),
                row("hol", "123", "R", "HOLIDAY"),
                row("otherSide", "123", "L", "Tuesday"),   // same street, other side: a different curb
                row("otherStreet", "999", "R", "Friday")   // different curb entirely
            )
        )
        db.scheduleOverrideDao().upsert(
            ScheduleOverride("thu", "Friday", true, true, true, true, true, 9, 11)
        )

        val curb = db.streetSegmentDao().getByCurb("123", "R")

        assertEquals(setOf("mon", "thu", "hol"), curb.map { it.blockSweepId }.toSet())
        val thu = curb.first { it.blockSweepId == "thu" }
        assertEquals("override applied: Friday", "Friday", thu.fullName)
        assertEquals(9, thu.fromHour)
        assertTrue(db.streetSegmentDao().getByCurb("nope", "R").isEmpty())
    }
}
