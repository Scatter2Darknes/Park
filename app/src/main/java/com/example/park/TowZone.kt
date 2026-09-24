package com.example.park

import androidx.room.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One SFMTA temporary tow-away zone, from "Enforced Temporary Tow Zones" (DataSF 6r5h-j298 — see
 * TowZoneApi.kt and docs/park-closures-spec.md §9 for what the feed actually contains).
 *
 * A zone is DAILY HOURS OVER A DATE RANGE, in San Francisco local time: e.g. 7 AM – 5 PM, Monday to
 * Friday, from Sep 1 to Sep 30. It covers the WHOLE of each listed block on BOTH sides: the feed's
 * side-of-street field is always empty and its frontage length is too coarse to place (spec §9), so
 * the conservative reading is the whole block.
 *
 * Unlike a street closure, a tow zone IS a deadline: a car still parked there when enforcement starts
 * gets towed. It feeds the tow reminder family (TowAlerts.kt).
 */
@Entity(
    tableName = "tow_zone",
    indices = [Index(value = ["endEpochDay"])]
)
data class TowZone(
    @PrimaryKey val rowId: String,   // Socrata's own row id (":id"); casenumber repeats across rows
    val caseNumber: String?,
    val permitNumber: String?,
    // Every CNN the zone covers, wrapped in commas (",5440000,5441000,") so one LIKE can match a
    // single CNN exactly — a third of rows list several blocks. See TowZoneDao.getForCnn.
    val cnns: String,
    val address: String?,
    val streetName: String?,
    val fromStreet: String?,
    val toStreet: String?,
    val startEpochDay: Long,        // first day (LocalDate.toEpochDay), SF local
    val endEpochDay: Long,          // last day, inclusive
    val startMinute: Int,           // minutes after local midnight when each day's window starts
    val endMinute: Int,             // when it ends; <= startMinute means it runs past midnight
    val allDay: Boolean,            // 24-hour enforcement on each listed day
    val daysMask: Int,              // bit (dayOfWeek.value - 1) per enforced weekday; ALL_DAYS_MASK = every day
    val daysText: String?,          // the feed's raw day text, for display
    val enteredMillis: Long?,       // when SFMTA entered the permit
    val lastSeenSyncId: Long? = null
)

const val ALL_DAYS_MASK = 0b1111111

/** Row key prefix of the fake zones DebugControlReceiver's INJECT_TOW adds. */
const val DEBUG_TOW_ROW_PREFIX = "debug-tow-"

/** ",a,b," form of [cnns] for [TowZone.cnns]. Blank entries are dropped. */
fun towCnnList(cnns: List<String>): String =
    cnns.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",", prefix = ",", postfix = ",")

@Dao
interface TowZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(zones: List<TowZone>)

    /** Zones on block [cnn] whose last day is not before [fromEpochDay]. */
    @Query("SELECT * FROM tow_zone WHERE cnns LIKE '%,' || :cnn || ',%' AND endEpochDay >= :fromEpochDay")
    suspend fun getForCnn(cnn: String, fromEpochDay: Long): List<TowZone>

    /** Time-based pruning. The caller passes yesterday, so an overnight window that began on a zone's
     *  last day (and runs into today) is kept until it's certainly over. */
    @Query("DELETE FROM tow_zone WHERE endEpochDay < :beforeEpochDay")
    suspend fun deleteEndedBefore(beforeEpochDay: Long): Int

    // Same as StreetClosureDao.deleteNotSeenSince. The debug rows are never stamped, so a real sync
    // that prunes also clears them — fine, they are throw-away test data.
    @Query("DELETE FROM tow_zone WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :syncId")
    suspend fun deleteNotSeenSince(syncId: Long): Int

    @Query("SELECT COUNT(*) FROM tow_zone")
    suspend fun count(): Int

    @Query("DELETE FROM tow_zone WHERE rowId LIKE 'debug-tow-%'")
    suspend fun deleteDebugRows(): Int
}

// --- Pure schedule logic (unit tested in TowZoneScheduleTest) ---

/** One enforcement window, SF local time. [end] is exclusive. */
data class TowWindow(val start: LocalDateTime, val end: LocalDateTime)

fun TowZone.enforcesOn(day: DayOfWeek): Boolean = daysMask and (1 shl (day.value - 1)) != 0

