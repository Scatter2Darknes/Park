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
            carHits = emptyList(), nowMillis = now
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
            carHits = emptyList(), nowMillis = now
        )
        assertEquals(setOf("1", "2"), blocks.map { it.cnn }.toSet())
    }

    @Test
    fun theCutOff_isTheEndOfTheLabelledDay_notSevenDaysFromNow() {
        // Now: Wed Sep 23, 9 AM. The label says "through Sep 30", so ALL of Sep 30 is covered.
        val sep30At8pm = ZonedDateTime.of(2026, 9, 30, 20, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        val oct1At0030 = ZonedDateTime.of(2026, 10, 1, 0, 30, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        val blocks = groupClosuresForMap(
            listOf(closure("sep30evening", cnn = "1", startIn = sep30At8pm - now), closure("oct1", cnn = "2", startIn = oct1At0030 - now)),
            carHits = emptyList(), nowMillis = now
        )
        assertEquals(listOf("1"), blocks.map { it.cnn }) // the evening of the labelled day is in; the next day isn't
        assertEquals(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli(), closureMapHorizonEndMillis(now))
    }

    @Test
    fun theLabel_andCutOff_followSanFranciscoDates() {
        // 11:30 PM Sep 23 in SF is already Sep 24 in UTC; the label must still follow SF's calendar.
        val lateEvening = ZonedDateTime.of(2026, 9, 23, 23, 30, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        assertEquals("Closures through Sep 30", closureHorizonLabel(lateEvening))
        // Just after SF midnight, a new day: the window moves on by one.
        val justAfterMidnight = ZonedDateTime.of(2026, 9, 24, 0, 5, 0, 0, SF_ZONE).toInstant().toEpochMilli()
        assertEquals("Closures through Oct 1", closureHorizonLabel(justAfterMidnight))
    }

    @Test
    fun theParkedCarsClosure_isAlwaysDrawnAndFlagged_evenWithTheLayerOff() {
        val mine = closure("mine", cnn = "9", startIn = 3 * day)
        val mineHit = ClosureHit(mine, ClosureImpact.BLOCKED_IN, 0.0)
        val blocks = groupClosuresForMap(layerClosures = emptyList(), carHits = listOf(mineHit), nowMillis = now)
        assertTrue(blocks.single().affectsParkedCar)
        // With the layer on as well, the same row isn't drawn twice, and other blocks aren't flagged.
        val both = groupClosuresForMap(listOf(mine, closure("other", cnn = "8")), listOf(mineHit), now)
        assertEquals(2, both.size)
        assertTrue(both.first { it.cnn == "9" }.affectsParkedCar)
        assertFalse(both.first { it.cnn == "8" }.affectsParkedCar)
    }

    @Test
    fun theDialogNote_tellsTheCarsBlockFromNearbyFromNeither() {
        val onBlock = closure("b", cnn = "1")
        val near = closure("n", cnn = "2")
        val blocks = groupClosuresForMap(
            listOf(onBlock, near, closure("x", cnn = "3")),
            listOf(ClosureHit(onBlock, ClosureImpact.BLOCKED_IN, 0.0), ClosureHit(near, ClosureImpact.NEARBY, 150.0)),
            now
        ).associateBy { it.cnn }
        assertEquals(ClosureImpact.BLOCKED_IN, blocks.getValue("1").carImpact)
        assertEquals(ClosureImpact.NEARBY, blocks.getValue("2").carImpact)
        assertEquals(null, blocks.getValue("3").carImpact)
        assertTrue(closureDetailNote(blocks.getValue("1")).startsWith("This one is on your car's block"))
        assertTrue(closureDetailNote(blocks.getValue("2")).startsWith("This one is near where your car is parked"))
        // Only the car's own block may say it could keep the car from driving out.
        assertFalse(closureDetailNote(blocks.getValue("2")).contains("drive out"))
        assertTrue(closureDetailNote(blocks.getValue("3")).startsWith("Not a ticket risk"))
    }

    @Test
    fun aBlockThatIsOneCarsBlockAndAnotherCarsNeighbour_countsAsTheCarsBlock() {
        val c = closure("c", cnn = "1")
        val block = groupClosuresForMap(
            emptyList(),
            listOf(ClosureHit(c, ClosureImpact.NEARBY, 150.0), ClosureHit(c, ClosureImpact.BLOCKED_IN, 0.0)),
            now
        ).single()
        assertEquals(ClosureImpact.BLOCKED_IN, block.carImpact)
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
            carClosuresForMap(listOf(blockedIn6d, blockedIn9d, nearbyIn1d, nearbyIn3d), now, lead).map { it.closure.objectId }
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
