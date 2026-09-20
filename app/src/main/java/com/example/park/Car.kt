package com.example.park

import androidx.room.*

@Entity(tableName = "car")
data class Car(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val bluetoothDeviceAddress: String? = null, // for future auto-detect association
    val colorHex: String? = null,   // null = use DEFAULT_CAR_COLOR_HEX (see CarStyle.kt)
    val iconEmoji: String? = null,  // null = use DEFAULT_CAR_ICON (see CarStyle.kt)
    val photoPath: String? = null,  // local file path; takes precedence over colorHex/iconEmoji when set — see CarAvatar in CarStyle.kt
    val widgetTextColorHex: String? = null, // color of the text drawn over the home screen widget — see CarStyle.kt
    val permitZoneLetters: String? = null // comma-joined RPP zone letters this car holds a permit for, e.g. "A,Q" — see permitZoneLetterSet()
)

/** [Car.permitZoneLetters], parsed and normalized — null/blank means no RPP permit at all. */
fun Car.permitZoneLetterSet(): Set<String> =
    (permitZoneLetters ?: "").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

@Dao
interface CarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(car: Car): Long

    @Update
    suspend fun update(car: Car)

    @Delete
    suspend fun delete(car: Car)

    @Query("SELECT * FROM car ORDER BY id")
    suspend fun getAll(): List<Car>

    @Query("SELECT * FROM car WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): Car?

    @Query("SELECT COUNT(*) FROM car")
    suspend fun count(): Int

    @Query("UPDATE car SET isDefault = 0")
    suspend fun clearAllDefaults()

    @Query("UPDATE car SET isDefault = 1 WHERE id = :carId")
    suspend fun setDefaultById(carId: Long)

    @Query("UPDATE car SET isDefault = 0 WHERE id = :carId")
    suspend fun clearDefaultById(carId: Long)

    @Transaction
    suspend fun setDefault(carId: Long) {
        clearAllDefaults()
        setDefaultById(carId)
    }
}