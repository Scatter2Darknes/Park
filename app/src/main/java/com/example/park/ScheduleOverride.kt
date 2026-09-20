package com.example.park

import androidx.room.*

/**
 * A manual correction to one block's sweep schedule, for the known DataSF-lags-signage case
 * (e.g. a block posted "every Monday" but the database still says "2nd & 4th Monday").
 * Applied transparently at the query level — see StreetSegmentDao.getNearby/getById, which
 * LEFT JOIN + COALESCE against this table — so every consumer of a StreetSegment (map
 * coloring, the detail sheet, notification scheduling) automatically respects an override
 * with no per-call-site changes needed.
 */
@Entity(tableName = "schedule_override")
data class ScheduleOverride(
    @PrimaryKey val blockSweepId: String,
    val fullName: String,
    val week1: Boolean,
    val week2: Boolean,
    val week3: Boolean,
    val week4: Boolean,
    val week5: Boolean,
    val fromHour: Int,
    val toHour: Int,
    val notes: String? = null
)

@Dao
interface ScheduleOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ScheduleOverride)

    @Query("SELECT * FROM schedule_override WHERE blockSweepId = :id LIMIT 1")
    suspend fun getById(id: String): ScheduleOverride?

    @Query("DELETE FROM schedule_override WHERE blockSweepId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM schedule_override")
    suspend fun getAll(): List<ScheduleOverride>
}