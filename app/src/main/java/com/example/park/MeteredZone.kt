package com.example.park

import androidx.room.*

/**
 * One metered-parking schedule row, from SFMTA's meter feeds — modeled closely on
 * RppZoneRegulation.kt, but meters are point features (one lat/lng per meter post), not
 * blockface polylines, and — unlike RPP, which has everything in a single feed — meter
 * locations and meter operating hours are TWO separate upstream datasets joined here by
 * [postId] (see MeteredZoneApi.kt):
 *  - Locations: SFMTA's ArcGIS mirror, "MTA.meters" layer (the same service RppDataApi.kt
 *    already trusts, so no cert-chain workaround needed for this half).
 *  - Operating schedules: DataSF's own Socrata API (data.sf.gov/resource/6cqg-dxku.json) —
 *    this dataset isn't mirrored on the ArcGIS service. docs/ReliabilityPlan.md's T0 section
 *    flags data.sf.gov as having a cert chain that's failed before (from curl/possibly
 *    Android's stricter TLS validation, even where a browser tolerates it) — see
 *    MeteredZoneApi.kt's fetch for how a failure here degrades instead of losing the feature
 *    entirely: [days]/[hrsBegin]/[hrsEnd]/[timeLimitMinutes] all null means "we know this meter
 *    exists here, but not its hours" (location-only fallback), never "it's definitely metered
 *    right now" — see isMeterEnforcedOrUnknown in MeterStatus.kt for exactly how that's read.
 *
 * One meter post can have several schedule rows (different day groups, e.g. weekday vs
 * Saturday vs Sunday each with their own hours/limit — same reason StreetSegment has multiple
 * rows per physical curb), so this is NOT one row per meter; [id] is postId+priority, matching
 * the schedule feed's own key for one (post, day-group) pair.
 */
@Entity(tableName = "metered_zone")
data class MeteredZone(
    @PrimaryKey val id: String, // "$postId#$priority", or "$postId#location" for the no-schedule fallback row
    val postId: String,
    val lat: Double,
    val lng: Double,
    val streetName: String?,
    val days: String?,       // comma-shorthand e.g. "Mo,Tu,We,Th,Fr" (see parseMeterDays) — null = hours unknown
    val hrsBegin: Int?,       // military time; null alongside days
    val hrsEnd: Int?,
    val timeLimitMinutes: Int?, // posted stay limit while enforced; null if the feed didn't parse
    // Id of the last network sync that saw this row (see StaleRowPruning.kt). Null = never stamped.
    val lastSeenSyncId: Long? = null
)

@Dao
interface MeteredZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(zones: List<MeteredZone>)

    @Query("""
        SELECT * FROM metered_zone
        WHERE lat BETWEEN :minLat AND :maxLat
        AND lng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getNearby(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<MeteredZone>

    // Same as RppZoneRegulationDao.deleteNotSeenSince, for meters.
    @Query("DELETE FROM metered_zone WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM metered_zone")
    suspend fun count(): Int
}
