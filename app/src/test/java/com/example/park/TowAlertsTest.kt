package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The pure tow-zone decisions in TowAlerts.kt: deadlines, the advance alert, and the banner status (incl. a stale feed). */
class TowAlertsTest {

    private fun zone(
        id: String, start: LocalDate, end: LocalDate, startMinute: Int = 7 * 60, endMinute: Int = 17 * 60,
        days: String? = "Monday - Friday"
    ) = TowZone(
        rowId = id, caseNumber = null, permitNumber = null, cnns = ",1,", address = null, streetName = "FELL ST",
        fromStreet = null, toStreet = null, startEpochDay = start.toEpochDay(), endEpochDay = end.toEpochDay(),
        startMinute = startMinute, endMinute = endMinute, allDay = false, daysMask = parseTowDays(days),
        daysText = days, enteredMillis = null
    )

    private fun millis(t: LocalDateTime) = t.atZone(SF_ZONE).toInstant().toEpochMilli()

    private val mon = LocalDate.of(2026, 9, 28) // a Monday
    private val lead = 48 * 60 * 60_000L

    // --- Deadlines ---

    @Test
    fun deadline_isTheSoonestWindowStartAcrossZones() {
        val later = zone("a", mon.plusDays(7), mon.plusDays(11))
        val sooner = zone("b", mon.plusDays(2), mon.plusDays(4), startMinute = 9 * 60)
        val d = nextTowDeadline(listOf(later, sooner), LocalDateTime.of(2026, 9, 26, 12, 0))!!
        assertEquals("b", d.zone.rowId)
        assertEquals(millis(LocalDateTime.of(2026, 9, 30, 9, 0)), d.startMillis)
    }

    @Test
    fun deadline_isStableUntilItPasses_thenRollsToTheNextWindow() {
        val z = zone("a", mon, mon.plusDays(4))
        val before = nextTowDeadline(listOf(z), LocalDateTime.of(2026, 9, 28, 6, 0))!!.startMillis
        assertEquals(before, nextTowDeadline(listOf(z), LocalDateTime.of(2026, 9, 28, 6, 59))!!.startMillis)
        // Once Monday 7am has passed (the car is in the window), the next deadline is Tuesday 7am.
        assertEquals(millis(LocalDateTime.of(2026, 9, 29, 7, 0)), nextTowDeadline(listOf(z), LocalDateTime.of(2026, 9, 28, 7, 30))!!.startMillis)
    }

    @Test
    fun noZonesOrAllOver_meansNoDeadline() {
        assertNull(nextTowDeadline(emptyList(), LocalDateTime.of(2026, 9, 28, 6, 0)))
        assertNull(nextTowDeadline(listOf(zone("a", mon, mon)), LocalDateTime.of(2026, 9, 28, 18, 0)))
    }

    @Test
    fun activeWindow_onlyInsideEnforcement() {
        val z = zone("a", mon, mon.plusDays(4))
        assertEquals(millis(LocalDateTime.of(2026, 9, 28, 17, 0)), activeTowWindow(listOf(z), LocalDateTime.of(2026, 9, 28, 12, 0))!!.endMillis)
        assertNull(activeTowWindow(listOf(z), LocalDateTime.of(2026, 9, 28, 18, 0)))
    }

    // --- Advance alert ---

    @Test
    fun advance_targetsAZonesFirstWindowOnly_notEveryDay() {
        val z = zone("a", mon, mon.plusDays(4))
        assertEquals(millis(LocalDateTime.of(2026, 9, 28, 7, 0)), towAdvanceTarget(listOf(z), LocalDateTime.of(2026, 9, 25, 12, 0))!!.startMillis)
        // Tuesday, zone already under way: no advance alert (the daily reminders cover it).
        assertNull(towAdvanceTarget(listOf(z), LocalDateTime.of(2026, 9, 29, 12, 0)))
    }

