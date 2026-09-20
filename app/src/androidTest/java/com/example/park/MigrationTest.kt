package com.example.park

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The project's first migration test. MigrationTestHelper builds a real database at an OLD
 * version from the exported schemas/<version>.json, lets us insert rows into it the way the old
 * app would have, then runs the real Migration and checks Room's own schema validation passes
 * (the columns/types/indices must match the newer JSON exactly) and that the data survived.
 * This is what actually proves a migration works, rather than just "compiles."
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate11To12_keepsParkedRow_andAddsNullDeliveryMarkers() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                """
                INSERT INTO parked_state
                    (id, carId, segmentBlockSweepId, sideConfirmed, parkedLat, parkedLng,
                     exactPinLat, exactPinLng, parkedAtMillis, nextSweepAtMillis,
                     notificationScheduled, rppRegulationId)
                VALUES
                    (1, 42, 'block-123', 1, 37.7749, -122.4194,
                     NULL, NULL, 1000000, 2000000,
                     1, 'rpp-9')
                """.trimIndent()
            )
            close()
        }

        // validateDroppedTables = true: also fails if the migration dropped anything it shouldn't.
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        db.query("SELECT * FROM parked_state").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(c.getColumnIndexOrThrow("carId")))
            assertEquals("block-123", c.getString(c.getColumnIndexOrThrow("segmentBlockSweepId")))
            assertEquals(1000000L, c.getLong(c.getColumnIndexOrThrow("parkedAtMillis")))
            assertEquals(2000000L, c.getLong(c.getColumnIndexOrThrow("nextSweepAtMillis")))
            assertEquals("rpp-9", c.getString(c.getColumnIndexOrThrow("rppRegulationId")))
            for (marker in listOf(
                "normalDeliveredForMillis", "urgentDeliveredForMillis",
                "rppNormalDeliveredForMillis", "rppUrgentDeliveredForMillis"
            )) {
                assertTrue("$marker should be NULL after migration", c.isNull(c.getColumnIndexOrThrow(marker)))
            }
            assertEquals(1, c.count)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
