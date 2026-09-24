package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CurbScheduleTest {

    /** A row on curb 123 / side R. April 2026 has no holidays; 2026-04-15 is a Wednesday. */
    private fun row(id: String, name: String, from: Int = 8, to: Int = 10, cnn: String = "123", side: String = "R") =
        sweepSegment(fullName = name, fromHour = from, toHour = to).copy(blockSweepId = id, cnn = cnn, cnnRightLeft = side)

    private val wednesday = LocalDateTime.of(2026, 4, 15, 9, 30)

    // ---- the bug: parking saved one row of a multi-row curb ----

    @Test
    fun aCurbSweptMondayAndThursday_nextSweepIsTheEarlierOne_notJustTheMatchedRow() {
        val monday = row("mon", "Monday")
        val thursday = row("thu", "Thursday")
        // Matched row = Monday only would have said next Monday (Apr 20). The curb's real next sweep is Thursday Apr 16.
        assertEquals(LocalDateTime.of(2026, 4, 20, 8, 0), NextSweepCalculator.nextSweepDateTime(monday, wednesday))
        assertEquals(LocalDateTime.of(2026, 4, 16, 8, 0), CurbSchedule.nextSweepDateTime(listOf(monday, thursday), wednesday))
    }

    @Test
    fun aSevenDayNightlyCurbWithAHolidayRow_isSweptTomorrow_andTheHolidayRowNeverCounts() {
        val nightly = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            .map { row(it.lowercase(), it, from = 2, to = 6) } + row("hol", "HOLIDAY", from = 2, to = 6)
        // Wednesday 9:30am: the next 2am sweep is Thursday Apr 16 at 02:00.
        assertEquals(LocalDateTime.of(2026, 4, 16, 2, 0), CurbSchedule.nextSweepDateTime(nightly, wednesday))
    }

    @Test
    fun aCurbWithOnlyAHolidayRow_hasNoUpcomingSweep() {
        assertNull(CurbSchedule.nextSweepDateTime(listOf(row("hol", "HOLIDAY", 2, 6)), wednesday))
    }

    @Test
    fun sweepInProgress_looksAtEveryRow_andReportsTheLatestEnd() {
        val tuesday = LocalDateTime.of(2026, 4, 14, 9, 30) // a Tuesday
        val early = row("a", "Tuesday", 8, 10)   // in progress until 10:00
        val late = row("b", "Tuesday", 9, 12)    // in progress until 12:00
        val other = row("c", "Thursday", 8, 10)  // not today
        assertEquals(LocalDateTime.of(2026, 4, 14, 12, 0), CurbSchedule.sweepInProgressEnd(listOf(early, late, other), tuesday))
        assertNull(CurbSchedule.sweepInProgressEnd(listOf(other), tuesday))
    }

    @Test
    fun mostUrgentStatus_isTheMostUrgentRow() {
        val thresholds = SweepThresholds()
        val tuesday = LocalDateTime.of(2026, 4, 14, 9, 30)
        val activeNow = row("a", "Tuesday", 8, 10)
        val nextWeek = row("b", "Monday")
        assertEquals(SweepStatus.ACTIVE_OR_VERY_SOON, CurbSchedule.mostUrgentStatus(listOf(nextWeek, activeNow), tuesday, thresholds))
        assertEquals(SweepStatus.SAFE, CurbSchedule.mostUrgentStatus(emptyList(), tuesday, thresholds))
    }

    // ---- choosing one representative row ----

    @Test
    fun theRepresentative_isARealWeekdayRow_neverTheHolidayRow() {
        val rows = listOf(row("hol", "HOLIDAY", 2, 6), row("thu", "Thursday"), row("mon", "Monday"))
        assertEquals("thu", CurbSchedule.pickRepresentative(rows, wednesday).blockSweepId) // soonest parseable
        assertEquals("mon", CurbSchedule.pickRepresentative(listOf(row("hol", "HOLIDAY"), row("mon", "Monday")), wednesday).blockSweepId)
    }

    @Test
    fun theRepresentative_isDeterministic_whateverTheInputOrder() {
        val a = row("a", "Monday")
        val b = row("b", "Monday")
        assertEquals("a", CurbSchedule.pickRepresentative(listOf(b, a), wednesday).blockSweepId)
        assertEquals("a", CurbSchedule.pickRepresentative(listOf(a, b), wednesday).blockSweepId)
    }

    // ---- the matcher: one candidate per curb ----

    @Test
    fun coLocatedRowsOfOneCurb_collapseToOneConfidentMatch() {
        val rows = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
            .map { row(it.lowercase(), it, 2, 6) } + row("hol", "HOLIDAY", 2, 6)
        // All eight are the same physical curb, so all at the same distance.
        val matches = rows.map { SegmentMatch(it, 4.0) }

        // Before: eight matches 0 m apart => AMBIGUOUS. Now: one.
        assertEquals(MatchConfidence.AMBIGUOUS, classifyMatch(matches))
        val collapsed = CurbSchedule.collapseSameCurb(matches, wednesday)
        assertEquals(1, collapsed.size)
        assertEquals(MatchConfidence.CONFIDENT, classifyMatch(collapsed))
        assertEquals("thursday", collapsed.single().segment.blockSweepId) // the next sweep, not the HOLIDAY row
        assertEquals(4.0, collapsed.single().distanceMeters, 0.0)
    }

    @Test
    fun twoDifferentCurbsClose_together_stayAmbiguous() {
        val thisSide = row("r1", "Monday", side = "R")
        val otherSide = row("l1", "Monday", side = "L")
        val collapsed = CurbSchedule.collapseSameCurb(
            listOf(SegmentMatch(thisSide, 3.0), SegmentMatch(otherSide, 5.0), SegmentMatch(row("r2", "Thursday", side = "R"), 3.0)),
            wednesday
        )
        assertEquals(2, collapsed.size)                                   // R (two rows merged) and L
        assertEquals(listOf("R", "L"), collapsed.map { it.segment.cnnRightLeft })
        assertEquals(MatchConfidence.AMBIGUOUS, classifyMatch(collapsed)) // 3 m vs 5 m: still can't tell the sides apart
    }

    @Test
    fun collapsing_keepsTheNearestDistance_andSortsByIt() {
        val far = row("far", "Monday", cnn = "1")
        val near1 = row("near1", "Monday", cnn = "2")
        val near2 = row("near2", "Thursday", cnn = "2")
        val collapsed = CurbSchedule.collapseSameCurb(
            listOf(SegmentMatch(far, 20.0), SegmentMatch(near1, 6.0), SegmentMatch(near2, 5.0)), wednesday
        )
        assertEquals(listOf(5.0, 20.0), collapsed.map { it.distanceMeters })
        assertEquals("2", collapsed.first().segment.cnn)
    }

    // ---- the map's per-curb de-duplication ----

    private data class Candidate(val seg: StreetSegment, val status: SweepStatus)

    @Test
    fun pickByUrgency_moreUrgentWins_andOnATiePrefersARealRowOverHoliday() {
        val hol = Candidate(row("hol", "HOLIDAY", 2, 6), SweepStatus.SAFE)
        val real = Candidate(row("mon", "Monday"), SweepStatus.SAFE)
        val urgent = Candidate(row("thu", "Thursday"), SweepStatus.IMMINENT)
        assertEquals("mon", CurbSchedule.pickByUrgency(listOf(hol, real), { it.seg }, { it.status }).seg.blockSweepId)
        assertEquals("mon", CurbSchedule.pickByUrgency(listOf(real, hol), { it.seg }, { it.status }).seg.blockSweepId)
        assertEquals("thu", CurbSchedule.pickByUrgency(listOf(hol, real, urgent), { it.seg }, { it.status }).seg.blockSweepId)
    }

    // ---- the block details sheet: the whole curb, not the tapped row ----

    @Test
    fun sweepsOn_marksEveryRowsDays_notJustTheTappedOne() {
        val curb = listOf(row("mon", "Monday"), row("thu", "Thursday"))
        assertTrue(CurbSchedule.sweepsOn(curb, java.time.LocalDate.of(2026, 4, 13)))  // Monday
        assertTrue(CurbSchedule.sweepsOn(curb, java.time.LocalDate.of(2026, 4, 16)))  // Thursday: hidden before
        assertTrue(!CurbSchedule.sweepsOn(curb, java.time.LocalDate.of(2026, 4, 15))) // Wednesday
    }

    @Test
    fun sweepsOn_respectsTheWeekOfTheMonth() {
        val firstAndThird = sweepSegment(fullName = "Monday", fromHour = 8, toHour = 10,
            weeks = booleanArrayOf(true, false, true, false, false)).copy(blockSweepId = "m13", cnn = "123")
        assertTrue(CurbSchedule.sweepsOn(listOf(firstAndThird), java.time.LocalDate.of(2026, 4, 6)))   // 1st Monday
        assertTrue(!CurbSchedule.sweepsOn(listOf(firstAndThird), java.time.LocalDate.of(2026, 4, 13))) // 2nd Monday
        assertEquals("1st & 3rd", CurbSchedule.weeksLabel(firstAndThird))
        assertEquals("", CurbSchedule.weeksLabel(row("m", "Monday")))
    }

    @Test
    fun aHoliday_isASkip_notASweep() {
        val thursday = listOf(row("thu", "Thursday"))
        val thanksgiving = java.time.LocalDate.of(2026, 11, 26)
        assertTrue(!CurbSchedule.sweepsOn(thursday, thanksgiving))
        assertTrue(CurbSchedule.holidaySkipOn(thursday, thanksgiving))
        assertEquals("thu", CurbSchedule.holidaySkipRow(thursday, thanksgiving)?.blockSweepId)
        // An ordinary Thursday is neither a skip nor missing.
        assertTrue(!CurbSchedule.holidaySkipOn(thursday, java.time.LocalDate.of(2026, 11, 19)))
    }

    @Test
    fun scheduleRows_oneLinePerDistinctSchedule_inWeekdayOrder_withoutTheHolidayRow() {
        val rows = listOf(row("thu", "Thursday"), row("hol", "HOLIDAY", 2, 6), row("mon", "Monday"), row("mon2", "Monday"))
        assertEquals(listOf("mon", "thu"), CurbSchedule.scheduleRows(rows).map { it.blockSweepId })
    }

    @Test
    fun curbKey_isCnnPlusSide() {
        assertEquals("123|R", CurbSchedule.curbKey(row("x", "Monday")))
        assertTrue(CurbSchedule.curbKey(row("x", "Monday", side = "L")) != CurbSchedule.curbKey(row("x", "Monday", side = "R")))
    }
}
