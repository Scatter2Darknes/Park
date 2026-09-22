package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How DebugControlReceiver reads its lat/lng extras. Scripts send strings (`--es`) because `am broadcast --ed` doesn't
 * exist on API 29; numeric extras must keep working for older callers. This lives in testDebug because the class under
 * test is in the debug source set (a release build doesn't contain it).
 */
class DebugCoordinateTest {

    @Test
    fun aStringIsParsed() {
        assertEquals(DebugCoordinate.Value(37.7802), DebugCoordinate.parse("37.7802"))
        assertEquals(DebugCoordinate.Value(-122.461), DebugCoordinate.parse("-122.461"))
    }

    @Test
    fun aStringWithSurroundingSpacesIsParsed() {
        assertEquals(DebugCoordinate.Value(-122.461), DebugCoordinate.parse("  -122.461 "))
    }

    @Test
    fun aDoubleExtraStillWorks() {
        assertEquals(DebugCoordinate.Value(37.7802), DebugCoordinate.parse(37.7802))
    }

    @Test
    fun otherNumericExtrasStillWork() {
        assertEquals(DebugCoordinate.Value(-122.0), DebugCoordinate.parse(-122))       // --ei
        assertEquals(DebugCoordinate.Value(37.5), DebugCoordinate.parse(37.5f))        // --ef
        assertEquals(DebugCoordinate.Value(38.0), DebugCoordinate.parse(38L))          // --el
    }

    @Test
    fun aMissingExtraIsMissing() {
        assertEquals(DebugCoordinate.Missing, DebugCoordinate.parse(null))
        assertEquals("lat is missing (send --es lat <degrees>)", DebugCoordinate.Missing.problem("lat"))
    }

    @Test
    fun garbageIsUnparsableAndNamesTheOffendingText() {
        for (garbage in listOf("abc", "", "   ", "37.7,802", "12.3.4", "north")) {
            val parsed = DebugCoordinate.parse(garbage)
            assertEquals("'$garbage'", DebugCoordinate.Unparsable(garbage), parsed)
            assertTrue(parsed.problem("lng")!!.contains("lng is not a number: '$garbage'"))
        }
    }

    @Test
    fun nanAndInfinityAreNotCoordinates() {
        for (bad in listOf<Any>("NaN", "Infinity", "-Infinity", Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("$bad", DebugCoordinate.parse(bad) is DebugCoordinate.Unparsable)
        }
    }

    @Test
    fun aGoodValueHasNoProblem() {
        assertNull(DebugCoordinate.parse("1.5").problem("lat"))
        assertNotNull(DebugCoordinate.parse("abc").problem("lat"))
    }
}
