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
    val photoPath: String? = null  // local file path; takes precedence over colorHex/iconEmoji when set
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