package com.example.park

import androidx.room.*

/**
 * One Public Works temporary-occupancy permit on one block, from the city's street-use permit data (DataSF
 * b6tj-gt35 — see StreetUsePermitApi.kt and docs/park-sources-refactor-spec.md "Part A findings").
 *
 * These are the short-notice permits that post temporary no-parking signs: moving vans, parking/staging,
 * special events, tree work. One permit often covers several blocks; the feed has one row per block, and so
 * does this table. The feed gives a date range with a start time on the first day and an end time on the last
 * ("Sep 28 8:00 AM – Oct 3 5:00 PM") but no reliable daily hours, so a permit is only ever a "check the
 * signs" warning, never a move-by deadline.
 */
@Entity(
    tableName = "street_use_permit",
    indices = [Index(value = ["cnn"]), Index(value = ["endMillis"])]
)
data class StreetUsePermit(
    @PrimaryKey val rowKey: String,  // "<permitNumber>_<cnn>", the feed's own unique_identifier shape
    val permitNumber: String,
    val cnn: String,                 // same street-centerline id as StreetSegment.cnn
    val streetName: String?,
    val crossStreet1: String?,
    val crossStreet2: String?,
    val purpose: String?,
    val status: String?,
    val startMillis: Long,           // epoch ms: the permit's start (SF local)
    val endMillis: Long,             // epoch ms, exclusive: its end; a date-only end means the end of that day
    val lastSeenSyncId: Long? = null
)

@Dao
interface StreetUsePermitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(permits: List<StreetUsePermit>)

    /** Permits on block [cnn] that aren't over at [nowMillis]. */
    @Query("SELECT * FROM street_use_permit WHERE cnn = :cnn AND endMillis > :nowMillis")
    suspend fun getForCnn(cnn: String, nowMillis: Long): List<StreetUsePermit>

    /** Time-based pruning: a permit that has ended can never matter again. */
    @Query("DELETE FROM street_use_permit WHERE endMillis <= :nowMillis")
    suspend fun deleteEndedBy(nowMillis: Long): Int

    // Same as TowZoneDao.deleteNotSeenSince (see StaleRowPruning.kt).
    @Query("DELETE FROM street_use_permit WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM street_use_permit")
    suspend fun count(): Int
}
