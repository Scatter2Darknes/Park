package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which banner lines are about the city's data (shown once) versus a car's spot (shown per car). */
class BannerLinesTest {
    private val now = 1_790_000_000_000L
    private val newest = now - 60L * 24 * 3_600_000

    private fun car(id: Long, closure: ClosureStatus? = null, tow: TowStatus? = null) =
        CarWithStatus(Car(id = id, name = "Car $id"), null, null, closure, null, tow)

    private fun deadline() = TowDeadline(
        TowZone("T", null, null, ",1,", null, "FELL ST", null, null, 0, 0, 420, 1020, false, ALL_DAYS_MASK, null, null),
        now + 3_600_000, now + 7_200_000
    )

    @Test
    fun onlyDataStatusesAreFeedWide() {
        assertTrue(towLineIsFeedWide(TowStatus.Stale(newest)))
        assertTrue(towLineIsFeedWide(TowStatus.Unchecked))
        assertFalse(towLineIsFeedWide(TowStatus.Nearby(deadline())))
        assertFalse(towLineIsFeedWide(TowStatus.InEffect(deadline())))
        assertFalse(towLineIsFeedWide(TowStatus.Clear))
        assertFalse(towLineIsFeedWide(null))
        assertTrue(closureLineIsFeedWide(ClosureStatus.Unchecked))
        assertFalse(closureLineIsFeedWide(ClosureStatus.Clear))
        assertFalse(closureLineIsFeedWide(null))
    }

    @Test
    fun threeParkedCarsWithAStaleFeed_getTheLineOnce() {
        val cars = listOf(1L, 2L, 3L).map { car(it, tow = TowStatus.Stale(newest)) }
        assertEquals(listOf(towBannerText(TowStatus.Stale(newest), now)), feedWideBannerLines(cars, now))
    }

    @Test
    fun closureThenTow_eachOnce_carSpecificLinesLeftOut() {
        val cars = listOf(
            car(1, closure = ClosureStatus.Unchecked, tow = TowStatus.Unchecked),
            car(2, closure = ClosureStatus.Unchecked, tow = TowStatus.Nearby(deadline())), // car 2's tow line is its own
            car(3, closure = ClosureStatus.Clear, tow = TowStatus.Clear)
        )
        assertEquals(
            listOf(closureBannerText(ClosureStatus.Unchecked, now), towBannerText(TowStatus.Unchecked, now)),
            feedWideBannerLines(cars, now)
        )
    }

    @Test
    fun nothingFeedWide_noLines() {
        assertEquals(emptyList<String>(), feedWideBannerLines(listOf(car(1, ClosureStatus.Clear, TowStatus.Clear), car(2)), now))
    }
}
