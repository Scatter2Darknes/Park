package com.example.park

import androidx.room.*

@Entity(
    tableName = "parked_state",
    indices = [Index(value = ["carId"], unique = true)]
)
data class ParkedState(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val segmentBlockSweepId: String?,
    val sideConfirmed: Boolean,
    val parkedLat: Double,
    val parkedLng: Double,
    val exactPinLat: Double? = null,
    val exactPinLng: Double? = null,
    val parkedAtMillis: Long,
    val nextSweepAtMillis: Long?,
    val notificationScheduled: Boolean = false,
    val rppRegulationId: String? = null // resolved once at parking time — see RppMatcher.findConfidentRppMatch
)

@Dao
interface ParkedStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ParkedState)

    @Query("SELECT * FROM parked_state WHERE carId = :carId LIMIT 1")
    suspend fun getForCar(carId: Long): ParkedState?

    @Query("SELECT * FROM parked_state")
    suspend fun getAll(): List<ParkedState>

    @Query("DELETE FROM parked_state WHERE carId = :carId")
    suspend fun clearForCar(carId: Long)
}