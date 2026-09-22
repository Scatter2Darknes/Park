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

/**
 * v13 -> v14: adds limitAssumed to rpp_zone_regulation — true when the row's hrLimit is a stand-in because the
 * feed left the limit blank on a "Time Limited" row (see RPP_ASSUMED_LIMIT_HOURS). Nullable INTEGER with no
 * default, like the earlier additive columns. Existing rows get NULL (= "not assumed"), which is right for
 * every row already stored; the next RPP sync re-inserts rows with the new parse.
 */
val MIGRATION_13_14 = Migration(13, 14) { db ->
    db.execSQL("ALTER TABLE rpp_zone_regulation ADD COLUMN limitAssumed INTEGER")
}

/**
 * v14 -> v15: adds an index on street_segment (cnn, cnnRightLeft), the key that identifies one physical curb, so
 * CurbSchedule's "all rows for this curb" lookup doesn't scan the whole ~38,000-row table each time. An index changes
 * no data. The name is the one Room generates for that column pair, which its schema validation checks.
 */
val MIGRATION_14_15 = Migration(14, 15) { db ->
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_street_segment_cnn_cnnRightLeft` ON `street_segment` (`cnn`, `cnnRightLeft`)")
}

/**
 * v15 -> v16: adds isSafeFromSweeping to saved_location — a location explicitly marked (never
 * inferred) as having no street-cleaning risk, e.g. a garage or driveway (see
 * saveUnmanagedParkedState and the safe parking flow it's shared with). Nullable INTEGER with
 * no default, same reasoning as every other additive column here — see MIGRATION_12_13's
 * comment for the full explanation of the @ColumnInfo(defaultValue) pitfall this sidesteps.
 * Existing saved locations get NULL, which SavedLocation reads as "not marked safe" — exactly
 * right, since this is opt-in per the feature's own design decision.
 */
val MIGRATION_15_16 = Migration(15, 16) { db ->
    db.execSQL("ALTER TABLE saved_location ADD COLUMN isSafeFromSweeping INTEGER")
}

/**
 * v16 -> v17: adds parkedViaSafeLocationId to parked_state — set only when a row was saved
 * unmanaged because it matched a safe-tagged SavedLocation (see ParkedState's doc comment on
 * the field, and SavedLocationRecompute.kt). Nullable INTEGER with no default, same reasoning
 * as every other additive column here. Existing rows get NULL, which is correct either way: an
 * existing real managed park was never "via" a safe location, and an existing unmanaged row
 * from before this column existed predates safe-location tracking entirely, so it's treated as
 * a manual (NoStreetNearby) unmanaged park rather than retroactively guessed at.
 */
val MIGRATION_16_17 = Migration(16, 17) { db ->
    db.execSQL("ALTER TABLE parked_state ADD COLUMN parkedViaSafeLocationId INTEGER")
}

/**
 * v17 -> v18: adds metered_zone (SFMTA meter locations joined with DataSF's meter operating
 * schedules — see MeteredZone.kt and MeteredZoneApi.kt). Brand-new table, nothing to migrate
 * data-wise. Numbered 17->18 (not 15->16) because this branch was rebased onto the
 * saved-location-safe-flag branch's tip after the two independently reached "v16" on their own
 * — two feature branches bumping the schema from the same base collide the instant both builds
 * land on one device/app data, so from here on this needs to stack on whichever branch is
 * ahead rather than assuming main's version number.
 */
val MIGRATION_17_18 = Migration(17, 18) { db ->
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS metered_zone (
            id TEXT NOT NULL PRIMARY KEY,
            postId TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL,
            streetName TEXT,
            days TEXT,
            hrsBegin INTEGER,
            hrsEnd INTEGER,
            timeLimitMinutes INTEGER,
            lastSeenSyncId INTEGER
        )
        """.trimIndent()
    )
}

/**
 * v18 -> v19: adds meterTimerAtMillis to parked_state — the deadline of a currently-scheduled
 * manual meter timer (see MeterTimer.kt), persisted so the map's priority banner and the
 * widget can show it alongside the sweep/RPP deadline (see soonestDeadline() in
 * CarActions.kt). Nullable INTEGER with no default, same reasoning as every other additive
 * column here.
 */
val MIGRATION_18_19 = Migration(18, 19) { db ->
    db.execSQL("ALTER TABLE parked_state ADD COLUMN meterTimerAtMillis INTEGER")
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
    MIGRATION_17_18, MIGRATION_18_19
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