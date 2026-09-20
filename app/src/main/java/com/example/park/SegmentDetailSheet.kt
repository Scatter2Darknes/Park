package com.example.park

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentDetailSheet(
    segment: StreetSegment,
    onDismiss: () -> Unit,
    onOverrideChanged: () -> Unit = {},
    activeCar: Car? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var existingOverride by remember { mutableStateOf<ScheduleOverride?>(null) }
    var showOverrideDialog by remember { mutableStateOf(false) }
    var rppRegulation by remember { mutableStateOf<RppZoneRegulation?>(null) }

    LaunchedEffect(segment.blockSweepId) {
        existingOverride = AppDatabase.getInstance(context).scheduleOverrideDao().getById(segment.blockSweepId)
        rppRegulation = findConfidentRppMatch(context, LatLng(segment.centroidLat, segment.centroidLng))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = segment.corridor,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(text = segment.limits, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            // Compass is purely a visual reinforcement of the text that's already there —
            // it's never a replacement for "North side", and it's simply omitted (not shown
            // needle-less) when BlockSide isn't one of DataSF's 8 standard compass values.
            val compassDegrees = remember(segment.blockSide) { blockSideCompassDegrees(segment.blockSide) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (compassDegrees != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    BlockSideCompass(degrees = compassDegrees)
                }
                Text(
                    text = "  ${segment.blockSide} side \u00b7 ${segment.fullName}s ${formatHour(segment.fromHour)}\u2013${formatHour(segment.toHour)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (existingOverride != null) {
                Text(
                    "\u270f\ufe0f Manually corrected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val next = NextSweepCalculator.nextSweepDateTime(segment)
            if (next != null) {
                val daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), next.toLocalDate())
                val label = when {
                    daysUntil == 0L -> "Today"
                    daysUntil == 1L -> "Tomorrow"
                    else -> "In $daysUntil days"
                }
                Text(
                    text = "Next cleaning: ${next.toLocalDate()} \u2014 $label",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text("No upcoming cleaning found in the next 60 days.")
            }

            Spacer(modifier = Modifier.height(16.dp))

            SweepCalendarGrid(segment = segment, weeksToShow = 4)

            rppRegulation?.let { regulation -> RppZoneSection(regulation = regulation, activeCar = activeCar) }

            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { showOverrideDialog = true }) {
                Text(if (existingOverride != null) "Edit correction" else "Doesn't match the sign? Fix it")
            }
        }
    }

    if (showOverrideDialog) {
        ScheduleOverrideDialog(
            segment = segment,
            existingOverride = existingOverride,
            onSave = { override ->
                scope.launch {
                    AppDatabase.getInstance(context).scheduleOverrideDao().upsert(override)
                    showOverrideDialog = false
                    onOverrideChanged()
                    onDismiss() // segment data is now stale relative to the DB; close rather than show outdated info
                }
            },
            onRemove = {
                scope.launch {
                    AppDatabase.getInstance(context).scheduleOverrideDao().deleteById(segment.blockSweepId)
                    showOverrideDialog = false
                    onOverrideChanged()
                    onDismiss()
                }
            },
            onDismiss = { showOverrideDialog = false }
        )
    }
}

/**
 * Small 8-point compass rose with a needle pointed at [degrees] (0 = North, clockwise).
 * Purely decorative alongside the existing "North side" text — ticks give the needle a
 * frame of reference without adding any new text/labels to the header.
 */
@Composable
private fun BlockSideCompass(degrees: Double, modifier: Modifier = Modifier) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(18.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        for (tick in 0 until 8) {
            val angle = Math.toRadians((tick * 45).toDouble())
            val outer = Offset(
                center.x + (radius * sin(angle)).toFloat(),
                center.y - (radius * cos(angle)).toFloat()
            )
            val inner = Offset(
                center.x + (radius * 0.7f * sin(angle)).toFloat(),
                center.y - (radius * 0.7f * cos(angle)).toFloat()
            )
            drawLine(tickColor, inner, outer, strokeWidth = 1.5f)
        }

        val needleAngle = Math.toRadians(degrees)
        val tip = Offset(
            center.x + (radius * 0.9f * sin(needleAngle)).toFloat(),
            center.y - (radius * 0.9f * cos(needleAngle)).toFloat()
        )
        drawLine(needleColor, center, tip, strokeWidth = 2.5f, cap = StrokeCap.Round)
        drawCircle(needleColor, radius = 2f, center = center)
    }
}

