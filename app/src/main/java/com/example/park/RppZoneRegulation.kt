package com.example.park

import androidx.room.*

/**
 * One blockface's Residential Parking Permit regulation, from SFMTA's "Parking regulations
 * (except non-metered color curb)" feed (DataSF hi6h-neyh, fetched via SFMTA's own ArcGIS
 * mirror — see RppDataApi.kt). Only rows with a non-blank RPPAREA are kept; other regulation
 * types in that same feed (no-overnight, oversized-vehicle, government-permit, ...) aren't
 * modeled here since this app only needs the RPP time-limit case.
 */
@Entity(tableName = "rpp_zone_regulation")
@TypeConverters(LatLngListConverter::class)
data class RppZoneRegulation(
    @PrimaryKey val objectId: String,
    val zoneLetters: String, // comma-joined, e.g. "A" or "A,Q" for a zone-boundary block — see zoneLetterSet()
    val days: String,        // SFMTA's own shorthand, e.g. "M-F", "M-Sa", "M-Su" — see parseDaysRange()
    val hrsBegin: Int,       // military time, e.g. 800 = 8:00am
    val hrsEnd: Int,         // military time, e.g. 1800 = 6:00pm
    val hrLimit: Float,      // hours a non-permit-holding vehicle may park before this counts against it
    // true when hrLimit is a stand-in (RPP_ASSUMED_LIMIT_HOURS) because the feed left the limit blank on a
    // "Time Limited" row. Null on every other row (and on rows synced before this column existed).
    val limitAssumed: Boolean? = null,
    val points: List<LatLng>,
    val centroidLat: Double,
    val centroidLng: Double,
    // Id of the last network sync that saw this row (see StaleRowPruning.kt). Null = never stamped
    // (a row from before this column existed). Lets a sync delete rows that have vanished upstream.
    val lastSeenSyncId: Long? = null
)

/** [RppZoneRegulation.zoneLetters], parsed and normalized. */
fun RppZoneRegulation.zoneLetterSet(): Set<String> =
    zoneLetters.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

@Dao
interface RppZoneRegulationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(regulations: List<RppZoneRegulation>)

    @Query("""
        SELECT * FROM rpp_zone_regulation
        WHERE centroidLat BETWEEN :minLat AND :maxLat
        AND centroidLng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getNearby(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<RppZoneRegulation>

    @Query("SELECT * FROM rpp_zone_regulation WHERE objectId = :id LIMIT 1")
    suspend fun getById(id: String): RppZoneRegulation?

    // Same as StreetSegmentDao.deleteNotSeenSince, for RPP blockfaces.
    @Query("DELETE FROM rpp_zone_regulation WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM rpp_zone_regulation")
    suspend fun count(): Int

    @Query("SELECT DISTINCT zoneLetters FROM rpp_zone_regulation")
    suspend fun getAllZoneLetterGroups(): List<String>
}

/** Every distinct RPP zone letter present in the synced data, sorted — for the permit-zone
 *  picker in car settings. Derived from the synced feed itself rather than a hardcoded list of
 *  SF's zone letters, so it never drifts out of date with what's actually posted. */
suspend fun loadDistinctRppZoneLetters(context: android.content.Context): List<String> {
    val groups = AppDatabase.getInstance(context).rppZoneRegulationDao().getAllZoneLetterGroups()
    return groups.flatMap { it.split(",") }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct().sorted()
}
