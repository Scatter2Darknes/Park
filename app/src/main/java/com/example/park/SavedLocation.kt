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
    // Off the street entirely (a garage, a private lot): no street parking rule can apply, so tow-zone
    // checks AND the RPP non-permit time limit are skipped for a car parked here. Street-closure alerts
    // still apply (a closed street can block a garage exit). Separate from isSafeFromSweeping on purpose:
    // an on-street spot can be safe from sweeping and still get a tow zone or an RPP limit. Read as
    // `== true`, like isSafeFromSweeping.
    val isOffStreet: Boolean? = null
) {
    fun toLatLng(): LatLng = LatLng(lat, lng)
}

/**
 * What kind of spot a saved location is, as ONE choice in LocationStyleDialog. The two stored flags
 * (isSafeFromSweeping, isOffStreet) only ever make three meaningful combinations — "off the street but
 * swept" can't happen — so the dialog offers these three instead of a switch that reveals a second one.
 * An enum class is a fixed set of named values; each one here also knows what it means for the flags.
 */
enum class SpotKind(val title: String, val description: String) {
    ON_STREET(
        "On the street",
        "All checks: street cleaning, permit (RPP) limit, tow zones and closures."
    ),
    NEVER_SWEPT(
        "On the street, never swept",
        "Skips street cleaning. Still checks the permit (RPP) limit, tow zones and closures."
    ),
    OFF_STREET(
        "Off the street",
        "A garage, driveway or lot. Only checks street closures, since one can block your exit."
    );

    val isSafeFromSweeping: Boolean get() = this != ON_STREET
    val isOffStreet: Boolean get() = this == OFF_STREET
}

/** The location's current [SpotKind]. Off-street wins: off the street is never swept either. */
val SavedLocation.spotKind: SpotKind
    get() = when {
        isOffStreet == true -> SpotKind.OFF_STREET
        isSafeFromSweeping == true -> SpotKind.NEVER_SWEPT
        else -> SpotKind.ON_STREET
    }

/**
 * Whether [parked] is at a saved location marked off the street. Checked at USE time (arming, the
 * banner) rather than baked into the parked row, so switching the flag on or off applies to a car
 * already parked there without re-saving it — a re-save would restart its RPP clock.
 */
suspend fun isParkedOffStreet(context: android.content.Context, parked: ParkedState): Boolean {
    val locationId = parked.parkedViaSafeLocationId ?: return false
    return AppDatabase.getInstance(context).savedLocationDao().getAll().firstOrNull { it.id == locationId }?.isOffStreet == true
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