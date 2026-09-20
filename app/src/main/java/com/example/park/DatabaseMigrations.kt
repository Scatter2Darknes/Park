package com.example.park

import androidx.room.migration.Migration

/**
 * Real migrations for the schema, added here as it evolves past version 5 (the version this
 * app originally shipped with). See AppDatabase.kt for how these plug into the database
 * builder, and why versions 1-4 are handled differently (destructive fallback only — they
 * predate any of this).
 */

/**
 * v5 -> v6: adds per-car highlight color and icon (both nullable TEXT). Existing car rows
 * get colorHex = NULL and iconEmoji = NULL automatically — SQLite fills new columns with
 * NULL for existing rows — and NULL is exactly what CarStyle.kt's carColorInt()/carIcon()
 * already treat as "use the default," so no backfill is needed for this purely additive
 * column.
 */
val MIGRATION_5_6 = Migration(5, 6) { db ->
    db.execSQL("ALTER TABLE car ADD COLUMN colorHex TEXT")
    db.execSQL("ALTER TABLE car ADD COLUMN iconEmoji TEXT")
}

/**
 * v6 -> v7: adds saved_location (frequent/named parking-adjacent spots like Home or Work)
 * and schedule_override (manual per-block schedule corrections for the DataSF-lags-signage
 * case). Both are brand-new tables — nothing to migrate data-wise, just create them.
 */
