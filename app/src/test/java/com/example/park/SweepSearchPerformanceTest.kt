package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Cost of the sweep math on a map reload. Each reload calls `sweepStatus` (which calls
 * `NextSweepCalculator.nextSweepDateTime`) once per nearby segment, and driving mode reloads every
 * 2.5 s. The default 500 m radius holds roughly 300-1,600 segments (downtown is the dense end) and the
 * maximum 1,200 m setting up to ~6,400, so the sizes below cover both.
 *
 * `nextSweepDateTime`'s search limit was raised from 60 to 250 days so week-5-only schedules are found;
 * this checks that didn't make reloads slow. The numbers are printed (see the test report's
 * standard output); the assertions are deliberately loose upper bounds that catch an order-of-magnitude
 * regression without depending on the machine — a JVM on a laptop is several times faster than a
 * Galaxy S9, so treat the printed figures as a floor and multiply.
 */
class SweepSearchPerformanceTest {

    private val now = LocalDateTime.of(2026, 9, 21, 9, 30)

    /** The longest gap found by brute force (see NextSweepCalculator.DEFAULT_MAX_DAYS_TO_SEARCH): from here, a
     *  week-5-only Monday 8am route is next swept 210 days later, so each call walks 210 candidate dates. */
    private val worstCaseNow = LocalDateTime.of(2033, 1, 31, 9, 30)

    // The live feed's schedule patterns and their approximate shares (37,878 rows, checked 2026-09-20).
    private val realisticMix: List<Pair<Int, () -> StreetSegment>> = listOf(
        // weekly, one day (61%)
        61 to { sweepSegment(fullName = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday").random(seed++)) },
        // 2nd & 4th (18%), 1st & 3rd (11%), 1st/3rd/5th (6%), 2nd/4th/5th (1%), 1st only (0.3%)
        18 to { sweepSegment(fullName = "Mon 2nd & 4th", weeks = booleanArrayOf(false, true, false, true, false)) },
        11 to { sweepSegment(fullName = "Tue 1st & 3rd", weeks = booleanArrayOf(true, false, true, false, false)) },
        6 to { sweepSegment(fullName = "Thu 1st, 3rd, 5th", weeks = booleanArrayOf(true, false, true, false, true)) },
        1 to { sweepSegment(fullName = "Fri 2nd, 4th, 5th", weeks = booleanArrayOf(false, true, false, true, true)) },
        // unparseable "HOLIDAY" rows (2%) return immediately
        2 to { sweepSegment(fullName = "HOLIDAY") }
    )

    private var seed = 0
    private fun <T> List<T>.random(i: Int) = this[i % size]

    private fun mixedSegments(n: Int): List<StreetSegment> {
        val total = realisticMix.sumOf { it.first }
        return List(n) { i ->
            var pick = (i * 37) % total // deterministic spread over the weights
            realisticMix.first { (w, _) -> if (pick < w) true else { pick -= w; false } }.second()
        }
    }

    /** Runs [work] a few times to warm up the JIT, then returns the median of 5 timed runs in ms. */
    private fun timeMs(work: () -> Unit): Double {
        repeat(3) { work() }
        val samples = List(5) {
            val t0 = System.nanoTime(); work(); (System.nanoTime() - t0) / 1e6
        }.sorted()
        return samples[2]
    }

    private fun runAll(segments: List<StreetSegment>, at: LocalDateTime = now) {
        var acc = 0
        for (s in segments) acc += sweepStatus(s, at).ordinal
        assertTrue(acc >= 0) // keeps the loop from being optimized away
    }

    /** The very first pass, before the JIT has warmed up: closest to what a phone does on its first reload after launch. */
    @Test
    fun coldFirstPass_isStillSmall() {
        val segments = mixedSegments(1_600)
        val t0 = System.nanoTime(); runAll(segments); val ms = (System.nanoTime() - t0) / 1e6
        println("SWEEP-PERF cold first pass, 1,600 realistic segments (no warm-up): %.2f ms".format(ms))
        assertTrue("cold first pass took $ms ms", ms < 1_500)
    }

    @Test
    fun realisticViewports_fromDefaultRadiusToTheMaximum() {
        val report = StringBuilder("SWEEP-PERF realistic mix, median of 5 runs after warm-up (JVM ms; an S9 is several times slower):\n")
        for (n in listOf(300, 1_600, 6_400)) {
            val segments = mixedSegments(n)
            val ms = timeMs { runAll(segments) }
            report.append("  %,5d segments: %7.2f ms  (%.1f us/segment)\n".format(n, ms, ms * 1000 / n))
            assertTrue("$n segments took $ms ms — far slower than expected", ms < 1_500)
        }
        println(report)
    }

    @Test
    fun worstCase_everySegmentIsWeekFiveOnly_soTheWholeSearchWindowIsWalked() {
        // No such schedule exists in the live feed, but an override could create one, and this is the
        // maximum work per call: up to 250 days of candidate dates for each segment.
        val segments = List(6_400) { sweepSegment(fullName = "Mon", fromHour = 8, toHour = 10, weeks = booleanArrayOf(false, false, false, false, true)) }
        val ms = timeMs { runAll(segments, worstCaseNow) }
        assertEquals(LocalDateTime.of(2033, 8, 29, 8, 0), NextSweepCalculator.nextSweepDateTime(segments.first(), worstCaseNow)) // really walks 210 days
        println("SWEEP-PERF worst case, 6,400 week-5-only segments: %.2f ms (%.1f us/segment)".format(ms, ms * 1000 / segments.size))
        assertTrue("worst case took $ms ms", ms < 5_000)
    }

    @Test
    fun aScheduleWithNoOccurrence_returnsNullQuickly_andDoesntWalkTheWindow() {
        // "HOLIDAY" has no weekday, so it returns before any date loop. A parseable schedule with every
        // week flag off walks the whole window once and returns null: also measured.
        val neverSwept = List(6_400) { sweepSegment(fullName = "Mon", weeks = booleanArrayOf(false, false, false, false, false)) }
        val ms = timeMs { runAll(neverSwept) }
        println("SWEEP-PERF 6,400 segments with no sweep at all (full 250-day walk each): %.2f ms".format(ms))
        assertTrue(ms < 5_000)
        neverSwept.take(5).forEach { assertEquals(null, NextSweepCalculator.nextSweepDateTime(it, now)) }
    }

    @Test
    fun costOfASingleWorstCaseCall_versusAnOrdinaryOne() {
        val ordinary = sweepSegment(fullName = "Monday")
        val worst = sweepSegment(fullName = "Mon", fromHour = 8, toHour = 10, weeks = booleanArrayOf(false, false, false, false, true))
        val n = 20_000
        val ordinaryMs = timeMs { repeat(n) { NextSweepCalculator.nextSweepDateTime(ordinary, now) } }
        val worstMs = timeMs { repeat(n) { NextSweepCalculator.nextSweepDateTime(worst, worstCaseNow) } }
        println("SWEEP-PERF per call: ordinary weekly %.2f us, 210-day worst case %.2f us".format(ordinaryMs * 1000 / n, worstMs * 1000 / n))
    }
}
