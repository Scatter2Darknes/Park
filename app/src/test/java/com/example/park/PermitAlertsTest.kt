package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Parsing and the pure decisions behind the Public Works permit warnings (StreetUsePermitApi.kt, PermitAlerts.kt). */
class PermitAlertsTest {
    private fun ms(t: LocalDateTime) = t.atZone(SF_ZONE).toInstant().toEpochMilli()
    private val now = ms(LocalDateTime.of(2026, 9, 25, 12, 0))
    private val hour = 3_600_000L

    private fun permit(start: LocalDateTime, end: LocalDateTime, number: String = "26TOC-1", cnn: String = "100") = StreetUsePermit(
        rowKey = "${number}_$cnn", permitNumber = number, cnn = cnn, streetName = "16TH AVE", crossStreet1 = "TARAVAL ST",
        crossStreet2 = "ULLOA ST", purpose = null, status = "APPROVED", startMillis = ms(start), endMillis = ms(end)
    )

    @Test
    fun parsesRows_aDateOnlyEndMeansTheWholeDay_andDropsUnusableRows() {
        val json = """[
          {"permit_number":"26TOC-03357","cnn":"23178000","streetname":"16TH AVE","cross_street_1":"TARAVAL ST",
           "status":"APPROVED","permit_start_date":"2026-08-17T08:00:00.000","permit_end_date":"2026-09-30T17:00:00.000"},
          {"permit_number":"26TOC-1","cnn":"5.0","permit_start_date":"2026-10-09T00:00:00.000","permit_end_date":"2026-10-09T00:00:00.000"},
          {"permit_number":"no-cnn","permit_start_date":"2026-10-09T00:00:00.000","permit_end_date":"2026-10-10T00:00:00.000"},
          {"permit_number":"backwards","cnn":"7","permit_start_date":"2026-10-09T10:00:00.000","permit_end_date":"2026-10-09T09:00:00.000"},
          {"permit_number":"garbage","cnn":"8","permit_start_date":"soon","permit_end_date":"later"}
        ]"""
        val permits = parseStreetUsePermits(json, syncId = 42)
        assertEquals(listOf("26TOC-03357_23178000", "26TOC-1_5"), permits.map { it.rowKey })
        assertEquals(ms(LocalDateTime.of(2026, 9, 30, 17, 0)), permits[0].endMillis)
        assertEquals(42L, permits[0].lastSeenSyncId)
        // A date-only permit on Oct 9 covers all of Oct 9.
        assertEquals(ms(LocalDateTime.of(2026, 10, 9, 0, 0)), permits[1].startMillis)
        assertEquals(ms(LocalDateTime.of(2026, 10, 10, 0, 0)), permits[1].endMillis)
    }

    @Test
    fun theQueryIsCitywideTemporaryOccupancyWithoutDeadStatuses() {
        val where = permitWhereClause(java.time.LocalDate.of(2026, 9, 25))
        assertTrue(where.contains("permit_type = 'TempOccup'"))
        assertTrue(where.contains("permit_end_date >= '2026-09-25T00:00:00'"))
        assertTrue("blank statuses must be kept", where.contains("status IS NULL OR status NOT IN ('VOID','WITHDRAW','CANCELLED','CLOSED','EXPIRED')"))
        assertTrue("never filtered by place", !where.contains("cnn") && !where.contains("latitude"))
    }

    @Test
    fun statusPrefersInEffect_thenStartingWithinTheLeadTime() {
        val inEffect = permit(LocalDateTime.of(2026, 9, 24, 8, 0), LocalDateTime.of(2026, 9, 26, 17, 0), "A")
        val soon = permit(LocalDateTime.of(2026, 9, 26, 7, 0), LocalDateTime.of(2026, 9, 26, 15, 0), "B")
        val later = permit(LocalDateTime.of(2026, 10, 5, 7, 0), LocalDateTime.of(2026, 10, 5, 15, 0), "C")
        val lead = 48 * hour
        assertEquals(PermitStatus.Posted(inEffect, inEffect = true), permitStatusFor(listOf(later, soon, inEffect), now, true, lead))
        assertEquals(PermitStatus.Posted(soon, inEffect = false), permitStatusFor(listOf(later, soon), now, true, lead))
        assertEquals(PermitStatus.Clear, permitStatusFor(listOf(later), now, true, lead)) // too far out: the heads-up covers it
        assertEquals(PermitStatus.Unchecked, permitStatusFor(emptyList(), now, dataUsable = false, leadMillis = lead)) // never "clear"
        assertEquals(PermitStatus.Posted(soon, false), permitStatusFor(listOf(soon), now, dataUsable = false, leadMillis = lead)) // real even from old data
    }

    @Test
    fun theHeadsUpIsForTheSoonestPermitNotStartedYet() {
        val started = permit(LocalDateTime.of(2026, 9, 24, 8, 0), LocalDateTime.of(2026, 9, 26, 17, 0), "A")
        val b = permit(LocalDateTime.of(2026, 10, 1, 7, 0), LocalDateTime.of(2026, 10, 1, 15, 0), "B")
        val c = permit(LocalDateTime.of(2026, 9, 28, 7, 0), LocalDateTime.of(2026, 9, 28, 15, 0), "C")
        assertEquals(c, nextPermitToStart(listOf(started, b, c), now))
        assertNull(nextPermitToStart(listOf(started), now))
    }

    @Test
    fun periodTextReadsTheWayThePermitStatesIt() {
        assertEquals("Aug 17 – Sep 30, 8:00 AM – 5:00 PM",
            permitPeriodText(permit(LocalDateTime.of(2026, 8, 17, 8, 0), LocalDateTime.of(2026, 9, 30, 17, 0))))
        assertEquals("Oct 9", permitPeriodText(permit(LocalDateTime.of(2026, 10, 9, 0, 0), LocalDateTime.of(2026, 10, 10, 0, 0))))
        assertEquals("Oct 9 – Oct 11", permitPeriodText(permit(LocalDateTime.of(2026, 10, 9, 0, 0), LocalDateTime.of(2026, 10, 12, 0, 0))))
        assertEquals("Oct 9, 7:00 AM – 3:00 PM", permitPeriodText(permit(LocalDateTime.of(2026, 10, 9, 7, 0), LocalDateTime.of(2026, 10, 9, 15, 0))))
    }

    @Test
    fun bannerAndNoticeSayCheckSigns_neverMoveOrTowed() {
        val p = permit(LocalDateTime.of(2026, 9, 26, 7, 0), LocalDateTime.of(2026, 9, 26, 15, 0))
        val lines = listOf(
            permitBannerText(PermitStatus.Posted(p, inEffect = true))!!,
            permitBannerText(PermitStatus.Posted(p, inEffect = false))!!,
            permitInEffectContent("Civic", p).second
        )
        for (line in lines) {
            assertTrue(line, line.contains("check", ignoreCase = true))
            assertTrue(line, !line.contains("tow", ignoreCase = true) && !line.contains("move your car", ignoreCase = true))
        }
        assertEquals("No-parking permit check unavailable", permitBannerText(PermitStatus.Unchecked))
        assertNull(permitBannerText(PermitStatus.Clear))
        assertEquals("16TH AVE (TARAVAL ST to ULLOA ST)", permitPlaceLabel(p))
    }
}