val MIGRATION_6_7 = Migration(6, 7) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS saved_location (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS schedule_override (
            blockSweepId TEXT NOT NULL PRIMARY KEY,
            fullName TEXT NOT NULL,
            week1 INTEGER NOT NULL,
            week2 INTEGER NOT NULL,
            week3 INTEGER NOT NULL,
            week4 INTEGER NOT NULL,
            week5 INTEGER NOT NULL,
            fromHour INTEGER NOT NULL,
            toHour INTEGER NOT NULL,
            notes TEXT
        )
        """.trimIndent()
    )
}

/**
 * v7 -> v8: adds photoPath to Car — a local file path to a user-picked photo, which takes
 * precedence over colorHex/iconEmoji when set (see CarAvatar in CarStyle.kt). Nullable and
 * purely additive, so existing car rows just get NULL and keep showing their current
 * color/emoji.
 */
val MIGRATION_7_8 = Migration(7, 8) { db ->
    db.execSQL("ALTER TABLE car ADD COLUMN photoPath TEXT")
}

/**
 * v8 -> v9: adds widgetTextColorHex to Car — the color the home screen widget uses for its
 * text, independently chosen since the widget's background may be a photo or road-status
 * color of unpredictable brightness, so auto-contrast can't be relied on the way it can for
 * a fixed neutral background. Nullable and purely additive.
 */
val MIGRATION_8_9 = Migration(8, 9) { db ->
    db.execSQL("ALTER TABLE car ADD COLUMN widgetTextColorHex TEXT")
}

/**
 * v9 -> v10: adds colorHex, iconEmoji, and photoPath to saved_location — the same
 * customization already available for cars, now for saved/frequent locations too. All
 * nullable and purely additive.
 */
val MIGRATION_9_10 = Migration(9, 10) { db ->
    db.execSQL("ALTER TABLE saved_location ADD COLUMN colorHex TEXT")
    db.execSQL("ALTER TABLE saved_location ADD COLUMN iconEmoji TEXT")
    db.execSQL("ALTER TABLE saved_location ADD COLUMN photoPath TEXT")
}

/**
 * v10 -> v11: adds RPP (Residential Parking Permit) zone awareness. rpp_zone_regulation is a
 * brand-new table (synced from SFMTA's ArcGIS feed — see RppDataApi.kt), nothing to migrate
 * data-wise. car.permitZoneLetters and parked_state.rppRegulationId are both nullable/additive;
 * existing rows get NULL, which car.permitZoneLetterSet() and every rppRegulationId call site
 * already treat as "no RPP permit"/"no match resolved" respectively.
 */
val MIGRATION_10_11 = Migration(10, 11) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS rpp_zone_regulation (
            objectId TEXT NOT NULL PRIMARY KEY,
            zoneLetters TEXT NOT NULL,
            days TEXT NOT NULL,
            hrsBegin INTEGER NOT NULL,
            hrsEnd INTEGER NOT NULL,
            hrLimit REAL NOT NULL,
            points TEXT NOT NULL,
            centroidLat REAL NOT NULL,
            centroidLng REAL NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("ALTER TABLE car ADD COLUMN permitZoneLetters TEXT")
    db.execSQL("ALTER TABLE parked_state ADD COLUMN rppRegulationId TEXT")
}

/**
 * v11 -> v12: adds the four reminder delivery markers to parked_state (see the comment on
 * ParkedState for what they mean). All nullable INTEGER and purely additive, so an existing
 * parked row keeps every value and gets NULL markers — "nothing delivered yet," which is at worst
 * one harmless re-delivery of a reminder that already fired before the upgrade.
 */
val MIGRATION_11_12 = Migration(11, 12) { db ->
    db.execSQL("ALTER TABLE parked_state ADD COLUMN normalDeliveredForMillis INTEGER")
    db.execSQL("ALTER TABLE parked_state ADD COLUMN urgentDeliveredForMillis INTEGER")
    db.execSQL("ALTER TABLE parked_state ADD COLUMN rppNormalDeliveredForMillis INTEGER")
    db.execSQL("ALTER TABLE parked_state ADD COLUMN rppUrgentDeliveredForMillis INTEGER")
}

/**
 * v12 -> v13: adds lastSeenSyncId to street_segment and rpp_zone_regulation, the stamp a network
 * sync leaves on every row it sees so rows that later vanish upstream can be deleted (see
 * StaleRowPruning.kt). Nullable INTEGER with no default — deliberately not NOT NULL DEFAULT 0,
 * which Room's schema validation would insist matches an @ColumnInfo(defaultValue) exactly. Existing
 * rows get NULL, meaning "not seen by any stamped sync yet": the first fully successful sync after
 * the upgrade re-stamps everything the feed still returns and deletes the rest.
 */
val MIGRATION_12_13 = Migration(12, 13) { db ->
    db.execSQL("ALTER TABLE street_segment ADD COLUMN lastSeenSyncId INTEGER")
    db.execSQL("ALTER TABLE rpp_zone_regulation ADD COLUMN lastSeenSyncId INTEGER")
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12, MIGRATION_12_13
)

/*
 * HOW TO ADD THE NEXT ONE (v6 -> v7 or beyond):
 *   1. Bump `version` in the @Database annotation on AppDatabase.
 *   2. Write the SQL that transforms the OLD table shape into the NEW one. SQLite's ALTER
 *      TABLE is limited — adding a nullable/defaulted column works directly (as above);
 *      dropping or renaming a column, or changing a column's type, needs a
 *      create-copy-drop-rename dance instead (create the new-shape table under a temp name,
 *      INSERT...SELECT the data across, DROP the old table, rename the temp table into place).
 *   3. Add a new Migration object here and append it to ALL_MIGRATIONS.
 *   4. Rebuild once so Room exports schemas/<newVersion>.json (requires the ksp
 *      `room.schemaLocation` arg in build.gradle.kts — see below if not already added).
 *      Then write an instrumented test with androidx.room:room-testing's MigrationTestHelper
 *      that opens a database at the OLD version and asserts your migration runs cleanly
 *      against it. This is the step that actually PROVES the migration works, rather than
 *      just "compiles."
 *
 * Required build.gradle.kts additions (if not already present):
 *     dependencies {
 *         androidTestImplementation("androidx.room:room-testing:2.7.2")
 *     }
 *     ksp {
 *         arg("room.schemaLocation", "$projectDir/schemas")
 *     }
 * Commit the generated schemas/ directory to version control — those JSON files are what
 * MigrationTestHelper uses to reconstruct a pre-migration database for testing.
 */