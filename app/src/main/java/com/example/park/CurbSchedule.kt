package com.example.park

import android.content.Context
import java.time.LocalDateTime

/**
 * Sweeping schedules for a whole CURB, not a single database row.
 *
 * DataSF describes one physical curb-side (identified by `cnn` + `cnnRightLeft`) with SEVERAL rows: one per
 * sweep weekday when a curb is swept on different days (about 5,800 of 22,600 curbs have 2-13 rows, 4,800 with
 * different weekdays — the 7-day nightly routes have one row per weekday), or one per week pattern
 * ("Mon 1st & 3rd" / "Mon 2nd & 4th"). Parking used to save just ONE of those rows, so the reminder covered only
 * that row's days: park on a curb swept Monday and Thursday and you could be subscribed to Monday alone.
 *
 * Everything here works on the list of rows for a curb and answers questions about the curb as a whole. Pure and
 * JVM-testable; [loadCurbRows] is the one function that touches the database.
 *
 * "HOLIDAY" rows (824 of them, all on curbs that also have ordinary weekday rows) have no weekday, so their next
 * sweep is null and they never contribute; the day rows already carry the right holiday behaviour. The point of
 * treating rows as a group is that such a row can no longer be picked INSTEAD of a real one.
 */
object CurbSchedule {

    /** Same key the map drawing already uses to keep one row per curb. */
    fun curbKey(segment: StreetSegment): String = "${segment.cnn}|${segment.cnnRightLeft}"

    /** The earliest upcoming sweep across every row of the curb, or null if none of them has one. */
    fun nextSweepDateTime(rows: List<StreetSegment>, from: LocalDateTime = sfNow()): LocalDateTime? =
        rows.mapNotNull { NextSweepCalculator.nextSweepDateTime(it, from) }.minOrNull()

    /** When the sweep happening right now ends — the latest end among rows in progress (the longer one is what
     *  the driver has to wait out) — or null if no row of the curb is being swept at [now]. */
    fun sweepInProgressEnd(rows: List<StreetSegment>, now: LocalDateTime): LocalDateTime? =
        rows.mapNotNull { NextSweepCalculator.sweepInProgressEndDateTime(it, now) }.maxOrNull()

    /** The most urgent status among the curb's rows (a curb is as urgent as its most urgent row). */
    fun mostUrgentStatus(rows: List<StreetSegment>, now: LocalDateTime, thresholds: SweepThresholds): SweepStatus =
        rows.map { sweepStatus(it, now, thresholds) }.minByOrNull { it.urgencyRank() } ?: SweepStatus.SAFE

    /**
     * Which row stands for the curb when ONE has to be stored or shown (the matched candidate, the row saved on a
     * parked car). Rows with a parseable weekday come first, then the earliest next sweep, then blockSweepId so the
     * choice is deterministic. Never a "HOLIDAY" row while a real one exists.
     */
    fun pickRepresentative(rows: List<StreetSegment>, from: LocalDateTime = sfNow()): StreetSegment =
        rows.minWith(
            compareBy<StreetSegment>(
                { if (NextSweepCalculator.dayOfWeekFromName(it.fullName) == null) 1 else 0 },
                { NextSweepCalculator.nextSweepDateTime(it, from) ?: LocalDateTime.MAX },
                { it.blockSweepId }
            )
        )

    /**
     * Collapses candidate matches so each physical curb appears ONCE: at its nearest distance, represented by
     * [pickRepresentative]. Rows on the same curb are co-located, so before this they made every multi-row curb look
     * AMBIGUOUS to [classifyMatch] (two matches under 5 m apart) and put the duplicates in the manual picker.
     * Two genuinely different curbs (e.g. the two sides of a street) are still separate candidates.
     */
    fun collapseSameCurb(matches: List<SegmentMatch>, from: LocalDateTime = sfNow()): List<SegmentMatch> =
        matches.groupBy { curbKey(it.segment) }
            .map { (_, group) ->
                SegmentMatch(
                    segment = pickRepresentative(group.map { it.segment }, from),
                    distanceMeters = group.minOf { it.distanceMeters }
                )
            }
            .sortedBy { it.distanceMeters }

    /**
     * Among rows with the same urgency (e.g. all SAFE) prefer a real weekday row over an unparseable one; used by the
     * map's per-curb de-duplication so a tap never lands on a "HOLIDAY" row that says "no upcoming cleaning" for a curb
     * that is swept. Returns the winning candidate.
     */
    fun <T> pickByUrgency(candidates: List<T>, segmentOf: (T) -> StreetSegment, statusOf: (T) -> SweepStatus): T =
        candidates.minWith(
            compareBy<T>(
                { statusOf(it).urgencyRank() },
                { if (NextSweepCalculator.dayOfWeekFromName(segmentOf(it).fullName) == null) 1 else 0 },
                { segmentOf(it).blockSweepId }
            )
        )
}

/** Every row for [segment]'s curb (override-aware), always including [segment] itself even if the database no longer has it. */
suspend fun loadCurbRows(context: Context, segment: StreetSegment): List<StreetSegment> {
    val rows = AppDatabase.getInstance(context).streetSegmentDao().getByCurb(segment.cnn, segment.cnnRightLeft)
    return if (rows.any { it.blockSweepId == segment.blockSweepId }) rows else rows + segment
}
