package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * soonestDeadline() across every combination of the four deadline sources (docs/park-sources-refactor-spec.md,
 * Part B, Tests §2). Characterizes v1.04: the refactor moves this ranking onto the source registry and must
 * give the same answer for every row. Closure status is never a deadline, so it must never change the result.
 */
class SoonestDeadlineTableTest {
    private val base = LocalDateTime.of(2026, 10, 5, 12, 0)
    private fun ms(t: LocalDateTime) = t.atZone(SF_ZONE).toInstant().toEpochMilli()

    private val sweep = ms(base.plusHours(30))
    private val rpp = base.plusHours(20)
    private val meter = ms(base.plusHours(10))
    private val tow = ms(base.plusHours(40))

    private fun status(
        sweepAt: Long? = null, rppAt: LocalDateTime? = null, meterAt: Long? = null, towAt: Long? = null,
        parked: Boolean = true, closure: ClosureStatus? = null
    ): CarWithStatus {
        val car = Car(id = 1, name = "Civic")
        val p = if (!parked) null else ParkedState(
            carId = 1, segmentBlockSweepId = "S1", sideConfirmed = true, parkedLat = 0.0, parkedLng = 0.0,
            parkedAtMillis = 0, nextSweepAtMillis = sweepAt, meterTimerAtMillis = meterAt
        )
        val warning = rppAt?.let { RppWarning(setOf("A"), 2f, it) }
        return CarWithStatus(car, p, warning, closure, towAt, null)
    }

    @Test
    fun everyCombinationPicksTheEarliest() {
        // 16 rows: each source present or absent. Order of the fixtures: meter < rpp < sweep < tow.
        for (mask in 0 until 16) {
            val s = status(
                sweepAt = sweep.takeIf { mask and 1 != 0 },
                rppAt = rpp.takeIf { mask and 2 != 0 },
                meterAt = meter.takeIf { mask and 4 != 0 },
                towAt = tow.takeIf { mask and 8 != 0 }
            )
            val expected = when {
                mask and 4 != 0 -> CarDeadline(meter, DeadlineKind.METER)
                mask and 2 != 0 -> CarDeadline(ms(rpp), DeadlineKind.RPP)
                mask and 1 != 0 -> CarDeadline(sweep, DeadlineKind.SWEEP)
                mask and 8 != 0 -> CarDeadline(tow, DeadlineKind.TOW)
                else -> null
            }
            assertEquals("mask=$mask", expected, s.soonestDeadline())
        }
    }

    @Test
    fun towWinsWhenItIsSoonest() {
        val soon = ms(base.plusHours(1))
        assertEquals(CarDeadline(soon, DeadlineKind.TOW), status(sweepAt = sweep, rppAt = rpp, meterAt = meter, towAt = soon).soonestDeadline())
    }

    @Test
    fun aTieGoesToTheFirstInListOrder_sweepRppMeterTow() {
        val t = ms(base.plusHours(5))
        assertEquals(DeadlineKind.SWEEP, status(sweepAt = t, rppAt = base.plusHours(5), meterAt = t, towAt = t).soonestDeadline()!!.kind)
        assertEquals(DeadlineKind.RPP, status(rppAt = base.plusHours(5), meterAt = t, towAt = t).soonestDeadline()!!.kind)
        assertEquals(DeadlineKind.METER, status(meterAt = t, towAt = t).soonestDeadline()!!.kind)
    }

    @Test
    fun aPastDeadlineStillCounts() {
        // soonestDeadline doesn't filter by time: a deadline already passed is still the "soonest".
        val past = ms(base.minusHours(3))
        assertEquals(CarDeadline(past, DeadlineKind.SWEEP), status(sweepAt = past, towAt = tow).soonestDeadline())
    }

    @Test
    fun closureStatusIsNeverADeadline() {
        val hit = ClosureHit(
            StreetClosure("C1", null, null, null, "100", null, null, null, null, ms(base.plusMinutes(5)), ms(base.plusHours(2)),
                emptyList(), 0.0, 0.0),
            ClosureImpact.BLOCKED_IN, 0.0
        )
        assertEquals(null, status(closure = ClosureStatus.Affected(hit)).soonestDeadline())
        assertEquals(CarDeadline(tow, DeadlineKind.TOW), status(towAt = tow, closure = ClosureStatus.Affected(hit)).soonestDeadline())
        assertEquals(null, status(closure = ClosureStatus.Unchecked).soonestDeadline())
    }

    @Test
    fun notParked_onlyTheResolvedRppAndTowCount() {
        // With no parked row there's no sweep or meter; rpp/tow fields are only ever set for a parked car,
        // but soonestDeadline itself doesn't check that.
        assertEquals(null, status(parked = false).soonestDeadline())
        assertEquals(CarDeadline(tow, DeadlineKind.TOW), status(parked = false, towAt = tow).soonestDeadline())
    }
}
