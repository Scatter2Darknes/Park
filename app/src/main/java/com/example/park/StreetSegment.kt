package com.example.park

import androidx.room.*
import org.json.JSONArray

@Entity(tableName = "street_segment")
@TypeConverters(LatLngListConverter::class)
data class StreetSegment(
    @PrimaryKey val blockSweepId: String,
    val cnn: String,
    val corridor: String,
    val limits: String,
    val cnnRightLeft: String,
    val blockSide: String,
    val fullName: String,
    val fromHour: Int,
    val toHour: Int,
    val week1: Boolean,
    val week2: Boolean,
    val week3: Boolean,
    val week4: Boolean,
    val week5: Boolean,
    val holidays: Boolean,
    val points: List<LatLng>,
    val centroidLat: Double,
    val centroidLng: Double,
    // Id of the last network sync that saw this row (see StaleRowPruning.kt). Null = never stamped
    // (a row from before this column existed). Lets a sync delete rows that have vanished upstream.
    val lastSeenSyncId: Long? = null
)

class LatLngListConverter {
    @TypeConverter
    fun fromList(points: List<LatLng>): String {
        val array = JSONArray()
        points.forEach {
            val pair = JSONArray()
            pair.put(it.lat); pair.put(it.lng)
            array.put(pair)
        }
        return array.toString()
    }

    @TypeConverter
    fun toList(data: String): List<LatLng> {
        val array = JSONArray(data)
        val result = mutableListOf<LatLng>()
        for (i in 0 until array.length()) {
            val pair = array.getJSONArray(i)
            result.add(LatLng(pair.getDouble(0), pair.getDouble(1)))
        }
        return result
    }
}

@Dao
interface StreetSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<StreetSegment>)

    @Query("SELECT * FROM street_segment")
    suspend fun getAll(): List<StreetSegment>

    // Deletes every row the network sync with id [syncId] did not see (never stamped, or last seen by
    // an earlier sync). Only ever called after a FULLY successful sync — see StaleRowPruning.kt.
    @Query("DELETE FROM street_segment WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM street_segment")
    suspend fun count(): Int

    // LEFT JOINs against schedule_override and COALESCEs the schedule-relevant columns so a
    // manual correction (see ScheduleOverride.kt) is applied transparently to every segment
    // this returns — map coloring, the detail sheet, and notification scheduling all get
    // override-aware data automatically, with no changes needed at those call sites. The
    // aliased column names match StreetSegment's fields exactly, so Room maps the result the
    // same way it would a plain SELECT *.
    @Query("""
        SELECT
            s.blockSweepId, s.cnn, s.corridor, s.limits, s.cnnRightLeft, s.blockSide,
            COALESCE(o.fullName, s.fullName) AS fullName,
            COALESCE(o.fromHour, s.fromHour) AS fromHour,
            COALESCE(o.toHour, s.toHour) AS toHour,
            COALESCE(o.week1, s.week1) AS week1,
            COALESCE(o.week2, s.week2) AS week2,
            COALESCE(o.week3, s.week3) AS week3,
            COALESCE(o.week4, s.week4) AS week4,
            COALESCE(o.week5, s.week5) AS week5,
            s.holidays, s.points, s.centroidLat, s.centroidLng, s.lastSeenSyncId
        FROM street_segment s
        LEFT JOIN schedule_override o ON s.blockSweepId = o.blockSweepId
        WHERE s.centroidLat BETWEEN :minLat AND :maxLat
        AND s.centroidLng BETWEEN :minLng AND :maxLng
    """)
    suspend fun getNearby(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<StreetSegment>

    @Query("""
        SELECT
            s.blockSweepId, s.cnn, s.corridor, s.limits, s.cnnRightLeft, s.blockSide,
            COALESCE(o.fullName, s.fullName) AS fullName,
            COALESCE(o.fromHour, s.fromHour) AS fromHour,
            COALESCE(o.toHour, s.toHour) AS toHour,
            COALESCE(o.week1, s.week1) AS week1,
            COALESCE(o.week2, s.week2) AS week2,
            COALESCE(o.week3, s.week3) AS week3,
            COALESCE(o.week4, s.week4) AS week4,
            COALESCE(o.week5, s.week5) AS week5,
            s.holidays, s.points, s.centroidLat, s.centroidLng, s.lastSeenSyncId
        FROM street_segment s
        LEFT JOIN schedule_override o ON s.blockSweepId = o.blockSweepId
        WHERE s.blockSweepId = :id
        LIMIT 1
    """)
    suspend fun getById(id: String): StreetSegment?
}