package com.example.park

import java.time.DayOfWeek
import java.time.LocalDateTime

private val METER_DAY_ABBREVIATIONS = mapOf(
    "Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY,
    "Th" to DayOfWeek.THURSDAY, "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY
)

/**
 * Parses the meter schedule feed's own day-list shorthand, e.g. "Mo,Tu,We,Th,Fr" or "Sa" or
 * "Su" — a comma-separated list of individual days. NOT the same convention as RppStatus.kt's
 * parseDaysRange ("M-F" contiguous ranges): a different feed, and a meter's full week is
 * usually expressed as several separate schedule rows (weekday/Saturday/Sunday), each with its
 * own disjoint day list, rather than one range.
 */
fun parseMeterDays(days: String): Set<DayOfWeek> =
    days.split(",").mapNotNull { METER_DAY_ABBREVIATIONS[it.trim()] }.toSet()

/**
 * Parses the meter schedule feed's own "7:00 AM" / "12:00 PM" style time strings into a
 * military-time int (e.g. 700, 1200, 1800) — the same convention militaryHourToLocalTime
 * (RppStatus.kt) already reads, so both feeds' hours can share that one conversion. Null on
 * anything that doesn't match the feed's own consistent format.
 */
fun parseMeterTimeToMilitary(time: String): Int? {
    val match = Regex("""^(\d{1,2}):(\d{2})\s*(AM|PM)$""", RegexOption.IGNORE_CASE).find(time.trim()) ?: return null
    val (hourStr, minuteStr, meridiem) = match.destructured
    var hour = hourStr.toIntOrNull() ?: return null
    val minute = minuteStr.toIntOrNull() ?: return null
    if (hour !in 1..12 || minute !in 0..59) return null
    if (meridiem.equals("AM", ignoreCase = true) && hour == 12) hour = 0
    if (meridiem.equals("PM", ignoreCase = true) && hour != 12) hour += 12
    return hour * 100 + minute
}

/**
 * Parses a "60 minutes" / "2 hours" style stay-limit string into whole minutes, or null if it
 * doesn't parse — the feed's time_limit field is free text, not a structured number.
 */
fun parseMeterTimeLimitMinutes(text: String?): Int? {
    if (text == null) return null
    val match = Regex("""(\d+)\s*(minute|hour)""", RegexOption.IGNORE_CASE).find(text) ?: return null
    val value = match.groupValues[1].toIntOrNull() ?: return null
    return if (match.groupValues[2].equals("hour", ignoreCase = true)) value * 60 else value
}

/**
 * Whether [zone] should currently count as metered for the map badge / timer prompt: either
 * it's genuinely inside its enforced day+hours window right now, or its hours are simply
 * unknown (the location-only sync fallback — see MeteredZone's doc comment for when that
 * happens) and this deliberately doesn't gate that case at all. Same reasoning
 * RppZoneRegulation.kt already applies to a missing HRLIMIT: showing a badge for a meter that
 * might not be enforced beats silently hiding one that is.
 */
fun isMeterEnforcedOrUnknown(zone: MeteredZone, now: LocalDateTime): Boolean {
    val days = zone.days
    val hrsBegin = zone.hrsBegin
    val hrsEnd = zone.hrsEnd
    if (days == null || hrsBegin == null || hrsEnd == null) return true // hours unknown — don't gate
    if (now.dayOfWeek !in parseMeterDays(days)) return false
    val windowStart = militaryHourToLocalTime(hrsBegin)
    val windowEnd = militaryHourToLocalTime(hrsEnd)
    val nowTime = now.toLocalTime()
    return !nowTime.isBefore(windowStart) && nowTime.isBefore(windowEnd)
}
