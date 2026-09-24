package com.example.park

/**
 * Every notification ID and per-car PendingIntent request code in the app, in one place.
 *
 * WHY ONE PLACE: two things that share an ID silently overwrite each other. That's exactly what
 * happened when the Bluetooth notifications used `carId + 2_000_000` and `carId + 3_000_000` — the
 * same numbers as the RPP reminders — so a Bluetooth notice and an RPP reminder for the same car
 * replaced one another (and, since both taps open MainActivity with the same request code, one could
 * overwrite the other's tap intent). Now, every id is `offset(purpose) + carId`, each purpose owns
 * a whole [SPAN]-sized range, and a test proves no two purposes can ever produce the same number.
 *
 * TWO NAMESPACES, ONE SCHEME. Notification IDs and PendingIntent request codes are separate
 * spaces (a request code only has to differ from other PendingIntents for the same target). The
 * same per-purpose number is used for both where a purpose has both, so it's easy to reason about.
 *
 * STABILITY: the first seven offsets are the values the app already used, unchanged, so alarms
 * scheduled by an earlier version keep matching the codes used to cancel or replace them. Only the
 * two Bluetooth purposes moved (they had collided with the RPP ones).
 */
object NotificationIds {
    /** Size of each purpose's range. A car id must fit below this or it would spill into the next purpose. */
    const val SPAN = 1_000_000

    /** Largest id a real car can have. The top of the range is reserved for the Settings test buttons. */
    const val MAX_REAL_CAR_ID = 998_999L

    /** Fake car id used by the Settings "test notification" buttons — inside the reserved top of the range, never a real car. */
    const val TEST_CAR_ID = 999_999L

    // Fixed notification IDs for the four Settings test buttons (see TEST_CAR_ID). They sit in the
    // reserved top of the first range, above MAX_REAL_CAR_ID, so no real car's reminder can share one.
    const val TEST_NORMAL = 999_001
    const val TEST_URGENT = 999_002
    const val TEST_RPP_NORMAL = 999_003
    const val TEST_RPP_URGENT = 999_004

    /** One entry per kind of thing that needs an id. Each owns the range [offset, offset + SPAN). */
    enum class Purpose(val offset: Int) {
        REMINDER_NORMAL(0),
        REMINDER_URGENT(1 * SPAN),
        RPP_NORMAL(2 * SPAN),
        RPP_URGENT(3 * SPAN),
        /** Request code only (an alarm, no notification): advances a parked car past a sweep. */
        ROLL_FORWARD_SWEEP(4 * SPAN),
        /** Request code only: advances a parked car past an RPP window. */
        ROLL_FORWARD_RPP(5 * SPAN),
        /** "Sweeping is in progress right now" one-off notice. */
        SWEEP_ACTIVE(6 * SPAN),
        /** Bluetooth "Did X just park?" / "Parked X automatically". Moved from 2M, which collided with RPP_NORMAL. */
        BLUETOOTH_AUTO_DETECT(7 * SPAN),
        /** Bluetooth "X unparked". Moved from 3M, which collided with RPP_URGENT. */
        BLUETOOTH_AUTO_UNPARK(8 * SPAN),
        /** A car's ParkedState was re-evaluated because a SavedLocation's safe-from-sweeping
         *  flag changed (edited or deleted) — see SavedLocationRecompute.kt. */
        SAVED_LOCATION_RECOMPUTE(9 * SPAN),
        /** Manual meter timer — a single user-typed deadline, no tiers, no delivery markers
         *  (see MeterTimer.kt). */
        METER_TIMER(10 * SPAN),
        /** Street-closure "blocked in" alert: its alarm's request code and its notification id
         *  (see ClosureAlerts.kt). One slot per car; alerts go out one closure at a time. */
        CLOSURE_ALERT(11 * SPAN),
        /** The one-per-park "street closure nearby" notice (see ClosureAlerts.kt). Its own id so it
         *  never replaces a "blocked in" alert for the same car. */
        CLOSURE_NEARBY(12 * SPAN)
    }

    /**
     * The id for [purpose] and [carId]. Throws (with a clear message) if [carId] is outside
     * `0 until SPAN`: an id that large would land inside another purpose's range and reintroduce the
     * very collision this object exists to prevent, so failing loudly beats colliding quietly.
     * Room's autoincrement would need a million cars to get there, so this never happens in practice;
     * a malformed intent (a receiver's `-1` default) is the realistic way to hit it — callers that read
     * a car id from an intent check for that first.
     */
    fun forCar(carId: Long, purpose: Purpose): Int {
        require(carId in 0 until SPAN) {
            "Car id $carId is outside 0 until $SPAN, so its notification/request-code range would overlap another purpose's"
        }
        return purpose.offset + carId.toInt()
    }
}
