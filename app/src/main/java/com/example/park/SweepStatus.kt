package com.example.park

import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

enum class SweepStatus { SAFE, SOON, IMMINENT, ACTIVE_OR_VERY_SOON }

data class SweepThresholds(
    val soonDays: Float = SettingsDefaults.SOON_THRESHOLD_DAYS,
    val imminentDays: Float = SettingsDefaults.IMMINENT_THRESHOLD_DAYS
)

/**
 * The four sweep-status colors, kept pairwise distinct as an invariant — see
 * [swapAssignment], the only sanctioned way to change one, which preserves that invariant
 * by construction rather than validating it after the fact.
 */
data class SweepStatusColors(
    val safeHex: String = SettingsDefaults.SAFE_COLOR_HEX,
    val soonHex: String = SettingsDefaults.SOON_COLOR_HEX,
    val imminentHex: String = SettingsDefaults.IMMINENT_COLOR_HEX,
    val activeHex: String = SettingsDefaults.ACTIVE_COLOR_HEX
)

enum class SweepColorSlot { SAFE, SOON, IMMINENT, ACTIVE }

private fun SweepStatusColors.hexFor(slot: SweepColorSlot): String = when (slot) {
    SweepColorSlot.SAFE -> safeHex
    SweepColorSlot.SOON -> soonHex
    SweepColorSlot.IMMINENT -> imminentHex
    SweepColorSlot.ACTIVE -> activeHex
}

private fun SweepStatusColors.withHex(slot: SweepColorSlot, hex: String): SweepStatusColors = when (slot) {
    SweepColorSlot.SAFE -> copy(safeHex = hex)
    SweepColorSlot.SOON -> copy(soonHex = hex)
    SweepColorSlot.IMMINENT -> copy(imminentHex = hex)
    SweepColorSlot.ACTIVE -> copy(activeHex = hex)
}

/**
 * Assigns [newHex] to [slot]. If another slot currently holds [newHex], the two slots swap
 * colors instead of creating a duplicate — this is a transposition on an already-distinct
 * 4-tuple, which cannot introduce a collision. If no other slot holds [newHex], this is a
 *  * reassignment to a genuinely-unused value, which also cannot collide. Either way, the
 * pairwise-distinct invariant holds after the call given it held before — by induction,
 * starting from the pairwise-distinct defaults, it holds for every reachable state, for any
 * sequence of calls to this function.
 */
fun swapAssignment(current: SweepStatusColors, slot: SweepColorSlot, newHex: String): SweepStatusColors {
    val oldHexForSlot = current.hexFor(slot)
    val conflictingSlot = SweepColorSlot.entries.firstOrNull {
        it != slot && current.hexFor(it).equals(newHex, ignoreCase = true)
    }
    var result = current.withHex(slot, newHex)
    if (conflictingSlot != null) {
        result = result.withHex(conflictingSlot, oldHexForSlot)
    }
    return result
}

private fun parseColorOrDefault(hex: String, default: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Color(android.graphics.Color.parseColor(default))
}

fun sweepStatus(
    segment: StreetSegment,
    now: LocalDateTime = sfNow(),
    thresholds: SweepThresholds = SweepThresholds()
): SweepStatus {
    val targetDay = NextSweepCalculator.dayOfWeekFromName(segment.fullName)

    if (targetDay != null && now.dayOfWeek == targetDay) {
        val weekFlags = listOf(segment.week1, segment.week2, segment.week3, segment.week4, segment.week5)
        val occurrence = ((now.dayOfMonth - 1) / 7) + 1
        if (weekFlags[occurrence - 1] && !SfHolidayCalendar.isSuspended(now.toLocalDate(), segment)) {
            val windowStart = now.toLocalDate().atTime(segment.fromHour, 0)
            val windowEnd = now.toLocalDate().atTime(segment.toHour, 0)
            val minutesUntilStart = Duration.between(now, windowStart).toMinutes()
            if ((now.isAfter(windowStart) && now.isBefore(windowEnd)) || (minutesUntilStart in 0..30)) {
                return SweepStatus.ACTIVE_OR_VERY_SOON
            }
        }
    }

    val next = NextSweepCalculator.nextSweepDateTime(segment, now) ?: return SweepStatus.SAFE
    val daysUntil = Duration.between(now, next).toHours() / 24.0

    return when {
        daysUntil > thresholds.soonDays -> SweepStatus.SAFE
        daysUntil > thresholds.imminentDays -> SweepStatus.SOON
        else -> SweepStatus.IMMINENT
    }
}

fun sweepStatusColor(status: SweepStatus, colors: SweepStatusColors = SweepStatusColors()): Color = when (status) {
    SweepStatus.SAFE -> parseColorOrDefault(colors.safeHex, SettingsDefaults.SAFE_COLOR_HEX)
    SweepStatus.SOON -> parseColorOrDefault(colors.soonHex, SettingsDefaults.SOON_COLOR_HEX)
    SweepStatus.IMMINENT -> parseColorOrDefault(colors.imminentHex, SettingsDefaults.IMMINENT_COLOR_HEX)
    SweepStatus.ACTIVE_OR_VERY_SOON -> parseColorOrDefault(colors.activeHex, SettingsDefaults.ACTIVE_COLOR_HEX)
}
/**
 * Lower = more urgent. Used to pick one representative row when several StreetSegment rows describe the same physical
 * curb-side with different (and sometimes disagreeing) schedules — the map's per-curb de-duplication and CurbSchedule.
 * Lives here, next to SweepStatus, rather than in MapUtils.kt, whose file-level initialization needs Android classes
 * and so can't load in JVM unit tests.
 */
internal fun SweepStatus.urgencyRank(): Int = when (this) {
    SweepStatus.ACTIVE_OR_VERY_SOON -> 0
    SweepStatus.IMMINENT -> 1
    SweepStatus.SOON -> 2
    SweepStatus.SAFE -> 3
}
