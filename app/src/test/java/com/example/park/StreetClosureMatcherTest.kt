package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a closure is classified for a parked car. Geometry is a straight east-west block about 100 m
 * long, so distances are easy to reason about: 0.0001° of latitude ≈ 11 m.
 */
class StreetClosureMatcherTest {

    private val west = LatLng(37.7600, -122.4200)
    private val east = LatLng(37.7600, -122.4189) // ~97 m east at this latitude

    private fun closure(
        cnn: String = "100",
        impact: String? = "all-lanes-closed",
        points: List<LatLng> = listOf(west, east),
        start: Long = 1_000L,
        id: String = "c-$cnn-$start"
    ) = StreetClosure(
        objectId = id, caseNum = null, caseName = null, type = null, cnn = cnn,
        street = null, fromStreet = null, toStreet = null, vehicleImpact = impact,
        startMillis = start, endMillis = start + 3_600_000L, points = points,
        centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    private val midBlockCurb = LatLng(37.76010, -122.41945) // ~11 m north of the centerline, mid-block

    // --- Known block (matched street segment): the CNN decides ---

    @Test
    fun sameCnn_fullClosure_isBlockedIn() {
        assertEquals(ClosureImpact.BLOCKED_IN, classifyClosure(closure(), parkedCnn = "100", point = midBlockCurb)?.impact)
    }

    @Test
    fun sameCnn_partialClosure_isOnlyNearby() {
        assertEquals(ClosureImpact.NEARBY, classifyClosure(closure(impact = "some-lanes-closed"), "100", midBlockCurb)?.impact)
        assertEquals(ClosureImpact.NEARBY, classifyClosure(closure(impact = "all-lanes-open"), "100", midBlockCurb)?.impact)
    }

    @Test
    fun sameCnn_withNoGeometry_isStillBlockedIn() {
        val hit = classifyClosure(closure(points = emptyList()), "100", midBlockCurb)
        assertEquals(ClosureImpact.BLOCKED_IN, hit?.impact)
    }

    @Test
    fun differentCnn_isNeverBlockedIn_evenRightNextToTheLine() {
        // Known block wins over geometry: a car on the next block over is not blocked in.
        assertEquals(ClosureImpact.NEARBY, classifyClosure(closure(cnn = "200"), parkedCnn = "100", point = midBlockCurb)?.impact)
    }

    // --- Unknown block (no segment, e.g. a safe location): geometry decides ---

    @Test
    fun noCnn_closeAndMidBlock_isBlockedIn() {
        assertEquals(ClosureImpact.BLOCKED_IN, classifyClosure(closure(), parkedCnn = null, point = midBlockCurb)?.impact)
    }

    @Test
    fun noCnn_aroundTheCorner_isOnlyNearby() {
        // On the cross street, ~11 m north of the block's west END: close to the line, but not on the block.
        val aroundTheCorner = LatLng(37.76010, -122.4200)
        val hit = classifyClosure(closure(), parkedCnn = null, point = aroundTheCorner)
        assertEquals(ClosureImpact.NEARBY, hit?.impact)
    }

    @Test
    fun noCnn_tooFarFromTheCenterline_isOnlyNearby() {
        val setBack = LatLng(37.76030, -122.41945) // ~33 m north, mid-block (e.g. a garage behind a building)
        assertEquals(ClosureImpact.NEARBY, classifyClosure(closure(), parkedCnn = null, point = setBack)?.impact)
    }

    @Test
    fun beyondTheNearbyRadius_isIgnored() {
        val farAway = LatLng(37.7630, -122.41945) // ~330 m north
        assertNull(classifyClosure(closure(), parkedCnn = null, point = farAway))
        assertNull(classifyClosure(closure(cnn = "200"), parkedCnn = "100", point = farAway))
    }

    @Test
    fun withinTheNearbyRadius_isNearby() {
        val twoBlocksish = LatLng(37.7615, -122.41945) // ~167 m north
        val hit = classifyClosure(closure(cnn = "200"), parkedCnn = "100", point = twoBlocksish)
        assertNotNull(hit)
        assertEquals(ClosureImpact.NEARBY, hit!!.impact)
        assertEquals(167.0, hit.distanceMeters, 3.0)
    }

    // --- Where the car is ---

    @Test
    fun matchOrigin_prefersThePin_thenTheChosenCurb_neverThePhonesStartingPoint() {
        val pin = LatLng(37.7700, -122.4100)
        val curb = LatLng(37.7800, -122.4300)
        val phone = LatLng(37.7600, -122.4200) // where the parking flow started (the user's GPS)
        assertEquals(pin, closureMatchOrigin(exactPin = pin, curbMidpoint = curb, parkedPoint = phone, pinToCurbMeters = 10.0))
        // "Pick manually" + "just highlight street": the car is on the chosen curb, not at the phone.
        assertEquals(curb, closureMatchOrigin(exactPin = null, curbMidpoint = curb, parkedPoint = phone))
        // Garage / no-street park: no curb, so the saved point is all there is.
        assertEquals(phone, closureMatchOrigin(exactPin = null, curbMidpoint = null, parkedPoint = phone))
    }

    @Test
    fun aPinFarFromTheChosenCurb_losesToTheCurb() {
        // "Keep both" after the parking flow warned: reminders follow the street, so closures do too.
        val farPin = LatLng(37.7700, -122.4100)
        val curb = LatLng(37.7800, -122.4300)
        assertEquals(curb, closureMatchOrigin(exactPin = farPin, curbMidpoint = curb, parkedPoint = farPin, pinToCurbMeters = 850.0))
        // A pin near the curb is the better point, so it's used.
        assertEquals(farPin, closureMatchOrigin(exactPin = farPin, curbMidpoint = curb, parkedPoint = farPin, pinToCurbMeters = 20.0))
        // At exactly the limit it still counts as agreeing.
        assertEquals(farPin, closureMatchOrigin(farPin, curb, farPin, pinToCurbMeters = PIN_FAR_FROM_CURB_METERS))
    }

    @Test
    fun distanceFormatting_forThePinStep() {
        assertEquals("850 m", formatDistanceMeters(853.0))
        assertEquals("50 m", formatDistanceMeters(57.0))
        assertEquals("1.2 km", formatDistanceMeters(1234.0))
    }

    @Test
    fun aClosureNextToThePhone_isNotNearby_whenTheCarIsOnAFarCurb() {
        // The reported bug: user stands next to a closure, parks the car ~1 km away via "pick manually".
        val phone = LatLng(37.76010, -122.41945) // ~11 m from the closure
        val farCurb = LatLng(37.7690, -122.4195)  // ~1 km north
        val origin = closureMatchOrigin(exactPin = null, curbMidpoint = farCurb, parkedPoint = phone)
        assertNull(classifyClosure(closure(cnn = "200"), parkedCnn = "300", point = origin))
    }

    // --- Ranking ---

    @Test
    fun blockedInComesFirst_thenSoonestStart() {
        val laterBlock = ClosureHit(closure(start = 5_000L), ClosureImpact.BLOCKED_IN, 0.0)
        val soonNearby = ClosureHit(closure(cnn = "200", start = 1_000L), ClosureImpact.NEARBY, 50.0)
        val soonBlock = ClosureHit(closure(start = 2_000L), ClosureImpact.BLOCKED_IN, 0.0)
        assertEquals(listOf(soonBlock, laterBlock, soonNearby), rankClosureHits(listOf(soonNearby, laterBlock, soonBlock)))
    }

    // --- Geometry helper ---

    @Test
    fun projection_reportsInteriorVersusEnd() {
        val line = listOf(west, LatLng(37.7600, -122.41945), east) // two pieces
        val mid = projectOntoPolyline(midBlockCurb, line)!!
        assertTrue(mid.interior)
        assertEquals(11.1, mid.distanceMeters, 0.5)
        // Clamped at the shared middle vertex is still interior; clamped at the first/last point is an end.
        assertTrue(projectOntoPolyline(LatLng(37.76010, -122.41945), line)!!.interior)
        assertFalse(projectOntoPolyline(LatLng(37.7600, -122.4210), line)!!.interior) // west of the line
        assertFalse(projectOntoPolyline(LatLng(37.7600, -122.4180), line)!!.interior) // east of the line
        assertNull(projectOntoPolyline(midBlockCurb, listOf(west)))
    }
}
