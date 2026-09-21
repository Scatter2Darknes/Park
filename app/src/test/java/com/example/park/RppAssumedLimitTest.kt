package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * F4: what the app does with RPP rows whose HRLIMIT is missing, and the junk zone "0". Fixtures mirror the
 * real feed's shapes (checked 2026-09-20): 5 "Time Limited" rows with no limit, 3 "Paid + Permit" rows with no
 * limit, 2 "No parking any time" rows with RPPAREA1 = "0", 59 "Pay or Permit" rows at 72 h.
 */
class RppAssumedLimitTest {

    private fun feature(id: Int, regulation: String, zone: String, hrLimit: String, days: String = "M-F", begin: Int = 800, end: Int = 1800) = """
        { "attributes": { "OBJECTID": $id, "REGULATION": "$regulation", "DAYS": "$days", "HRS_BEGIN": $begin, "HRS_END": $end,
                          "HRLIMIT": $hrLimit, "RPPAREA1": "$zone", "RPPAREA2": null, "RPPAREA3": null },
          "geometry": { "paths": [ [ [-122.4, 37.7], [-122.41, 37.71] ] ] } }
    """.trimIndent()

    private val parsed = parseRppRegulations(
        """{ "features": [
            ${feature(1, "Time Limited", "S", "null")},
            ${feature(2, "Time Limited", "Q", "0")},
            ${feature(3, "Time limited", "D", "null")},
            ${feature(4, "Time Limited", "A", "2.0")},
            ${feature(5, "Time Limited", "B", "4.0")},
            ${feature(6, "Paid + Permit", "Y", "null", days = "M-Sa", begin = 900)},
            ${feature(7, "Pay or Permit", "HV", "72.0", days = "M-Sa", begin = 900, end = 2100)},
            ${feature(8, "No parking any time", "0", "null", days = "", begin = 0, end = 0)}
        ] }""".trimIndent()
    ).associateBy { it.objectId }

    @Test
    fun timeLimitedRowsWithAMissingLimit_getTheAssumedLimit_andAreFlagged() {
        for (id in listOf("1", "2", "3")) { // JSON null, explicit 0, and a lower-case regulation name
            val r = parsed.getValue(id)
            assertEquals("row $id", RPP_ASSUMED_LIMIT_HOURS, r.hrLimit, 0f)
            assertEquals("row $id", true, r.limitAssumed)
        }
    }

    @Test
    fun rowsWithAPostedLimit_areUntouched() {
        assertEquals(2.0f, parsed.getValue("4").hrLimit, 0f)
        assertNull(parsed.getValue("4").limitAssumed)
        assertEquals(4.0f, parsed.getValue("5").hrLimit, 0f)
        assertNull(parsed.getValue("5").limitAssumed)
    }

    @Test
    fun meteredAndSeventyTwoHourRows_keepNoDeadline() {
        assertEquals(0f, parsed.getValue("6").hrLimit, 0f)   // "Paid + Permit", limit missing: metered, not a time limit
        assertNull(parsed.getValue("6").limitAssumed)
        assertEquals(72f, parsed.getValue("7").hrLimit, 0f)  // can't be exceeded in one day's window
    }

    @Test
    fun theJunkZoneZeroRow_isDropped() {
        assertFalse("8" in parsed)
        assertEquals(7, parsed.size)
        assertTrue(parsed.values.none { "0" in it.zoneLetters })
    }

    @Test
    fun anAssumedLimitProducesADeadline_andTheWarningSaysItIsAssumed() {
        val regulation = parsed.getValue("1")
        val car = Car(name = "Test")
        // Parked 5:59pm / 6:01pm / 8:00am, 8am-6pm window, assumed 2 h.
        val at559 = LocalDateTime.of(2026, 9, 14, 17, 59)
        val at601 = LocalDateTime.of(2026, 9, 14, 18, 1)
        val at8 = LocalDateTime.of(2026, 9, 14, 8, 0)
        val next10 = LocalDateTime.of(2026, 9, 15, 10, 0)
        assertEquals(next10, nextRppDeadline(regulation, car, at559, at559)?.moveByDateTime)
        assertEquals(next10, nextRppDeadline(regulation, car, at601, at601)?.moveByDateTime)
        val morning = nextRppDeadline(regulation, car, at8, at8)!!
        assertEquals(LocalDateTime.of(2026, 9, 14, 10, 0), morning.moveByDateTime)
        assertTrue(morning.limitAssumed)
        assertEquals("RPP Zone S (limit not posted — assumed 2 h; check signs)", rppZoneLabel(morning))
    }

    @Test
    fun aPostedLimitLabelIsJustTheZone_andBoundaryZonesAreJoined() {
        val posted = nextRppDeadline(parsed.getValue("4"), Car(name = "Test"), LocalDateTime.of(2026, 9, 14, 9, 0), LocalDateTime.of(2026, 9, 14, 9, 0))!!
        assertFalse(posted.limitAssumed)
        assertEquals("RPP Zone A", rppZoneLabel(posted))
        assertEquals("RPP Zone A/Q", rppZoneLabel(posted.copy(zoneLetters = setOf("Q", "A"))))
    }

    @Test
    fun aCarHoldingAPermitForTheZone_stillGetsNoDeadline_evenWithAnAssumedLimit() {
        val now = LocalDateTime.of(2026, 9, 14, 9, 0)
        assertNull(nextRppDeadline(parsed.getValue("1"), Car(name = "Test", permitZoneLetters = "S"), now, now))
    }
}
