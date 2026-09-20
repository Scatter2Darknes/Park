package com.example.park

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

private val RPP_DAY_ORDER = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
)
private val RPP_DAY_ABBREVIATIONS = mapOf(
    "M" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "W" to DayOfWeek.WEDNESDAY,
    "Th" to DayOfWeek.THURSDAY, "F" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY
)
private val RPP_DAY_DISPLAY_NAMES = mapOf(
    "M" to "Mon", "Tu" to "Tue", "W" to "Wed", "Th" to "Thu", "F" to "Fri", "Sa" to "Sat", "Su" to "Sun"
)

/**
 * Parses SFMTA's "M-F"/"M-Sa"/"M-Su"-style day range (the only forms seen in the live RPP feed)
 * into the set of days it covers. Handles a range wrapping past Sunday (e.g. "Sa-Tu") for
 * robustness, though the current feed never actually produces one.
 */
fun parseDaysRange(days: String): Set<DayOfWeek> {
    val parts = days.trim().split("-")
    if (parts.size != 2) return emptySet()
    val start = RPP_DAY_ABBREVIATIONS[parts[0].trim()] ?: return emptySet()
    val end = RPP_DAY_ABBREVIATIONS[parts[1].trim()] ?: return emptySet()
    val startIdx = RPP_DAY_ORDER.indexOf(start)
    val endIdx = RPP_DAY_ORDER.indexOf(end)
    return if (startIdx <= endIdx) {
        RPP_DAY_ORDER.subList(startIdx, endIdx + 1).toSet()
    } else {
        (RPP_DAY_ORDER.subList(startIdx, RPP_DAY_ORDER.size) + RPP_DAY_ORDER.subList(0, endIdx + 1)).toSet()
    }
}

/** Same day range, formatted for display, e.g. "M-Sa" -> "Mon–Sat". Falls back to the raw
 *  string if it's not in the recognized "X-Y" shorthand. */
fun formatRppDays(days: String): String {
    val parts = days.trim().split("-")
    if (parts.size != 2) return days
    val start = RPP_DAY_DISPLAY_NAMES[parts[0].trim()] ?: return days
    val end = RPP_DAY_DISPLAY_NAMES[parts[1].trim()] ?: return days
    return "$start–$end"
}

/** Converts a military-time int (e.g. 800, 1830) to a LocalTime. Every value seen in the live
 *  feed is on the hour, but this handles a non-zero minute component defensively. */
fun militaryHourToLocalTime(military: Int): LocalTime {
    val clamped = military.coerceIn(0, 2359)
    val hour = (clamped / 100).coerceIn(0, 23)
    val minute = (clamped % 100).coerceIn(0, 59)
    return LocalTime.of(hour, minute)
}

/**
 * Milliseconds until [regulation]'s non-permit restriction lifts, if it's in effect right now
 * (correct day AND inside its hours window) — null otherwise. For the on-map zone label, which
 * should only show while a block is actually under an active RPP restriction (per the request
 * this was built for: the label disappearing outside those hours rather than looking like a
 * permanent, ever-present marker regardless of whether it currently means anything).
 */
fun activeRppWindowEndMillis(regulation: RppZoneRegulation, now: LocalDateTime): Long? {
    if (now.dayOfWeek !in parseDaysRange(regulation.days)) return null
    if (SfHolidayCalendar.isRppSuspended(now.toLocalDate())) return null // not enforced today, so no zone label either
    val windowStart = militaryHourToLocalTime(regulation.hrsBegin)
    val windowEnd = militaryHourToLocalTime(regulation.hrsEnd)
    val nowTime = now.toLocalTime()
    if (nowTime.isBefore(windowStart) || !nowTime.isBefore(windowEnd)) return null
    return Duration.between(now, now.toLocalDate().atTime(windowEnd)).toMillis()
}

data class RppWarning(
    val zoneLetters: Set<String>,
    val hrLimitHours: Float,
    val moveByDateTime: LocalDateTime
)

