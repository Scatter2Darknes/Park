package com.example.park

import androidx.room.*

/**
 * One temporary street closure, from SFMTA's "Temporary Street Closures" feed (DataSF 8x25-yybr —
 * see StreetClosureApi.kt and docs/park-closures-spec.md §9 for what the feed actually contains).
 *
 * One row = one block (one [cnn]) closed for one time window. A recurring closure (a Shared Space
 * open every day, a weekly market) arrives already expanded into one row per occurrence, so there
 * is no recurrence rule to store. The feed has no side-of-street information: a closure closes the
 * whole block.
 *
 * A closure is NOT a parking deadline. It can mean "you may not be able to drive out until X"
 * (see ClosureImpact), never "move or get towed".
 */
@Entity(
    tableName = "street_closure",
    indices = [Index(value = ["cnn"]), Index(value = ["endMillis"])]
)
@TypeConverters(LatLngListConverter::class)
data class StreetClosure(
    @PrimaryKey val objectId: String,
    val caseNum: String?,
    val caseName: String?,
    val type: String?,           // "Roadway Shared Spaces", "Special Event", "Special Traffic Permit"
    val cnn: String,             // same street-centerline id as StreetSegment.cnn
    val street: String?,
    val fromStreet: String?,
    val toStreet: String?,
    // WZDx VehicleImpact: "all-lanes-closed", "some-lanes-closed", "all-lanes-open". Null = the row
    // didn't say, which is treated as a full closure (see isFullClosure) — the conservative reading.
    val vehicleImpact: String?,
    val startMillis: Long,       // epoch ms, from the feed's start_utc
    val endMillis: Long,         // epoch ms, from end_utc. The window is treated as continuous start -> end.
    val points: List<LatLng>,    // the closed block's centerline (the feed's LineString)
    val centroidLat: Double,
    val centroidLng: Double,
    // Id of the last network sync that saw this row (see StaleRowPruning.kt). Null = never stamped.
    val lastSeenSyncId: Long? = null
)

/** Whether this closure shuts the whole roadway. Anything but an explicit partial/open value counts as full. */
val StreetClosure.isFullClosure: Boolean
    get() = vehicleImpact != "some-lanes-closed" && vehicleImpact != "all-lanes-open"

@Dao
interface StreetClosureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(closures: List<StreetClosure>)

    /** Closures not yet over whose centroid is inside the box. The box must allow for half a block
     *  of line length on top of the search radius (see findClosuresNear). */
    @Query("""
        SELECT * FROM street_closure
        WHERE endMillis > :nowMillis
        AND centroidLat BETWEEN :minLat AND :maxLat
        AND centroidLng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getNearby(nowMillis: Long, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<StreetClosure>

    @Query("SELECT * FROM street_closure WHERE objectId = :objectId LIMIT 1")
    suspend fun getById(objectId: String): StreetClosure?

    @Query("SELECT * FROM street_closure WHERE cnn = :cnn AND endMillis > :nowMillis")
    suspend fun getForCnn(cnn: String, nowMillis: Long): List<StreetClosure>

    /** Everything not yet over that starts before [untilMillis] — for the map layer's horizon. */
    @Query("SELECT * FROM street_closure WHERE endMillis > :nowMillis AND startMillis <= :untilMillis")
    suspend fun getActiveOrStartingBefore(nowMillis: Long, untilMillis: Long): List<StreetClosure>

    // Same as RppZoneRegulationDao.deleteNotSeenSince, for closures.
    @Query("DELETE FROM street_closure WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    /** Time-based pruning: a closure that has ended can never matter again, so this needs no guard. */
    @Query("DELETE FROM street_closure WHERE endMillis <= :nowMillis")
    suspend fun deleteEndedBy(nowMillis: Long): Int

    @Query("SELECT COUNT(*) FROM street_closure")
    suspend fun count(): Int

    /** Removes the fake closures DebugControlReceiver's INJECT_CLOSURE adds (debug builds only use this). */
    @Query("DELETE FROM street_closure WHERE objectId LIKE 'debug-%'")
    suspend fun deleteDebugRows(): Int
}
