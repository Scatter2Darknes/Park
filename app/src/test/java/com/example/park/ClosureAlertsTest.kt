package com.example.park

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** When closure alerts fire, what the banner says, and that neither ever reads as "move or get towed". */
class ClosureAlertsTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    // Wed Sep 23 2026, 9:00 AM in San Francisco.
    private val now = ZonedDateTime.of(2026, 9, 23, 9, 0, 0, 0, SF_ZONE).toInstant().toEpochMilli()

    private fun closure(startIn: Long, lengthMillis: Long = 12 * hour, id: String = "c$startIn", impact: String? = "all-lanes-closed") =
        StreetClosure(
            objectId = id, caseNum = null, caseName = "Street Fair", type = "Special Event", cnn = "100",
            street = "VALENCIA ST", fromStreet = "16TH ST", toStreet = "17TH ST", vehicleImpact = impact,
            startMillis = now + startIn, endMillis = now + startIn + lengthMillis,
            points = emptyList(), centroidLat = 0.0, centroidLng = 0.0
        )

    private fun blocked(c: StreetClosure) = ClosureHit(c, ClosureImpact.BLOCKED_IN, 0.0)
    private fun nearby(c: StreetClosure) = ClosureHit(c, ClosureImpact.NEARBY, 150.0)

    // --- planClosureAlert ---

    @Test
    fun aClosureFarAhead_isScheduledTwoDaysBeforeItStarts() {
        val c = closure(startIn = 5 * day)
        assertEquals(ClosureAlertPlan.ScheduleAt(c, c.startMillis - 2 * day), planClosureAlert(listOf(blocked(c)), null, now))
    }

    @Test
    fun aClosureInsideTheLeadWindow_firesNow() {
        // The late-permit / parked-late case: less than 2 days left, so alert immediately rather than skip it.
        val c = closure(startIn = 20 * hour)
        assertEquals(ClosureAlertPlan.FireNow(c), planClosureAlert(listOf(blocked(c)), null, now))
    }

    @Test
    fun aClosureAlreadyUnderWay_firesNow() {
        val c = closure(startIn = -hour)
        assertEquals(ClosureAlertPlan.FireNow(c), planClosureAlert(listOf(blocked(c)), null, now))
    }

    @Test
    fun anAlreadyDeliveredClosure_isNotAlertedAgain() {
        val c = closure(startIn = 20 * hour)
        assertEquals(ClosureAlertPlan.None, planClosureAlert(listOf(blocked(c)), deliveredForMillis = c.startMillis, nowMillis = now))
    }

    @Test
    fun nearbyAndEndedClosures_neverNotify() {
        val near = closure(startIn = hour, id = "near")
        val ended = closure(startIn = -2 * day, lengthMillis = hour, id = "ended")
        assertEquals(ClosureAlertPlan.None, planClosureAlert(listOf(nearby(near), blocked(ended)), null, now))
    }

    @Test
    fun aDailyClosure_alertsForTheNextDayOnlyAfterTodaysHasEnded() {
        // A Shared Space closed 7 AM–10 PM every day. Today's (already alerted) runs until 10 PM tonight.
        val today = closure(startIn = -2 * hour, lengthMillis = 15 * hour, id = "today")
        val tomorrow = closure(startIn = 22 * hour, lengthMillis = 15 * hour, id = "tomorrow")
        val plan = planClosureAlert(listOf(blocked(today), blocked(tomorrow)), deliveredForMillis = today.startMillis, nowMillis = now)
        assertEquals(ClosureAlertPlan.ScheduleAt(tomorrow, today.endMillis), plan)
    }

    @Test
    fun theEarliestUndeliveredClosureGoesFirst() {
        val later = closure(startIn = 6 * day, id = "later")
        val sooner = closure(startIn = 4 * day, id = "sooner")
        val plan = planClosureAlert(listOf(blocked(later), blocked(sooner)), null, now) as ClosureAlertPlan.ScheduleAt
        assertEquals("sooner", plan.closure.objectId)
    }

    @Test
    fun anAlarmFiringSlightlyEarly_stillCountsAsDue() {
        val c = closure(startIn = 2 * day + 30_000L) // trigger is 30 s from now
        assertEquals(ClosureAlertPlan.FireNow(c), planClosureAlert(listOf(blocked(c)), null, now))
    }

    // --- the one-per-park nearby notice ---

    @Test
    fun nearbyOnPark_picksTheSoonestNearbyWithinTheLeadTime() {
        val soon = nearby(closure(startIn = 5 * hour, id = "soon"))
        val sooner = nearby(closure(startIn = hour, id = "sooner"))
        val tooFar = nearby(closure(startIn = 3 * day, id = "far"))
        assertEquals(sooner, pickNearbyToNotifyOnPark(listOf(soon, tooFar, sooner), now))
        assertNull(pickNearbyToNotifyOnPark(listOf(tooFar), now))
        assertNull(pickNearbyToNotifyOnPark(emptyList(), now))
    }

    @Test
    fun nearbyOnPark_includesOneAlreadyUnderWay_butNotOneThatEnded() {
        val underWay = nearby(closure(startIn = -hour, id = "on"))
        val ended = nearby(closure(startIn = -3 * hour, lengthMillis = hour, id = "ended"))
        assertEquals(underWay, pickNearbyToNotifyOnPark(listOf(ended, underWay), now))
    }

    @Test
    fun nearbyOnPark_staysQuietWhenABlockedInAlertCoversTheSpot() {
        val near = nearby(closure(startIn = hour, id = "near"))
        assertNull(pickNearbyToNotifyOnPark(listOf(near, blocked(closure(startIn = day, id = "block"))), now))
        // A blocked-in closure far off doesn't suppress it: its alert won't go out for days.
        assertEquals(near, pickNearbyToNotifyOnPark(listOf(near, blocked(closure(startIn = 5 * day, id = "later"))), now))
    }

    @Test
    fun nearbyNotice_wording() {
        val c = closure(startIn = 3 * day - hour)
        val (title, text) = closureNearbyContent("Civic", c, now)
        assertEquals("Civic: street closure nearby", title)
        assertEquals("VALENCIA ST (16TH ST to 17TH ST) is closed for Street Fair, Sat, Sep 26 at 8:00 AM – 8:00 PM. Check signs near your car.", text)
        assertFalse(text.contains("tow", ignoreCase = true))
    }

    // --- banner status ---

    @Test
    fun noHits_withFreshData_isClear_butWithOldOrNoData_isUnchecked() {
        assertEquals(ClosureStatus.Clear, closureStatusFor(emptyList(), dataUsable = true, nowMillis = now))
        assertEquals(ClosureStatus.Unchecked, closureStatusFor(emptyList(), dataUsable = false, nowMillis = now))
        assertEquals("Street-closure check unavailable", closureBannerText(ClosureStatus.Unchecked, now))
        assertNull(closureBannerText(ClosureStatus.Clear, now))
    }

    @Test
    fun dataAge_decidesUsable() {
        assertTrue(closureDataIsUsable(now - 2 * day, now))
        assertFalse(closureDataIsUsable(now - 4 * day, now))
        assertFalse(closureDataIsUsable(null, now))
    }

    @Test
    fun aRealHitIsShownEvenFromOldData() {
        val c = closure(startIn = 3 * day)
        assertEquals(ClosureStatus.Affected(blocked(c)), closureStatusFor(listOf(blocked(c)), dataUsable = false, nowMillis = now))
    }

    @Test
    fun blockedInIsShownAWeekAhead_nearbyOnlyWithinTheLeadTime() {
        val blockedIn6Days = blocked(closure(startIn = 6 * day, id = "b6"))
        val blockedIn8Days = blocked(closure(startIn = 8 * day, id = "b8"))
        val nearbyIn1Day = nearby(closure(startIn = day, id = "n1"))
        val nearbyIn3Days = nearby(closure(startIn = 3 * day, id = "n3"))
        assertEquals(ClosureStatus.Affected(blockedIn6Days), closureStatusFor(listOf(blockedIn6Days, nearbyIn1Day), true, now))
        assertEquals(ClosureStatus.Affected(nearbyIn1Day), closureStatusFor(listOf(blockedIn8Days, nearbyIn1Day), true, now))
        assertEquals(ClosureStatus.Clear, closureStatusFor(listOf(blockedIn8Days, nearbyIn3Days), true, now))
    }

    // --- wording ---

    @Test
    fun windowFormatting() {
        val sameDay = closure(startIn = 3 * day - hour, lengthMillis = 12 * hour) // Sat 8 AM – 8 PM
        assertEquals("Sat, Sep 26 at 8:00 AM – 8:00 PM", formatClosureWindow(sameDay, now))
        val overnight = closure(startIn = 2 * day + 7 * hour, lengthMillis = 2 * day) // Fri 4 PM – Sun 4 PM
        assertEquals("Fri, Sep 25 at 4:00 PM – Sun, Sep 27 at 4:00 PM", formatClosureWindow(overnight, now))
        val underWay = closure(startIn = -hour, lengthMillis = 5 * hour) // until 1 PM today
        assertEquals("until 1:00 PM", formatClosureWindow(underWay, now))
    }

    @Test
    fun bannerAndNotificationText_neverSayMoveOrTowed() {
        val c = closure(startIn = 3 * day - hour)
        val texts = listOf(
            closureBannerText(ClosureStatus.Affected(blocked(c)), now)!!,
            closureBannerText(ClosureStatus.Affected(nearby(c)), now)!!,
            closureAlertContent("Civic", c, atSavedLocation = false, nowMillis = now).let { it.first + " " + it.second },
            closureAlertContent("Civic", c, atSavedLocation = true, nowMillis = now).let { it.first + " " + it.second }
        )
        for (t in texts) {
            assertFalse(t, t.contains("tow", ignoreCase = true))
            assertFalse(t, t.contains("move your car or", ignoreCase = true))
            assertFalse(t, Regex("\\bmove (it|now)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t))
        }
        assertEquals(
            "⚠ Block closes Sat, Sep 26 at 8:00 AM – 8:00 PM — may not be able to drive out",
            texts[0]
        )
        assertTrue(texts[3].contains("You may not be able to move your car for a while."))
        assertEquals(
            "VALENCIA ST (16TH ST to 17TH ST) is closed for Street Fair, Sat, Sep 26 at 8:00 AM – 8:00 PM. You may not be able to drive out until it reopens.",
            closureAlertContent("Civic", c, atSavedLocation = false, nowMillis = now).second
        )
    }

    @Test
    fun collapsedBanner_prefersBlockedInOverNearbyOverUnchecked() {
        val c = closure(startIn = day)
        assertTrue(closureBannerRank(ClosureStatus.Affected(blocked(c))) < closureBannerRank(ClosureStatus.Affected(nearby(c))))
        assertTrue(closureBannerRank(ClosureStatus.Affected(nearby(c))) < closureBannerRank(ClosureStatus.Unchecked))
    }
}