    @Test
    fun advance_forALatePermit_isAlreadyDue_soItFiresAtOnce() {
        // Posted with a day's notice: the target is inside the 2-day lead, so trigger = start - lead is in the past,
        // which scheduleOrFireImmediately turns into an immediate notification.
        val now = LocalDateTime.of(2026, 9, 27, 7, 0)
        val target = towAdvanceTarget(listOf(zone("a", mon, mon)), now)!!
        assertTrue(target.startMillis - lead < millis(now))
        assertTrue(target.startMillis > millis(now))
    }

    // --- Staleness and the banner ---

    private val now = LocalDateTime.of(2026, 9, 24, 12, 0)
    private val nowMillis = millis(now)
    private val day = 24 * 60 * 60_000L

    @Test
    fun feedStaleness() {
        assertFalse(towFeedIsStale(nowMillis - 2 * day, nowMillis))
        assertTrue(towFeedIsStale(nowMillis - 8 * day, nowMillis))
        assertTrue("an unknown newest entry can't vouch for the feed", towFeedIsStale(null, nowMillis))
    }

    @Test
    fun noHit_freshData_isClear() {
        assertEquals(TowStatus.Clear, towStatusFor(emptyList(), now, dataUsable = true, newestEntryMillis = nowMillis - day, leadMillis = lead))
    }

    @Test
    fun noHit_staleFeed_isNeverClear() {
        val july20 = millis(LocalDateTime.of(2026, 7, 20, 17, 1))
        val status = towStatusFor(emptyList(), now, dataUsable = true, newestEntryMillis = july20, leadMillis = lead)
        assertEquals(TowStatus.Stale(july20), status)
        assertEquals("City tow-zone data may be out of date (no new permits since Jul 20) — check signs", towBannerText(status, nowMillis))
    }

    @Test
    fun noHit_noUsableSync_isUnchecked_evenIfTheFeedLooksFresh() {
        assertEquals(TowStatus.Unchecked, towStatusFor(emptyList(), now, dataUsable = false, newestEntryMillis = nowMillis, leadMillis = lead))
    }

    @Test
    fun confidentUpcoming_withAStaleFeed_stillWarnsTheDataIsOld() {
        // The deadline shows through soonestDeadline; the status line still says a second zone could be missing.
        val hit = TowHit(zone("a", mon, mon.plusDays(4)), TowMatch.CONFIDENT)
        assertTrue(towStatusFor(listOf(hit), now, dataUsable = true, newestEntryMillis = null, leadMillis = lead) is TowStatus.Stale)
    }

    @Test
    fun confidentInForce_isInEffect_evenFromOldData() {
        val inForceNow = zone("a", LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 25)) // Thursday noon is inside 7-5
        val status = towStatusFor(listOf(TowHit(inForceNow, TowMatch.CONFIDENT)), now, dataUsable = false, newestEntryMillis = null, leadMillis = lead)
        assertTrue(status is TowStatus.InEffect)
        assertEquals("⚠ Tow-away zone in effect now until 5:00 PM — move your car", towBannerText(status, nowMillis))
    }

    @Test
    fun uncertain_withinLead_isNearby_butBeyondLeadFallsThrough() {
        val soon = TowHit(zone("a", LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 25)), TowMatch.UNCERTAIN)
        assertTrue(towStatusFor(listOf(soon), now, true, nowMillis, lead) is TowStatus.Nearby)
        val farOff = TowHit(zone("b", mon.plusDays(7), mon.plusDays(8)), TowMatch.UNCERTAIN)
        assertEquals(TowStatus.Clear, towStatusFor(listOf(farOff), now, true, nowMillis, lead))
    }

    @Test
    fun bannerRanks_putInEffectFirst() {
        val ranks = listOf<TowStatus>(TowStatus.Clear, TowStatus.Stale(null), TowStatus.Unchecked).map { towBannerRank(it) }
        assertEquals(ranks.sortedDescending(), ranks)
        assertNull(towBannerText(TowStatus.Clear, nowMillis))
    }
}