@Composable
private fun SweepCalendarGrid(segment: StreetSegment, weeksToShow: Int) {
    val today = LocalDate.now()
    val daysFromSunday = today.dayOfWeek.value % 7 // Mon=1...Sun=7, so Sunday becomes 0
    val startOfWeek = today.minusDays(daysFromSunday.toLong())

    val targetDay = NextSweepCalculator.dayOfWeekFromName(segment.fullName)
    val weekFlags = listOf(segment.week1, segment.week2, segment.week3, segment.week4, segment.week5)

    // Nearest holiday within the visible range that would otherwise have been a cleaning day —
    // called out explicitly since a suppressed date silently missing its usual red dot is easy
    // to misread as "the schedule glitched" rather than "no cleaning today, it's a holiday."
    val upcomingHolidaySkip = remember(segment.blockSweepId) {
        (0 until weeksToShow * 7L)
            .asSequence()
            .map { today.plusDays(it) }
            .firstOrNull { date ->
                targetDay != null && date.dayOfWeek == targetDay &&
                        weekFlags[((date.dayOfMonth - 1) / 7)] &&
                        SfHolidayCalendar.isSuspended(date, segment)
            }
            ?.let { date -> date to SfHolidayCalendar.holidayName(date, segment)!! }
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        for (week in 0 until weeksToShow) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dayOffset in 0 until 7) {
                    val date = startOfWeek.plusDays((week * 7 + dayOffset).toLong())
                    val occurrence = ((date.dayOfMonth - 1) / 7) + 1
                    val wouldBeSweepDay = targetDay != null && date.dayOfWeek == targetDay &&
                            occurrence in 1..5 && weekFlags[occurrence - 1]
                    val isHolidaySkip = wouldBeSweepDay && SfHolidayCalendar.isSuspended(date, segment)
                    val isSweepDay = wouldBeSweepDay && !isHolidaySkip
                    val isToday = date == today

                    Box(
                        modifier = Modifier.weight(1f).padding(2.dp).aspectRatio(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.8f)
                                .let {
                                    if (isHolidaySkip) {
                                        it.border(1.5.dp, Color(0xFFF44336), CircleShape)
                                    } else {
                                        it.background(
                                            color = if (isSweepDay) Color(0xFFF44336)
                                            else if (isToday) Color(0xFF2196F3).copy(alpha = 0.3f)
                                            else Color.Transparent,
                                            shape = CircleShape
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                fontSize = 13.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSweepDay) Color.White else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }

        upcomingHolidaySkip?.let { (date, name) ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "No cleaning ${formatHolidaySkipDate(date, today)} — $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatHolidaySkipDate(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "today"
    today.plusDays(1) -> "tomorrow"
    else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
}

@Composable
private fun RppZoneSection(regulation: RppZoneRegulation, activeCar: Car?) {
    val zones = remember(regulation.objectId) { regulation.zoneLetterSet() }
    val hasPermit = activeCar?.permitZoneLetterSet()?.any { it in zones } == true

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "🅿️ RPP Zone ${zones.sorted().joinToString("/")}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = if (hasPermit) {
            "${activeCar?.name ?: "This car"} has a permit for this zone — exempt from the time limit."
        } else {
            val limit = regulation.hrLimit
            val limitText = if (limit == limit.toInt().toFloat()) "${limit.toInt()}hr" else "${limit}hr"
            "$limitText limit without a permit, ${formatRppDays(regulation.days)} " +
                    "${formatHour(regulation.hrsBegin / 100)}–${formatHour(regulation.hrsEnd / 100)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}