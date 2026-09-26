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

    @Test
    fun migrate12To13_keepsSegmentAndRppRows_andAddsNullLastSeenSyncId() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL(
                """
                INSERT INTO street_segment
                    (blockSweepId, cnn, corridor, limits, cnnRightLeft, blockSide, fullName,
                     fromHour, toHour, week1, week2, week3, week4, week5, holidays,
                     points, centroidLat, centroidLng)
                VALUES
                    ('seg-1', '123', 'Main St', 'A to B', 'R', 'North', 'Mon',
                     6, 8, 1, 1, 1, 1, 1, 0,
                     '[[37.1,-122.1],[37.2,-122.2]]', 37.15, -122.15)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO rpp_zone_regulation
                    (objectId, zoneLetters, days, hrsBegin, hrsEnd, hrLimit, points, centroidLat, centroidLng)
                VALUES
                    ('rpp-7', 'A', 'M-F', 800, 1800, 2.0, '[[37.1,-122.1]]', 37.1, -122.1)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)

        db.query("SELECT corridor, fromHour, toHour, lastSeenSyncId FROM street_segment").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Main St", c.getString(0))
            assertEquals(6, c.getInt(1))
            assertEquals(8, c.getInt(2))
            assertTrue("street_segment.lastSeenSyncId should be NULL after migration", c.isNull(3))
            assertEquals(1, c.count)
        }
        db.query("SELECT zoneLetters, hrLimit, lastSeenSyncId FROM rpp_zone_regulation").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("A", c.getString(0))
            assertEquals(2.0, c.getDouble(1), 0.0)
            assertTrue("rpp_zone_regulation.lastSeenSyncId should be NULL after migration", c.isNull(2))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrateFrom11ThroughToCurrent_runsTheWholeChain() {
        helper.createDatabase(TEST_DB, 11).close()
        helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).close()
    }

    @Test
    fun migrate13To14_keepsRppRows_andAddsNullLimitAssumed() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                """
                INSERT INTO rpp_zone_regulation
                    (objectId, zoneLetters, days, hrsBegin, hrsEnd, hrLimit, points, centroidLat, centroidLng, lastSeenSyncId)
                VALUES
                    ('rpp-7', 'A', 'M-F', 800, 1800, 2.0, '[[37.1,-122.1]]', 37.1, -122.1, 555)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14)

        db.query("SELECT zoneLetters, hrLimit, lastSeenSyncId, limitAssumed FROM rpp_zone_regulation").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("A", c.getString(0))
            assertEquals(2.0, c.getDouble(1), 0.0)
            assertEquals(555L, c.getLong(2))
            assertTrue("limitAssumed should be NULL after migration", c.isNull(3))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate14To15_keepsSegments_andCreatesTheCurbIndex() {
        helper.createDatabase(TEST_DB, 14).apply {
            execSQL(
                """
                INSERT INTO street_segment
                    (blockSweepId, cnn, corridor, limits, cnnRightLeft, blockSide, fullName,
                     fromHour, toHour, week1, week2, week3, week4, week5, holidays,
                     points, centroidLat, centroidLng)
                VALUES
                    ('seg-1', '123', 'Main St', 'A to B', 'R', 'North', 'Mon', 6, 8, 1, 1, 1, 1, 1, 0, '[[37.1,-122.1],[37.2,-122.2]]', 37.15, -122.15),
                    ('seg-2', '123', 'Main St', 'A to B', 'R', 'North', 'Thu', 6, 8, 1, 1, 1, 1, 1, 0, '[[37.1,-122.1],[37.2,-122.2]]', 37.15, -122.15)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15)

        db.query("SELECT COUNT(*) FROM street_segment WHERE cnn = '123' AND cnnRightLeft = 'R'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_street_segment_cnn_cnnRightLeft'").use { c ->
            assertEquals("the curb index should exist", 1, c.count)
        }
    }

    @Test
    fun migrate19To20_keepsParkedRow_andCreatesAnEmptyClosureTable() {
        helper.createDatabase(TEST_DB, 19).apply {
            execSQL(
                """
                INSERT INTO parked_state
                    (id, carId, segmentBlockSweepId, sideConfirmed, parkedLat, parkedLng,
                     parkedAtMillis, nextSweepAtMillis, notificationScheduled, meterTimerAtMillis)
                VALUES
                    (1, 42, 'block-123', 1, 37.7749, -122.4194, 1000000, 2000000, 1, 3000000)
                """.trimIndent()
            )
            close()
        }

        // Also validates street_closure's columns and both indices against schemas/20.json.
        val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, MIGRATION_19_20)

        db.query("SELECT carId, meterTimerAtMillis FROM parked_state").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(3000000L, c.getLong(1))
            assertEquals(1, c.count)
        }
        db.query("SELECT COUNT(*) FROM street_closure").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.execSQL(
            """
            INSERT INTO street_closure
                (objectId, cnn, startMillis, endMillis, points, centroidLat, centroidLng)
            VALUES ('35303', '10404000', 1, 2, '[]', 0.0, 0.0)
            """.trimIndent()
        )
    }

    @Test
    fun migrate20To21_keepsParkedRow_andAddsNullClosureMarker() {
        helper.createDatabase(TEST_DB, 20).apply {
            execSQL(
                """
                INSERT INTO parked_state
                    (id, carId, segmentBlockSweepId, sideConfirmed, parkedLat, parkedLng,
                     parkedAtMillis, nextSweepAtMillis, notificationScheduled, normalDeliveredForMillis)
                VALUES
                    (1, 42, 'block-123', 1, 37.7749, -122.4194, 1000000, 2000000, 1, 2000000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21)

        db.query("SELECT carId, normalDeliveredForMillis, closureDeliveredForMillis FROM parked_state").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(2000000L, c.getLong(1))
            assertTrue("closureDeliveredForMillis should be NULL after migration", c.isNull(2))
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate21To22_keepsParkedAndSavedRows_andAddsTowTableAndNullColumns() {
        helper.createDatabase(TEST_DB, 21).apply {
            execSQL(
                """
                INSERT INTO parked_state
                    (id, carId, segmentBlockSweepId, sideConfirmed, parkedLat, parkedLng,
                     parkedAtMillis, nextSweepAtMillis, notificationScheduled, closureDeliveredForMillis)
                VALUES
                    (1, 42, 'block-123', 1, 37.7749, -122.4194, 1000000, 2000000, 1, 5000000)
                """.trimIndent()
            )
            execSQL("INSERT INTO saved_location (id, name, lat, lng, isSafeFromSweeping) VALUES (7, 'Garage', 37.1, -122.1, 1)")
            close()
        }

        // Also validates tow_zone's columns and index against schemas/22.json.
        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22)

        db.query("SELECT carId, closureDeliveredForMillis, towNormalDeliveredForMillis, towUrgentDeliveredForMillis, towAdvanceDeliveredForMillis FROM parked_state").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(5000000L, c.getLong(1))
            assertTrue(c.isNull(2)); assertTrue(c.isNull(3)); assertTrue(c.isNull(4))
        }
        db.query("SELECT isSafeFromSweeping, isOffStreet FROM saved_location").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertTrue("isOffStreet should be NULL (= not off-street) after migration", c.isNull(1))
        }
        db.execSQL(
            """
            INSERT INTO tow_zone (rowId, cnns, startEpochDay, endEpochDay, startMinute, endMinute, allDay, daysMask)
            VALUES ('row-1', ',5440000,', 20000, 20010, 420, 1020, 0, 31)
            """.trimIndent()
        )
    }

    @Test
    fun migrate22To23_keepsParkedRows_andAddsPermitTableAndNullMarker() {
        helper.createDatabase(TEST_DB, 22).apply {
            execSQL(
                """
                INSERT INTO parked_state
                    (id, carId, segmentBlockSweepId, sideConfirmed, parkedLat, parkedLng,
                     parkedAtMillis, nextSweepAtMillis, notificationScheduled, towAdvanceDeliveredForMillis)
                VALUES
                    (1, 42, 'block-123', 1, 37.7749, -122.4194, 1000000, 2000000, 1, 6000000)
                """.trimIndent()
            )
            close()
        }

        // Also validates street_use_permit's columns and indices against schemas/23.json.
        val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, MIGRATION_22_23)

        db.query("SELECT carId, towAdvanceDeliveredForMillis, permitAdvanceDeliveredForMillis FROM parked_state").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertEquals(6000000L, c.getLong(1))
            assertTrue(c.isNull(2))
        }
        db.execSQL(
            """
            INSERT INTO street_use_permit (rowKey, permitNumber, cnn, startMillis, endMillis)
            VALUES ('26TOC-1_100', '26TOC-1', '100', 1000, 2000)
            """.trimIndent()
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