/** The window that STARTS on [date], or null if the zone doesn't enforce that day. */
fun TowZone.windowOn(date: LocalDate): TowWindow? {
    val epochDay = date.toEpochDay()
    if (epochDay < startEpochDay || epochDay > endEpochDay || !enforcesOn(date.dayOfWeek)) return null
    if (allDay) return TowWindow(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
    val start = date.atTime(LocalTime.ofSecondOfDay(startMinute * 60L))
    val end = when {
        // "11:59 PM" is how the feed writes "until midnight"; reading it literally would leave a
        // one-minute gap that a later check could mistake for "not enforced".
        endMinute >= LAST_MINUTE_OF_DAY -> date.plusDays(1).atStartOfDay()
        endMinute > startMinute -> date.atTime(LocalTime.ofSecondOfDay(endMinute * 60L))
        // End at or before start: the window runs past midnight ("7:00 PM – 7:00 AM"), or for a full
        // 24 hours when the two are equal ("5:00 AM – 5:00 AM").
        else -> date.plusDays(1).atTime(LocalTime.ofSecondOfDay(endMinute * 60L))
    }
    return TowWindow(start, end)
}

private const val LAST_MINUTE_OF_DAY = 23 * 60 + 59

/** The first window starting strictly after [after], or null if none is left. */
fun TowZone.nextWindowStartingAfter(after: LocalDateTime): TowWindow? {
    var date = maxOf(after.toLocalDate(), LocalDate.ofEpochDay(startEpochDay))
    val last = LocalDate.ofEpochDay(endEpochDay)
    while (!date.isAfter(last)) {
        windowOn(date)?.takeIf { it.start.isAfter(after) }?.let { return it }
        date = date.plusDays(1)
    }
    return null
}

/** The window in force at [at] (start inclusive, end exclusive), or null. Checks the previous day too,
 *  for a window that started before midnight. */
fun TowZone.windowContaining(at: LocalDateTime): TowWindow? =
    listOf(at.toLocalDate().minusDays(1), at.toLocalDate())
        .mapNotNull { windowOn(it) }
        .firstOrNull { !at.isBefore(it.start) && at.isBefore(it.end) }

/** The zone's very first window, or null if it never enforces (no listed weekday inside its dates). */
fun TowZone.firstWindow(): TowWindow? =
    nextWindowStartingAfter(LocalDate.ofEpochDay(startEpochDay).atStartOfDay().minusNanos(1))

/**
 * The feed's day text as a [daysMask]. Handles what the feed actually uses (checked 2026-09-24 over
 * every row entered since 2025): "Monday - Friday", ranges that wrap past Sunday ("Friday - Wednesday"),
 * comma lists ("Monday,Tuesday", "Thursday, Friday") and single days. Blank or anything it can't read
 * means EVERY day: over-warning costs a notification, under-warning costs a tow.
 */
fun parseTowDays(text: String?): Int {
    if (text.isNullOrBlank()) return ALL_DAYS_MASK
    var mask = 0
    for (part in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
        val range = part.split(Regex("\\s*(?:-|–|\\bto\\b|\\bthrough\\b|\\bthru\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }.filter { it.isNotEmpty() }
        when (range.size) {
            1 -> mask = mask or dayBit(parseDayName(range[0]) ?: return ALL_DAYS_MASK)
            2 -> {
                val from = parseDayName(range[0]) ?: return ALL_DAYS_MASK
                val to = parseDayName(range[1]) ?: return ALL_DAYS_MASK
                var day = from
                while (true) {
                    mask = mask or dayBit(day)
                    if (day == to) break
                    day = day.plus(1) // DayOfWeek.plus wraps Sunday -> Monday
                }
            }
            else -> return ALL_DAYS_MASK
        }
    }
    return if (mask == 0) ALL_DAYS_MASK else mask
}

private fun dayBit(day: DayOfWeek) = 1 shl (day.value - 1)

private fun parseDayName(text: String): DayOfWeek? {
    val key = text.trim().lowercase().take(3)
    return DayOfWeek.values().firstOrNull { it.name.lowercase().startsWith(key) }?.takeIf { key.length == 3 }
}

/** "7:00 AM" / "12:00 AM" / "11:59 PM" -> minutes after midnight, or null if unreadable. */
fun parseTowClockTime(text: String?): Int? {
    val match = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*([AaPp])\\.?[Mm]\\.?\\s*$").find(text ?: return null) ?: return null
    val hour12 = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour12 !in 1..12 || minute !in 0..59) return null
    val pm = match.groupValues[3].equals("p", ignoreCase = true)
    val hour = (hour12 % 12) + if (pm) 12 else 0
    return hour * 60 + minute
}
