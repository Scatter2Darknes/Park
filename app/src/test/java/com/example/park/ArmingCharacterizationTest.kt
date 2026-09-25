package com.example.park

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Characterization tests for reminder arming (docs/park-sources-refactor-spec.md, Part B, Tests §1).
 *
 * They run the REAL v1.04 arming code (re-arm, reschedule, fresh park, the park-time step, unpark) against
 * a real Room database in memory, and record exactly what it leaves behind: every alarm (receiver, request
 * code, trigger time, extras), every notification on screen (id, channel, title, text), the parked row's
 * delivery markers, and the banner status loadCarsWithStatus reports. The CurbRestrictionSource refactor
 * must leave every one of these byte-for-byte the same, so these tests must stay green WITHOUT edits.
 *
 * Time: the app reads the real clock, which Robolectric can't freeze for app code. So fixtures are placed
 * relative to "now", days or hours away from any boundary, and expected values come from the fixture
 * (deadline minus the offset, etc.). Where a deadline depends on the calendar (a holiday can move a sweep
 * a week), the expected deadline comes from the same pure function the app uses (CurbSchedule,
 * nextRppDeadline, nextTowDeadline): those are not part of the refactor.
 *
 * @Config(application = Application::class): a plain Application instead of ParkApp, whose onCreate
 * starts background work (default car insert, data sync, Bluetooth) that would race these tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ArmingCharacterizationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private var now = 0L
    private lateinit var today: LocalDate

    // --- fixtures -------------------------------------------------------------------------------

    private val carId = 1L
    private val carName = "Civic"
    private val corridor = "FELL ST"

    /** The "sweep day" / RPP day used by most scenarios: three days out, far from any time-of-day edge. */
    private val sweepDay get() = today.plusDays(3)

    private val s1Points = listOf(LatLng(37.7800, -122.4600), LatLng(37.7800, -122.4590))
    private val s2Points = listOf(LatLng(37.7700, -122.4500), LatLng(37.7700, -122.4490))
    private val p2 = LatLng(37.7700, -122.4495) // on S2's line: a no-street park right on block 200

    private fun segment(id: String, cnn: String, fullName: String, fromHour: Int, toHour: Int, points: List<LatLng>) =
        StreetSegment(
            blockSweepId = id, cnn = cnn, corridor = corridor, limits = "A ST - B ST", cnnRightLeft = "L",
            blockSide = "North", fullName = fullName, fromHour = fromHour, toHour = toHour,
            week1 = true, week2 = true, week3 = true, week4 = true, week5 = true, holidays = false,
            points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
        )

    private fun dayName(day: DayOfWeek) = day.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun s1() = segment("S1", "100", dayName(sweepDay.dayOfWeek), 9, 11, s1Points)
    private fun s2() = segment("S2", "200", dayName(sweepDay.dayOfWeek), 9, 11, s2Points)

    private val rppDayAbbrev = mapOf(
        DayOfWeek.MONDAY to "M", DayOfWeek.TUESDAY to "Tu", DayOfWeek.WEDNESDAY to "W", DayOfWeek.THURSDAY to "Th",
        DayOfWeek.FRIDAY to "F", DayOfWeek.SATURDAY to "Sa", DayOfWeek.SUNDAY to "Su"
    )

    /** RPP on one day only (the sweep day), all day, 2 h limit: deadline that day at 02:00, whatever the time now. */
    private fun rpp(id: String, points: List<LatLng>): RppZoneRegulation {
        val d = rppDayAbbrev.getValue(sweepDay.dayOfWeek)
        return RppZoneRegulation(
            objectId = id, zoneLetters = "A", days = "$d-$d", hrsBegin = 0, hrsEnd = 2359, hrLimit = 2f,
            points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
        )
    }

    /** Tow zone 7 AM - 5 PM every day, tomorrow to the sweep day. */
    private fun tow(id: String, cnn: String) = TowZone(
        rowId = id, caseNumber = null, permitNumber = null, cnns = towCnnList(listOf(cnn)), address = null,
        streetName = corridor, fromStreet = null, toStreet = null,
        startEpochDay = today.plusDays(1).toEpochDay(), endEpochDay = sweepDay.toEpochDay(),
        startMinute = 7 * 60, endMinute = 17 * 60, allDay = false, daysMask = ALL_DAYS_MASK, daysText = null,
        enteredMillis = now
    )

    private fun closure(id: String, cnn: String, points: List<LatLng>, startMillis: Long) = StreetClosure(
        objectId = id, caseNum = "CASE-$id", caseName = "Street Fair", type = "Special Event", cnn = cnn,
        street = corridor, fromStreet = null, toStreet = null, vehicleImpact = null,
        startMillis = startMillis, endMillis = startMillis + 10 * HOUR,
        points = points, centroidLat = points.map { it.lat }.average(), centroidLng = points.map { it.lng }.average()
    )

    /** A closure that starts on a round minute, [hoursAhead] from now. */
    private fun closureStart(hoursAhead: Long) = (now + hoursAhead * HOUR) / MINUTE * MINUTE

    private fun parked(
        segmentId: String?, point: LatLng, nextSweep: Long?, rppId: String? = null, viaLocation: Long? = null
    ) = ParkedState(
        carId = carId, segmentBlockSweepId = segmentId, sideConfirmed = true, parkedLat = point.lat, parkedLng = point.lng,
        parkedAtMillis = parkedAt, nextSweepAtMillis = nextSweep, rppRegulationId = rppId, parkedViaSafeLocationId = viaLocation
    )

    private var parkedAt = 0L

    // --- expectations ---------------------------------------------------------------------------

    private fun millis(t: LocalDateTime) = t.atZone(SF_ZONE).toInstant().toEpochMilli()

    private fun sweepDeadline(seg: StreetSegment): Long = millis(CurbSchedule.nextSweepDateTime(listOf(seg))!!)

    private fun rppWarning(reg: RppZoneRegulation): RppWarning {
        val since = Instant.ofEpochMilli(parkedAt).atZone(SF_ZONE).toLocalDateTime()
        return nextRppDeadline(reg, Car(name = carName), since, sfNow())!!
    }

    private fun alarm(receiver: String, code: Int, at: Long, extras: Map<String, Any?>) =
        "alarm $receiver code=$code at=$at " + extras.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }

    private fun reminderAlarm(kind: ReminderKind, trigger: Long, deadline: Long, label: String) = alarm(
        "ParkingReminderReceiver", reminderRequestCode(carId, kind), trigger,
        mapOf("carId" to carId, "carName" to carName, "corridor" to label, "kind" to kind.name,
            "nextSweepAtMillis" to deadline, "parkedAtMillis" to parkedAt)
    )

    /** NORMAL/URGENT (or the RPP/TOW pair) at the default offsets (2 h, 15 min), plus the family's roll-forward. */
    private fun tiers(normal: ReminderKind, urgent: ReminderKind, roll: RollForwardKind, deadline: Long, label: String, rollAt: Long = deadline) =
        listOf(
            reminderAlarm(normal, deadline - 120 * MINUTE, deadline, label),
            reminderAlarm(urgent, deadline - 15 * MINUTE, deadline, label),
            rollAlarm(roll, rollAt)
        )

    private fun rollAlarm(kind: RollForwardKind, at: Long) = alarm(
        "ScheduleRollForwardReceiver",
        NotificationIds.forCar(carId, when (kind) {
            RollForwardKind.SWEEP -> NotificationIds.Purpose.ROLL_FORWARD_SWEEP
            RollForwardKind.RPP -> NotificationIds.Purpose.ROLL_FORWARD_RPP
            RollForwardKind.TOW -> NotificationIds.Purpose.ROLL_FORWARD_TOW
        }),
        at, mapOf("carId" to carId, "parkedAtMillis" to parkedAt, "rollKind" to kind.name)
    )

    private fun closureAlarm(at: Long) = alarm(
        "ClosureAlertReceiver", NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_ALERT), at,
        mapOf("carId" to carId, "parkedAtMillis" to parkedAt)
    )

    private fun notif(id: Int, channel: String, title: String, text: String) = "notification id=$id channel=$channel title=$title text=$text"

    private fun reminderNotif(kind: ReminderKind, deadline: Long, label: String, channel: String): String {
        val (title, text) = buildReminderContent(carName, label, deadline, kind)
        return notif(reminderNotificationId(carId, kind), channel, title, text)
    }

    private fun status(rpp: Long?, closure: String, towDeadline: Long?, tow: String, soonest: String?) =
        "status car=$carId rpp=$rpp closure=$closure towDeadline=$towDeadline tow=$tow soonest=$soonest"

    // --- snapshot ---------------------------------------------------------------------------------

    /** Everything a user (or Android) can observe after arming, as sorted lines. */
    private fun snapshot(withStatus: Boolean = true): String = runBlocking {
        val lines = mutableListOf<String>()
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        for (a in shadowOf(alarmManager).scheduledAlarms) {
            val pi = shadowOf(a.operation)
            val intent = pi.savedIntent
            val extras = intent.extras?.keySet()?.associateWith { intent.extras!!.get(it) } ?: emptyMap()
            lines += alarm(intent.component!!.shortClassName.substringAfterLast('.'), pi.requestCode, a.triggerAtTime, extras)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        for (sbn in notificationManager.activeNotifications) {
            val n = sbn.notification
            lines += notif(sbn.id, n.channelId, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
                n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        }
        db.parkedStateDao().getAll().forEach { p ->
            lines += "parked car=${p.carId} nextSweep=${p.nextSweepAtMillis} markers=" + listOf(
                p.normalDeliveredForMillis, p.urgentDeliveredForMillis, p.rppNormalDeliveredForMillis, p.rppUrgentDeliveredForMillis,
                p.closureDeliveredForMillis, p.towNormalDeliveredForMillis, p.towUrgentDeliveredForMillis, p.towAdvanceDeliveredForMillis
            ).joinToString(",")
        }
        if (withStatus) {
            for (c in loadCarsWithStatus(context).filter { it.parkedState != null }) {
                val rpp = c.rppDeadline?.moveByDateTime?.let { millis(it) }
                val closure = when (val s = c.closureStatus) {
                    is ClosureStatus.Affected -> "Affected(${s.hit.impact},${s.hit.closure.objectId})"
                    else -> s?.javaClass?.simpleName
                }
                val tow = when (val s = c.towStatus) {
                    is TowStatus.InEffect -> "InEffect(${s.deadline.startMillis})"
                    is TowStatus.Nearby -> "Nearby(${s.deadline.startMillis})"
                    is TowStatus.Stale -> "Stale"
                    else -> s?.javaClass?.simpleName
                }
                lines += status(rpp, closure.toString(), c.towDeadlineMillis, tow.toString(),
                    c.soonestDeadline()?.let { "${it.kind}@${it.millis}" })
            }
        }
        lines.sorted().joinToString("\n")
    }

    private fun expect(vararg groups: Any) = groups.flatMap { if (it is List<*>) it.map(Any?::toString) else listOf(it.toString()) }
        .sorted().joinToString("\n")

    private fun parkedLine(nextSweep: Long?, vararg markers: Pair<String, Long>): String {
        val m = markers.toMap()
        val order = listOf("normal", "urgent", "rppNormal", "rppUrgent", "closure", "towNormal", "towUrgent", "towAdvance")
        return "parked car=$carId nextSweep=$nextSweep markers=" + order.joinToString(",") { m[it].toString() }
    }

    // --- setup ------------------------------------------------------------------------------------

    @Before
    fun setUp() { runBlocking {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase.replaceInstanceForTests(db)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        NotificationHelper.createChannel(context)

        now = System.currentTimeMillis()
        today = LocalDate.now(SF_ZONE)
        parkedAt = now - HOUR
        // Every setting the arming code reads, set explicitly (not left to defaults).
        context.dataStore.edit { it.clear() }
        val settings = SettingsRepository(context)
        settings.setNotificationOffsetMinutes(120)
        settings.setUrgentReminderEnabled(true)
        settings.setUrgentOffsetMinutes(15)
        settings.setClosureParkTimeCheck(true)
        settings.setClosureBackgroundSync(false)
        settings.setClosureAlertLeadHours(48)
        // Fresh data, so the park-time step never goes to the network and the banner can say "checked".
        settings.setClosuresLastSyncMillis(now)
        settings.setTowLastSyncMillis(now)
        settings.setTowNewestEntryMillis(now)
        db.carDao().insert(Car(id = carId, name = carName, isDefault = true))
    } }

    @After
    fun tearDown() {
        AppDatabase.replaceInstanceForTests(null)
    }

    private suspend fun awaitParkTimeStep() = ClosureCheckCenter.awaitFor(carId, 30_000)

    private suspend fun setClosures(parkTime: Boolean, background: Boolean) {
        SettingsRepository(context).setClosureParkTimeCheck(parkTime)
        SettingsRepository(context).setClosureBackgroundSync(background)
    }

    // --- re-arm scenarios ---------------------------------------------------------------------------

    @Test
    fun rearm_sweepOnly() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d))

        rearmAllActiveReminders(context)

        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            parkedLine(d),
            status(null, "Clear", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_recomputesAStaleStoredSweepDeadline() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], now - DAY)) // a sweep that has passed

        rearmAllActiveReminders(context)

        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            parkedLine(d),
            status(null, "Clear", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun reschedule_sweepAndRpp() = runBlocking {
        val seg = s1()
        val reg = rpp("R1", s1Points)
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        db.rppZoneRegulationDao().insertAll(listOf(reg))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d, rppId = "R1"))
        val w = rppWarning(reg)
        val m = millis(w.moveByDateTime)

        rescheduleAllActiveReminders(context)

        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            tiers(ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, RollForwardKind.RPP, m, rppZoneLabel(w), rppWindowEndMillis(reg, w.moveByDateTime)),
            parkedLine(d),
            status(m, "Clear", null, "Clear", if (m < d) "RPP@$m" else "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_towConfident_advanceInsideLeadFiresAtOnce_andIsNotRepostedByTheNextRearm() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        val zone = tow("T1", "100")
        db.towZoneDao().insertAll(listOf(zone))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d))
        val t = nextTowDeadline(listOf(zone), sfNow())!!.startMillis // tomorrow 7:00

        rearmAllActiveReminders(context)
        val first = snapshot()
        val expected = expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            tiers(ReminderKind.TOW_NORMAL, ReminderKind.TOW_URGENT, RollForwardKind.TOW, t, corridor),
            reminderNotif(ReminderKind.TOW_ADVANCE, t, corridor, NotificationHelper.CHANNEL_ID_NORMAL),
            parkedLine(d, "towAdvance" to t),
            status(null, "Clear", t, "Clear", if (t < d) "TOW@$t" else "SWEEP@$d")
        )
        assertEquals(expected, first)

        rearmAllActiveReminders(context) // delivered for this deadline: not posted again, nothing else changes
        assertEquals(expected, snapshot())
    }

    @Test
    fun rearm_towUncertain_armsNothing_bannerSaysNearby() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(s2()))
        val zone = tow("T2", "200")
        db.towZoneDao().insertAll(listOf(zone))
        db.parkedStateDao().upsert(parked(null, p2, null))
        val t = nextTowDeadline(listOf(zone), sfNow())!!.startMillis

        rearmAllActiveReminders(context)

        assertEquals(expect(
            parkedLine(null),
            status(null, "Clear", null, "Nearby($t)", null)
        ), snapshot())
    }

    @Test
    fun rearm_closureBlockedIn_scheduledAtLeadTime() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        val start = closureStart(72)
        db.streetClosureDao().insertAll(listOf(closure("C1", "100", s1Points, start)))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d))

        rearmAllActiveReminders(context)

        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            closureAlarm(start - 48 * HOUR),
            parkedLine(d),
            status(null, "Affected(BLOCKED_IN,C1)", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_closureBlockedIn_insideLeadFiresAtOnce() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        val c = closure("C1", "100", s1Points, closureStart(24))
        db.streetClosureDao().insertAll(listOf(c))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d))

        rearmAllActiveReminders(context)

        val (title, text) = closureAlertContent(carName, c, atSavedLocation = false, nowMillis = now)
        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            notif(NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_ALERT), NotificationHelper.CHANNEL_ID_NORMAL, title, text),
            parkedLine(d, "closure" to c.startMillis),
            status(null, "Affected(BLOCKED_IN,C1)", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_closureNearby_bannerOnly() = runBlocking {
        val seg = s1()
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        val north = s1Points.map { LatLng(it.lat + 0.0009, it.lng) } // ~100 m north, another block
        db.streetClosureDao().insertAll(listOf(closure("C2", "300", north, closureStart(24))))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d))

        rearmAllActiveReminders(context)

        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            parkedLine(d),
            status(null, "Affected(NEARBY,C2)", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_offStreetSavedLocation_onlyClosuresApply() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(s2()))
        db.rppZoneRegulationDao().insertAll(listOf(rpp("R2", s2Points)))
        db.towZoneDao().insertAll(listOf(tow("T2", "200")))
        val c = closure("C3", "200", s2Points, closureStart(72))
        db.streetClosureDao().insertAll(listOf(c))
        val locationId = db.savedLocationDao().insert(SavedLocation(name = "Garage", lat = p2.lat, lng = p2.lng, isSafeFromSweeping = true, isOffStreet = true))
        db.parkedStateDao().upsert(parked(null, p2, null, rppId = "R2", viaLocation = locationId))

        rearmAllActiveReminders(context)

        assertEquals(expect(
            closureAlarm(c.startMillis - 48 * HOUR),
            parkedLine(null),
            status(null, "Affected(BLOCKED_IN,C3)", null, "Clear", null)
        ), snapshot())
    }

    @Test
    fun reschedule_closuresAndTowSwitchedOff_cancelsTheirAlarmsAndNotices_keepsSweepAndRpp() = runBlocking {
        val seg = s1()
        val reg = rpp("R1", s1Points)
        val d = sweepDeadline(seg)
        db.streetSegmentDao().insertAll(listOf(seg))
        db.rppZoneRegulationDao().insertAll(listOf(reg))
        db.towZoneDao().insertAll(listOf(tow("T1", "100")))
        db.streetClosureDao().insertAll(listOf(closure("C1", "100", s1Points, closureStart(72))))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], d, rppId = "R1"))
        rescheduleAllActiveReminders(context) // tow + closure armed, tow advance posted
        val advanceMarker = nextTowDeadline(listOf(tow("T1", "100")), sfNow())!!.startMillis

        setClosures(parkTime = false, background = false) // one switch pair for both today
        rescheduleAllActiveReminders(context)

        val w = rppWarning(reg)
        val m = millis(w.moveByDateTime)
        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            tiers(ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, RollForwardKind.RPP, m, rppZoneLabel(w), rppWindowEndMillis(reg, w.moveByDateTime)),
            parkedLine(d, "towAdvance" to advanceMarker),
            status(m, "null", null, "null", if (m < d) "RPP@$m" else "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun rearm_everythingOff_armsNothing() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(segment("S1", "100", "Holiday", 9, 11, s1Points))) // no weekday: never swept
        db.parkedStateDao().upsert(parked("S1", s1Points[0], null))
        setClosures(parkTime = false, background = false)

        rearmAllActiveReminders(context)

        assertEquals(expect(parkedLine(null), status(null, "null", null, "null", null)), snapshot())
    }

    // --- fresh park (the save path is a known leftover this pass, but it is covered) ----------------

    @Test
    fun freshPark_sweepAndRpp_thenParkTimeStep() = runBlocking {
        val seg = s1()
        val reg = rpp("R1", s1Points)
        db.streetSegmentDao().insertAll(listOf(seg))
        db.rppZoneRegulationDao().insertAll(listOf(reg))

        saveParkedState(context, carId, seg, LatLng(seg.centroidLat, seg.centroidLng))
        awaitParkTimeStep()

        parkedAt = db.parkedStateDao().getForCar(carId)!!.parkedAtMillis
        val d = sweepDeadline(seg)
        val w = rppWarning(reg)
        val m = millis(w.moveByDateTime)
        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            tiers(ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, RollForwardKind.RPP, m, rppZoneLabel(w), rppWindowEndMillis(reg, w.moveByDateTime)),
            parkedLine(d),
            status(m, "Clear", null, "Clear", if (m < d) "RPP@$m" else "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun freshPark_whileSweepingIsInProgress_postsTheNoticeAtSaveTime_evenWithClosuresOff() = runBlocking {
        val seg = segment("S1", "100", dayName(today.dayOfWeek), 0, 23, s1Points)
        val inProgressEnd = CurbSchedule.sweepInProgressEnd(listOf(seg), sfNow())
        assumeTrue("needs a sweep in progress right now (not 11 PM, not a holiday)", inProgressEnd != null)
        db.streetSegmentDao().insertAll(listOf(seg))
        setClosures(parkTime = false, background = false)

        saveParkedState(context, carId, seg, LatLng(seg.centroidLat, seg.centroidLng))
        awaitParkTimeStep()

        parkedAt = db.parkedStateDao().getForCar(carId)!!.parkedAtMillis
        val d = sweepDeadline(seg) // next week's
        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            reminderNotif(ReminderKind.SWEEP_ACTIVE, millis(inProgressEnd!!), corridor, NotificationHelper.CHANNEL_ID_URGENT),
            parkedLine(d),
            status(null, "null", null, "null", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun freshPark_noStreet_rppFromSaveTime_andTheUncertainTowNoticeFromTheParkTimeStep() = runBlocking {
        db.streetSegmentDao().insertAll(listOf(s2()))
        val reg = rpp("R2", s2Points)
        db.rppZoneRegulationDao().insertAll(listOf(reg))
        val zone = tow("T2", "200")
        db.towZoneDao().insertAll(listOf(zone))

        saveUnmanagedParkedState(context, carId, p2)
        awaitParkTimeStep()

        parkedAt = db.parkedStateDao().getForCar(carId)!!.parkedAtMillis
        val w = rppWarning(reg)
        val m = millis(w.moveByDateTime)
        val t = nextTowDeadline(listOf(zone), sfNow())!!
        val (title, text) = towNearbyContent(carName, t, now)
        assertEquals(expect(
            tiers(ReminderKind.RPP_NORMAL, ReminderKind.RPP_URGENT, RollForwardKind.RPP, m, rppZoneLabel(w), rppWindowEndMillis(reg, w.moveByDateTime)),
            notif(NotificationIds.forCar(carId, NotificationIds.Purpose.TOW_NEARBY), NotificationHelper.CHANNEL_ID_NORMAL, title, text),
            parkedLine(null),
            status(m, "Clear", null, "Nearby(${t.startMillis})", "RPP@$m")
        ), snapshot())
    }

    private fun parkTimeStepWithNearbyClosure(parkTime: Boolean, background: Boolean) = runBlocking {
        setClosures(parkTime, background)
        val seg = s1()
        db.streetSegmentDao().insertAll(listOf(seg))
        val north = s1Points.map { LatLng(it.lat + 0.0009, it.lng) }
        val c = closure("C2", "300", north, closureStart(24))
        db.streetClosureDao().insertAll(listOf(c))

        saveParkedState(context, carId, seg, LatLng(seg.centroidLat, seg.centroidLng))
        awaitParkTimeStep()

        parkedAt = db.parkedStateDao().getForCar(carId)!!.parkedAtMillis
        val d = sweepDeadline(seg)
        val (title, text) = closureNearbyContent(carName, c, now)
        assertEquals(expect(
            tiers(ReminderKind.NORMAL, ReminderKind.URGENT, RollForwardKind.SWEEP, d, corridor),
            notif(NotificationIds.forCar(carId, NotificationIds.Purpose.CLOSURE_NEARBY), NotificationHelper.CHANNEL_ID_NORMAL, title, text),
            parkedLine(d),
            status(null, "Affected(NEARBY,C2)", null, "Clear", "SWEEP@$d")
        ), snapshot())
    }

    @Test
    fun parkTimeStep_parkTimeCheckOn_postsTheNearbyClosureNotice() = parkTimeStepWithNearbyClosure(parkTime = true, background = false)

    @Test
    fun parkTimeStep_parkTimeCheckOff_backgroundOn_stillPostsItFromStoredData() = parkTimeStepWithNearbyClosure(parkTime = false, background = true)

    // --- unpark --------------------------------------------------------------------------------------

    @Test
    fun unpark_cancelsEverything_includingBluetoothNoticesAndTheMeterTimer() = runBlocking {
        val seg = s1()
        db.streetSegmentDao().insertAll(listOf(seg))
        db.rppZoneRegulationDao().insertAll(listOf(rpp("R1", s1Points)))
        db.towZoneDao().insertAll(listOf(tow("T1", "100")))
        db.streetClosureDao().insertAll(listOf(closure("C1", "100", s1Points, closureStart(24))))
        db.parkedStateDao().upsert(parked("S1", s1Points[0], sweepDeadline(seg), rppId = "R1"))
        rescheduleAllActiveReminders(context)
        scheduleMeterTimer(context, carId, carName, "meter", now + HOUR)
        for (purpose in listOf(NotificationIds.Purpose.BLUETOOTH_AUTO_DETECT, NotificationIds.Purpose.BLUETOOTH_AUTO_UNPARK)) {
            NotificationManagerCompat.from(context).notify(
                NotificationIds.forCar(carId, purpose),
                NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_STATUS)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("bt").build()
            )
        }
        // Sanity: the setup really did arm and post things, so "nothing left" below means something.
        assertEquals(true, snapshot(withStatus = false).lines().size > 8)

        unsubscribeParking(context, carId)

        assertNull(db.parkedStateDao().getForCar(carId))
        assertEquals("", snapshot())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
