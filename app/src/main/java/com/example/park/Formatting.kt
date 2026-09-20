package com.example.park

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SWEEP_DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")

/** e.g. "Thu, Jun 5 at 2:00 PM" — used everywhere a next-sweep time is shown to the user. */
fun formatSweepDateTime(dt: ZonedDateTime): String = dt.format(SWEEP_DATETIME_FORMATTER)

/**
 * Single-unit countdown for tight spaces like on-map labels — "45m", "3h", "2d", never a
 * combined "2h 15m" — keeping it to at most ~2 digits plus a unit letter.
 */
fun formatShortCountdown(millisRemaining: Long): String {
    val remaining = millisRemaining.coerceAtLeast(0)
    if (remaining < 60_000L) return "now"

    val duration = Duration.ofMillis(remaining)
    return when {
        duration.toDays() >= 1 -> "${duration.toDays()}d"
        duration.toHours() >= 1 -> "${duration.toHours()}h"
        else -> "${duration.toMinutes()}m"
    }
}

/**
 * Compact countdown for "time until sweep" displays, e.g. "2d 4h", "5h 12m", "23m".
 * Anything under a minute (including already-elapsed, from the reschedule race window)
 * reads as "Now" rather than a confusing "0m" or negative duration.
 */
fun formatCountdown(millisRemaining: Long): String {
    val remaining = millisRemaining.coerceAtLeast(0)
    if (remaining < 60_000L) return "Now"

    val duration = Duration.ofMillis(remaining)
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60

    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

/** e.g. "2PM", "12AM" — used for hour-only displays like sweep windows. */
fun formatHour(hour: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "$displayHour$amPm"
}

/**
 * Maps DataSF's BlockSide value (e.g. "North", "SouthEast" — confirmed via the dataset's
 * column docs as one of the 8 compass points, CamelCase, no space) to a 0-360 bearing for
 * the segment-detail compass needle. Returns null for blank/unrecognized values, so the
 * header can simply omit the compass rather than show a needle with nothing to point at —
 * this covers streets where BlockSide isn't one of the 8 standard values.
 */
fun blockSideCompassDegrees(blockSide: String): Double? {
    val normalized = blockSide.trim().lowercase().replace(" ", "")
    return when (normalized) {
        "north" -> 0.0
        "northeast" -> 45.0
        "east" -> 90.0
        "southeast" -> 135.0
        "south" -> 180.0
        "southwest" -> 225.0
        "west" -> 270.0
        "northwest" -> 315.0
        else -> null
    }
}

/** e.g. "42 MB", "1.2 GB" — used for tile cache size display. */
fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

/** e.g. "5 min ago", "3h ago", "2d ago" — used for "last synced" indicators. */
fun formatRelativeTime(millis: Long): String {
    val duration = Duration.between(java.time.Instant.ofEpochMilli(millis), java.time.Instant.now())
    val minutes = duration.toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${duration.toHours()}h ago"
        else -> "${duration.toDays()}d ago"
    }
}