package com.example.park

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

object NextSweepCalculator {
    fun nextSweepDateTime(
        segment: StreetSegment,
        from: LocalDateTime = LocalDateTime.now(),
        maxDaysToSearch: Int = 60
    ): LocalDateTime? {
        val targetDay = dayOfWeekFromName(segment.fullName) ?: return null
        val weekFlags = listOf(segment.week1, segment.week2, segment.week3, segment.week4, segment.week5)
        var candidateDate = from.toLocalDate()

        repeat(maxDaysToSearch) {
            if (candidateDate.dayOfWeek == targetDay) {
                val occurrence = weekOfMonthOccurrence(candidateDate)
                if (weekFlags[occurrence - 1] && !SfHolidayCalendar.isSuspended(candidateDate, segment)) {
                    val candidateDateTime = candidateDate.atTime(segment.fromHour, 0)
                    if (candidateDateTime.isAfter(from)) return candidateDateTime
                }
            }
            candidateDate = candidateDate.plusDays(1)
        }
        return null
    }

    /**
     * If [now] falls within this segment's active sweeping window today (right day of week,
     * right week-of-month occurrence), returns when that window ends; otherwise null. Used
     * for the "sweeping ends in Xh Ym" countdown — mirrors the ACTIVE_OR_VERY_SOON check
     * already inline inside sweepStatus() (SweepStatus.kt), duplicated rather than refactored
     * out from there to avoid touching that already-tested logic for this addition.
     */
    fun activeWindowEndDateTime(segment: StreetSegment, now: LocalDateTime): LocalDateTime? {
        val targetDay = dayOfWeekFromName(segment.fullName) ?: return null
        if (now.dayOfWeek != targetDay) return null
        val weekFlags = listOf(segment.week1, segment.week2, segment.week3, segment.week4, segment.week5)
        val occurrence = weekOfMonthOccurrence(now.toLocalDate())
        if (!weekFlags[occurrence - 1]) return null
        if (SfHolidayCalendar.isSuspended(now.toLocalDate(), segment)) return null
        return now.toLocalDate().atTime(segment.toHour, 0)
    }

    fun dayOfWeekFromName(name: String): DayOfWeek? {
        val firstWord = name.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        return DayOfWeek.entries.firstOrNull { day ->
            val full = day.name.lowercase()      // "monday"
            val abbrev = full.substring(0, 3)    // "mon"
            firstWord == full || firstWord == abbrev
        }
    }

    private fun weekOfMonthOccurrence(date: LocalDate): Int = ((date.dayOfMonth - 1) / 7) + 1
}