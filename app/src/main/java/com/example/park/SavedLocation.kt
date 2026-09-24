package com.example.park

import androidx.room.*

@Entity(tableName = "saved_location")
data class SavedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val colorHex: String? = null,  // null = use DEFAULT_LOCATION_COLOR_HEX (see LocationStyle.kt)
    val iconEmoji: String? = null, // null = use DEFAULT_LOCATION_ICON (see LocationStyle.kt)
    val photoPath: String? = null, // local file path; takes precedence over colorHex/iconEmoji when set
    // Explicitly set in Settings (LocationStyleDialog), never inferred from a parking event —
    // a location must be deliberately marked safe before it can auto-apply. Nullable rather than
    // NOT NULL DEFAULT false (see MIGRATION_15_16's comment for why); null and false both mean
    // "not marked safe," so read this as `== true`, never as a plain `if (isSafeFromSweeping)`.
    val isSafeFromSweeping: Boolean? = null,
    // Off the street entirely (a garage, a private lot): temporary tow zones can't apply, so tow
    // checks are skipped for a car parked here. Street-closure alerts still apply (a closed street
    // can block a garage exit). Separate from isSafeFromSweeping on purpose: an on-street spot can be
    // safe from sweeping and still get a tow zone. Read as `== true`, like isSafeFromSweeping.
    val isOffStreet: Boolean? = null
) {
    fun toLatLng(): LatLng = LatLng(lat, lng)
}

@Dao
interface SavedLocationDao {
    @Insert
    suspend fun insert(location: SavedLocation): Long

    @Update
    suspend fun update(location: SavedLocation)

    @Delete
    suspend fun delete(location: SavedLocation)

    @Query("SELECT * FROM saved_location ORDER BY name")
    suspend fun getAll(): List<SavedLocation>
}