package com.example.park

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Street sweeping and RPP schedules are San Francisco wall-clock times ("Monday 8am" means 8am in
 * San Francisco), so every calculation that compares a schedule to "now" has to use SF's clock — not
 * whatever time zone the phone happens to be set to. Someone who travels, or a phone left on a
 * different zone, would otherwise get reminders shifted by the offset between the two.
 *
 * Database timestamps (parkedAtMillis, nextSweepAtMillis, delivery markers) are absolute epoch
 * milliseconds and stay as they are; they're converted to and from SF wall-clock time with
 * `atZone(SF_ZONE)` / `Instant.atZone(SF_ZONE)`.
 *
 * Code that needs testable time already takes `now` / `from` as a parameter (e.g.
 * NextSweepCalculator.nextSweepDateTime, sweepStatus); [sfNow] is only ever the default value.
 */
val SF_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/** The current date and time in San Francisco. */
fun sfNow(): LocalDateTime = LocalDateTime.now(SF_ZONE)
