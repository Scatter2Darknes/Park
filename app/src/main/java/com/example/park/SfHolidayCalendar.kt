package com.example.park

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * SF's own street-cleaning holiday-enforcement calendar (per SFMTA's published Holiday Parking
 * Enforcement Schedule), which is NOT the same as the standard federal holiday list: it also
 * suspends on the day after Thanksgiving, and fixed-date holidays get the usual
 * Saturday-observed-Friday / Sunday-observed-Monday shift. Computed rather than hardcoded per
 * year so this never needs an annual data update.
 *
 * There are two different suspension lists depending on route type, straight from that page:
 *  - "Weekday Daytime Street Sweeping" (6am-2pm) — what every StreetSegment in this app
 *    represents — is suspended on the full list below.
 *  - "Nightly Street Sweeping" (12am-6am, 7-day/week commercial routes) is suspended only on
 *    the 3 major holidays (New Year's, Thanksgiving, Christmas).
 * StreetSegment.holidays (from DataSF's "1 = swept on holidays" field) is the signal for which
 * list applies to a given segment — see holidayName.
 */
object SfHolidayCalendar {

    fun fullSuspensionHolidays(year: Int): Map<LocalDate, String> = buildMap {
        put(observedNewYearsDay(year), "New Year's Day")
        put(nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "MLK Day")
        put(nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Presidents' Day")
        put(lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day")
        put(observedFixedDate(year, Month.JUNE, 19), "Juneteenth")
        put(observedFixedDate(year, Month.JULY, 4), "Independence Day")
        put(nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day")
        put(nthWeekdayOfMonth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Indigenous Peoples' Day")
        put(observedFixedDate(year, Month.NOVEMBER, 11), "Veterans Day")
        val thanksgiving = nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)
        put(thanksgiving, "Thanksgiving")
        put(thanksgiving.plusDays(1), "Day After Thanksgiving")
        put(observedFixedDate(year, Month.DECEMBER, 25), "Christmas")
        putNextYearsNewYearsIfSpillsBack(year)
    }

    fun majorHolidaysOnly(year: Int): Map<LocalDate, String> = buildMap {
        put(observedNewYearsDay(year), "New Year's Day")
        put(nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving")
        put(observedFixedDate(year, Month.DECEMBER, 25), "Christmas")
        putNextYearsNewYearsIfSpillsBack(year)
    }

    /** True if [date] is a holiday [segment]'s route observes a suspension on. */
    fun isSuspended(date: LocalDate, segment: StreetSegment): Boolean = holidayName(date, segment) != null

    /**
     * The name of the holiday [date] falls on for [segment]'s route, or null if [date] isn't
     * a suspended holiday for it. Segments with holidays=true are DataSF's "swept on holidays"
     * routes (nightly/commercial — only the 3 major holidays suspend them); holidays=false is
     * an ordinary weekday route (suspended on the full list).
     */
    fun holidayName(date: LocalDate, segment: StreetSegment): String? {
        val holidays = if (segment.holidays) majorHolidaysOnly(date.year) else fullSuspensionHolidays(date.year)
        return holidays[date]
    }

    // New Year's Day of the FOLLOWING year, if its observed date falls back into this
    // December — only possible when that Jan 1 is a Saturday (observed the preceding Friday).
    private fun MutableMap<LocalDate, String>.putNextYearsNewYearsIfSpillsBack(year: Int) {
        val nextYearNewYears = LocalDate.of(year + 1, Month.JANUARY, 1)
        if (nextYearNewYears.dayOfWeek == DayOfWeek.SATURDAY) put(nextYearNewYears.minusDays(1), "New Year's Day")
    }

    private fun observedNewYearsDay(year: Int): LocalDate = observedFixedDate(year, Month.JANUARY, 1)

    private fun observedFixedDate(year: Int, month: Month, day: Int): LocalDate {
        val actual = LocalDate.of(year, month, day)
        return when (actual.dayOfWeek) {
            DayOfWeek.SATURDAY -> actual.minusDays(1)
            DayOfWeek.SUNDAY -> actual.plusDays(1)
            else -> actual
        }
    }

    private fun nthWeekdayOfMonth(year: Int, month: Month, weekday: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, weekday))

    private fun lastWeekdayOfMonth(year: Int, month: Month, weekday: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(weekday))
}
