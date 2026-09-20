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
    // Outcome of the last scheduling attempt at park time: true if a reminder alarm was set (exact
    // or the inexact fallback) or one fired immediately, false if nothing could be scheduled.
    // Written by saveParkedState after scheduling; not read anywhere yet.
    val notificationScheduled: Boolean = false,
    val rppRegulationId: String? = null, // resolved once at parking time — see RppMatcher.findConfidentRppMatch
    // Delivery markers: each stores the DEADLINE (epoch ms) its tier's reminder was last actually
    // delivered for — not the time it was delivered. Comparing a marker to the deadline being
    // (re)armed answers "has the user already been told about this exact deadline?", and a
    // changed deadline (new park, Fix schedule, roll-forward) automatically invalidates it
    // because the values no longer match. Null = never delivered. They live on this row (rather
    // than in DataStore) so they share its lifetime: re-parking REPLACEs the row and they reset.
    val normalDeliveredForMillis: Long? = null,
    val urgentDeliveredForMillis: Long? = null,
    val rppNormalDeliveredForMillis: Long? = null,
    val rppUrgentDeliveredForMillis: Long? = null
) {
    /** The deadline [kind]'s reminder was last delivered for, or null if it never was. */
    fun deliveredForMillis(kind: ReminderKind): Long? = when (kind) {
        ReminderKind.NORMAL -> normalDeliveredForMillis
        ReminderKind.URGENT -> urgentDeliveredForMillis
        ReminderKind.RPP_NORMAL -> rppNormalDeliveredForMillis
        ReminderKind.RPP_URGENT -> rppUrgentDeliveredForMillis
        ReminderKind.SWEEP_ACTIVE -> null // a one-off notice, not a scheduled tier — nothing to de-duplicate
    }
}

@Dao
interface ParkedStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ParkedState)

    @Query("SELECT * FROM parked_state WHERE carId = :carId LIMIT 1")
    suspend fun getForCar(carId: Long): ParkedState?

    @Query("SELECT * FROM parked_state")
    suspend fun getAll(): List<ParkedState>

    @Query("UPDATE parked_state SET notificationScheduled = :scheduled WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun setNotificationScheduled(carId: Long, parkedAtMillis: Long, scheduled: Boolean)

    @Query("SELECT * FROM parked_state WHERE segmentBlockSweepId = :blockSweepId")
    suspend fun getForSegment(blockSweepId: String): List<ParkedState>

    // Guarded by parkedAtMillis like the delivery markers: a recompute racing a re-park must not
    // write the old row's deadline into the new one.
    @Query("UPDATE parked_state SET nextSweepAtMillis = :nextSweepAtMillis WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun updateNextSweep(carId: Long, parkedAtMillis: Long, nextSweepAtMillis: Long?)

    @Query("DELETE FROM parked_state WHERE carId = :carId")
    suspend fun clearForCar(carId: Long)

    // One update per marker column. Every one is guarded by parkedAtMillis so a delivery that
    // races a re-park (a reminder for the OLD spot firing just as the row is replaced) matches
    // zero rows instead of writing the old deadline into the new row's marker.
    @Query("UPDATE parked_state SET normalDeliveredForMillis = :deadlineMillis WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun markNormalDelivered(carId: Long, parkedAtMillis: Long, deadlineMillis: Long)

    @Query("UPDATE parked_state SET urgentDeliveredForMillis = :deadlineMillis WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun markUrgentDelivered(carId: Long, parkedAtMillis: Long, deadlineMillis: Long)

    @Query("UPDATE parked_state SET rppNormalDeliveredForMillis = :deadlineMillis WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun markRppNormalDelivered(carId: Long, parkedAtMillis: Long, deadlineMillis: Long)

    @Query("UPDATE parked_state SET rppUrgentDeliveredForMillis = :deadlineMillis WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis")
    suspend fun markRppUrgentDelivered(carId: Long, parkedAtMillis: Long, deadlineMillis: Long)
}
