package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Which closures the map shows, how recurring rows merge, and the layer's label, badge and details. */
class ClosureMapLayerTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    // Wed Sep 23 2026, 9:00 AM in San Francisco.
    private val now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()
    private val line = listOf(LatLng(37.76, -122.42), LatLng(37.76, -122.419))

    private fun closure(
        id: String,
        cnn: String = "100",
        startIn: Long = day,
        length: Long = 12 * hour,
        points: List<LatLng> = line,
        impact: String? = "all-lanes-closed"
    ) = StreetClosure(
        objectId = id, caseNum = "C-$cnn", caseName = "Street Fair", type = "Special Event", cnn = cnn,
        street = "VALENCIA ST", fromStreet = "16TH ST", toStreet = "17TH ST", vehicleImpact = impact,
        startMillis = now + startIn, endMillis = now + startIn + length, points = points,
        centroidLat = 37.76, centroidLng = -122.4195
    )

    @Test
    fun recurringRowsOnOneBlock_mergeIntoOneBlock_soonestFirst() {
        val blocks = groupClosuresForMap(
            listOf(closure("d3", startIn = 3 * day), closure("d1", startIn = day), closure("d2", startIn = 2 * day)),
            carClosures = emptyList(), nowMillis = now
        )
        assertEquals(1, blocks.size)
        assertEquals(listOf("d1", "d2", "d3"), blocks.single().closures.map { it.objectId })
        assertEquals("d1", blocks.single().next.objectId)
    }

    @Test
    fun theLayerKeepsOnlyTheWeekAhead_andDropsEndedOnes() {
        val blocks = groupClosuresForMap(
            listOf(
                closure("soon", cnn = "1", startIn = 2 * day),
                closure("onNow", cnn = "2", startIn = -hour),
                closure("nextMonth", cnn = "3", startIn = 20 * day),
                closure("ended", cnn = "4", startIn = -2 * day, length = hour)
            ),
            carClosures = emptyList(), nowMillis = now
        )
        assertEquals(setOf("1", "2"), blocks.map { it.cnn }.toSet())
    }

    @Test
    fun theParkedCarsClosure_isAlwaysDrawnAndFlagged_evenWithTheLayerOff() {
        val mine = closure("mine", cnn = "9", startIn = 3 * day)
        val blocks = groupClosuresForMap(layerClosures = emptyList(), carClosures = listOf(mine), nowMillis = now)
        assertTrue(blocks.single().affectsParkedCar)
        // With the layer on as well, the same row isn't drawn twice, and other blocks aren't flagged.
        val both = groupClosuresForMap(listOf(mine, closure("other", cnn = "8")), listOf(mine), now)
        assertEquals(2, both.size)
        assertTrue(both.first { it.cnn == "9" }.affectsParkedCar)
        assertFalse(both.first { it.cnn == "8" }.affectsParkedCar)
    }

    @Test
    fun aBlockWithoutGeometry_cantBeDrawn() {
        assertTrue(groupClosuresForMap(listOf(closure("x", points = emptyList())), emptyList(), now).isEmpty())
    }

    @Test
    fun carClosures_useTheBannersWindows() {
        val lead = 2 * day
        val blockedIn6d = ClosureHit(closure("b6", startIn = 6 * day), ClosureImpact.BLOCKED_IN, 0.0)
        val blockedIn9d = ClosureHit(closure("b9", startIn = 9 * day), ClosureImpact.BLOCKED_IN, 0.0)
        val nearbyIn1d = ClosureHit(closure("n1", startIn = day), ClosureImpact.NEARBY, 150.0)
        val nearbyIn3d = ClosureHit(closure("n3", startIn = 3 * day), ClosureImpact.NEARBY, 150.0)
        assertEquals(
            listOf("b6", "n1"),
            carClosuresForMap(listOf(blockedIn6d, blockedIn9d, nearbyIn1d, nearbyIn3d), now, lead).map { it.objectId }
        )
    }

    @Test
    fun label_badge_andDetails() {
        assertEquals("Closures through Sep 30", closureHorizonLabel(now))
        val upcoming = groupClosuresForMap(listOf(closure("a", startIn = 2 * day)), emptyList(), now).single()
        assertEquals("🚧 2d", closureBadgeText(upcoming, now))
        val onNow = groupClosuresForMap(listOf(closure("b", startIn = -hour)), emptyList(), now).single()
        assertEquals("🚧 now", closureBadgeText(onNow, now))

        val daily = groupClosuresForMap((1..7).map { closure("d$it", startIn = it * day - 2 * hour) }, emptyList(), now).single()
        val lines = closureDetailLines(daily, now)
        assertEquals("VALENCIA ST (16TH ST to 17TH ST)", lines[0])
        assertEquals("Street Fair · Special Event", lines[1])
        assertEquals("Closed to traffic", lines[2])
        assertEquals(5, lines.count { it.startsWith("• ") })
        assertEquals("+2 more", lines.last()) // 7 daily windows: 5 listed, 2 more
        assertTrue(lines.none { it.contains("tow", ignoreCase = true) })
    }

    @Test
    fun partialClosures_sayWhatIsClosed() {
        val block = groupClosuresForMap(listOf(closure("p", impact = "some-lanes-closed")), emptyList(), now).single()
        assertEquals("Some lanes closed", closureDetailLines(block, now)[2])
    }
}