/**
 * The next future moment [car] would exceed [regulation]'s non-permit time limit, having been
 * parked (at this spot) since [parkedSince], searching forward from [from]. Null means no
 * deadline ever applies — [car] already holds a permit for one of the zone's posted letters (a
 * boundary block can post more than one accepted letter; holding any one of them exempts you
 * from all of them, so "any" rather than "all" is correct here), or [regulation.days] doesn't
 * parse to any actual day.
 *
 * Unlike a simple "is there a live warning right now" check, this also looks ahead: parked at
 * 3am with an 8am-6pm window, this returns today's 8am-plus-limit, not null — so a reminder can
 * be scheduled ahead of the window opening, not only once it's already active. When [from]
 * falls inside an active window, this returns exactly what an instantaneous check would.
 *
 * The time-limit clock is treated as resetting at the start of each day's enforcement window
 * (never counting minutes parked before the window opened, or on a day it isn't enforced) —
 * this is a simplification: it doesn't model chalking/LPR-based persistent multi-day tracking,
 * which this feed has no data on either way. Matching the sweep reminder's own scope, this also
 * doesn't chain forward past the first found deadline — if the car is still parked there
 * without moving well past it, nothing here re-schedules a second, later one automatically.
 *
 * A day only produces a deadline if the limit can actually be exceeded inside that day's window:
 * when clock start + limit lands at or after the window's end there's no violation that day, so the
 * search moves on to the next enforced day (whose clock starts at the window's opening). This is
 * what stops "parked at 5:59pm, window closes at 6pm" from returning a 6pm deadline with a wrong
 * "limit is up" message and nothing scheduled for the next morning. It also means a limit at least
 * as long as the whole window (the feed has 72-hour rows) never yields a deadline at all.
 *
 * Also null for a limit of zero or less: the feed has ~850 rows with 0 or no HRLIMIT, and "0 hours"
 * can't be turned into a meaningful move-by time. Days the city doesn't enforce time-limited RPP
 * ([SfHolidayCalendar.isRppSuspended]) are skipped like any other non-enforced day.
 */
fun nextRppDeadline(
    regulation: RppZoneRegulation,
    car: Car,
    parkedSince: LocalDateTime,
    from: LocalDateTime,
    maxDaysToSearch: Int = 8
): RppWarning? {
    val zoneLetters = regulation.zoneLetterSet()
    if (zoneLetters.isEmpty()) return null
    if (car.permitZoneLetterSet().any { it in zoneLetters }) return null

    if (regulation.hrLimit <= 0f) return null
    val activeDays = parseDaysRange(regulation.days)
    if (activeDays.isEmpty()) return null

    val windowStart = militaryHourToLocalTime(regulation.hrsBegin)
    val windowEnd = militaryHourToLocalTime(regulation.hrsEnd)

    var candidateDate = from.toLocalDate()
    repeat(maxDaysToSearch) {
        if (candidateDate.dayOfWeek in activeDays && !SfHolidayCalendar.isRppSuspended(candidateDate)) {
            val isToday = candidateDate == from.toLocalDate()
            val windowEndThisDay = candidateDate.atTime(windowEnd)
            // If today's window has already fully closed by `from`, there's nothing left to
            // find on this date — fall through to the next candidate day instead.
            if (!isToday || from.isBefore(windowEndThisDay)) {
                val windowStartThisDay = candidateDate.atTime(windowStart)
                val clockStart = if (isToday && parkedSince.isAfter(windowStartThisDay)) parkedSince else windowStartThisDay
                val moveBy = clockStart.plusMinutes((regulation.hrLimit * 60).toLong())
                // Only a deadline strictly inside the window is a violation. At or after the
                // window's end the limit can't be exceeded today, so fall through to the next day.
                if (moveBy.isBefore(windowEndThisDay)) {
                    return RppWarning(
                        zoneLetters = zoneLetters,
                        hrLimitHours = regulation.hrLimit,
                        moveByDateTime = moveBy
                    )
                }
            }
        }
        candidateDate = candidateDate.plusDays(1)
    }
    return null
}
