package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parseRppRegulations against a captured-shape ArcGIS response containing the awkward values the
 * live feed produces: JSON nulls, a single space " " for "no value", and a null HRLIMIT.
 *
 * These run on the JVM with the real org.json library (see build.gradle.kts). One limit: Android's
 * own org.json turns a JSON null into the string "null" in optString, while this library returns
 * "", so the test can't reproduce that specific quirk — it checks that every null-ish input is
 * rejected, which is what the isNull() check (and the "null" text guard) exists to guarantee.
 */
class RppDataApiTest {

    private val fixture = """
    {
      "features": [
        {
          "attributes": { "OBJECTID": 101, "DAYS": "M-F", "HRS_BEGIN": 800, "HRS_END": 1800, "HRLIMIT": 2.0,
                          "RPPAREA1": "A", "RPPAREA2": null, "RPPAREA3": null },
          "geometry": { "paths": [ [ [-122.4194, 37.7749], [-122.4184, 37.7759] ] ] }
        },
        {
          "attributes": { "OBJECTID": 102, "DAYS": "M-Sa", "HRS_BEGIN": 900, "HRS_END": 2100, "HRLIMIT": null,
                          "RPPAREA1": "Q", "RPPAREA2": " ", "RPPAREA3": "NULL" },
          "geometry": { "paths": [ [ [-122.40, 37.78], [-122.41, 37.79] ] ] }
        },
        {
          "attributes": { "OBJECTID": 103, "DAYS": null, "HRS_BEGIN": 800, "HRS_END": 1800, "HRLIMIT": 1.0,
                          "RPPAREA1": "B", "RPPAREA2": "C", "RPPAREA3": "B" },
          "geometry": { "paths": [ [ [-122.45, 37.75], [-122.44, 37.76] ] ] }
        },
        {
          "attributes": { "OBJECTID": 104, "DAYS": "M-F", "HRS_BEGIN": 800, "HRS_END": 1800, "HRLIMIT": 2.0,
                          "RPPAREA1": " ", "RPPAREA2": null, "RPPAREA3": null },
          "geometry": { "paths": [ [ [-122.46, 37.74], [-122.45, 37.75] ] ] }
        }
      ]
    }
    """.trimIndent()

    private val parsed by lazy { parseRppRegulations(fixture).associateBy { it.objectId } }

    @Test
    fun jsonNullAreasAreDropped_soNoBogusZoneAppears() {
        assertEquals("A", parsed.getValue("101").zoneLetters)
    }

    @Test
    fun blankSpaceAndTheLiteralTextNullAreDroppedToo() {
        // RPPAREA2 = " " and RPPAREA3 = "NULL": only the real zone letter survives.
        assertEquals("Q", parsed.getValue("102").zoneLetters)
    }

    @Test
    fun duplicateAreasAreDeduplicated_andOrderIsKept() {
        assertEquals("B,C", parsed.getValue("103").zoneLetters)
    }

    @Test
    fun aFeatureWithNoRealZoneAtAllIsSkipped() {
        assertTrue("104" !in parsed)
        assertEquals(3, parsed.size)
    }

    @Test
    fun nullDaysBecomeAnEmptyString_notTheWordNull() {
        assertEquals("", parsed.getValue("103").days)
        assertEquals("M-F", parsed.getValue("101").days)
    }

    @Test
    fun nullHrLimitBecomesZero() {
        assertEquals(0f, parsed.getValue("102").hrLimit, 0f)
        assertEquals(2.0f, parsed.getValue("101").hrLimit, 0f)
    }

    @Test
    fun geometryIsReadAsLngLatAndCentroidIsTheAverage() {
        val r = parsed.getValue("101")
        assertEquals(2, r.points.size)
        assertEquals(37.7749, r.points[0].lat, 1e-9)
        assertEquals(-122.4194, r.points[0].lng, 1e-9)
        assertEquals((37.7749 + 37.7759) / 2, r.centroidLat, 1e-9)
    }

    @Test
    fun optCleanString_rejectsEveryNullish() {
        val obj = org.json.JSONObject("""{"a": null, "b": " ", "c": "null", "d": "NULL", "e": " x ", "f": ""}""")
        assertEquals(null, obj.optCleanString("a"))
        assertEquals(null, obj.optCleanString("b"))
        assertEquals(null, obj.optCleanString("c"))
        assertEquals(null, obj.optCleanString("d"))
        assertEquals("x", obj.optCleanString("e"))
        assertEquals(null, obj.optCleanString("f"))
        assertEquals(null, obj.optCleanString("missing"))
    }
}
