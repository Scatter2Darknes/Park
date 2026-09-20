# Park — Reliability & Polish Plan (Sep 2026)

Working document for Claude Code. Produced from a static code review plus an architecture discussion with Z (the developer). Decisions below are **made**; don't reopen them unless the code contradicts an assumption, in which case stop and say so.

## 0. How to work

- **One task at a time**, each on its own branch off `main` (`git switch -c <branch>`), small commits, then **stop and report** so Z can review and test on a device. Never start the next task unprompted and never merge to `main` yourself.
- **Verify before acting.** Anything marked **VERIFY** is a hypothesis from reading code, not a confirmed fact. Check it against the code (or ask Z to check logs or data) before building on it. If the code disagrees with this document, say so instead of forcing the document's version.
- **Z is new to Kotlin.** When you introduce a Kotlin or Android concept (e.g. `goAsync()`, `MigrationTestHelper`, sealed classes), explain it in a sentence or two and say why you chose it.
- **You probably can't run the app.** Z verifies on physical devices: S25 (main), S20, Tab S9, and a Galaxy S9 (API 29 = minSdk floor). Run `./gradlew testDebugUnitTest` where you can. For anything device-only, write clear manual test steps.
- Stack: Kotlin, Compose, Room (KSP), WorkManager, AlarmManager, DataStore, Glance widget, osmdroid. Package `com.example.park`. minSdk 29, targetSdk 37. Single APK, no build flavors. No Navigation Compose (enum screen switching in `MainActivity`).
- After any large edit to `MapScreen.kt`, `SettingsScreen.kt` or `MapUtils.kt`, do a brace-balance sanity check. Past edits caused cascading "Expecting a top level declaration" errors.
- Update the **Status** table below when a task is done.

### Status

| Task | Branch | State |
|---|---|---|
| T0 `holidays` flag check (Z runs a query) | n/a | not started |
| T1 Re-arm + delivery markers (Room v12) | `rearm-reminders` | not started |
| T2 Exact-alarm flow | `exact-alarm-flow` | not started |
| T3 Schedule recompute, roll-forward, active-window notice | `schedule-recompute` | not started |
| T4 Bluetooth notification channel + timeouts | `bt-notif-timeouts` | not started |
| T5 Tunnel detection | `tunnel-detection` | not started |
| T6 Sweep-logic tests | `sweep-tests` | not started |
| T7 RPP fixes | `rpp-fixes` | not started |
| T8 Stale-row cleanup | `stale-rows` | not started |
| T9 Build + hygiene | `build-hygiene` | not started |

### Invariants: do not regress

- **Notification action buttons** ("Snooze 10 min", "I moved my car") work now. An earlier version silently failed and the root cause was never identified. Keep the manifest `<receiver>` entries for `SnoozeReminderReceiver` and `DismissReminderReceiver` and the real (non-zero) action icons. **Every new `BroadcastReceiver` must be declared in `AndroidManifest.xml`**, and Z should test it on a clean (uninstall-first) install.
- File names `SnoozeReminderReciever.kt` and `DismissReminderReciver.kt` are misspelled but the class names are right. Don't rename them as a drive-by.
- Segment dedup by `cnn + cnnRightLeft` (most urgent wins) must happen **before** drawing.
- Use `reloadSegmentsAndMarkers`, not `loadAndDrawSegments` alone, or markers get buried under redrawn polylines.
- App theme is resolved once in `MainActivity`. `DrivingModeState` is the single, bidirectional source of truth for driving mode.
- `StreetSegmentRepository.refreshFromNetwork()` is guarded by a Mutex. `isFullySynced` derives from `lastRefreshMillis`, never from a row count.
- Sweep and RPP reminders are independent per car and both must keep working.
- Room migrations are real `Migration` objects. Never use destructive fallback from v5 onward. Commit the exported `schemas/*.json`.

## 1. Decisions log

| Topic | Decision |
|---|---|
| Boot receiver | `BOOT_COMPLETED` (not `LOCKED_BOOT_COMPLETED`; Room lives in credential-encrypted storage) |
| Receiver async work | `goAsync()` + coroutine, `finish()` in `finally`. No WorkManager, and no `setExpedited` (it crashes on API < 31 without `getForegroundInfo`) |
| Marker storage | Room columns on `parked_state`, not DataStore (lifetime is tied to the row) |
| Force-stop | Cannot be recovered until the app is next opened. Document as a known limitation |
| Snooze + reboot | A snoozed alarm lost to a reboot inside the 10 min window is accepted. Comment it, don't fix it |
| Exact alarms | Keep `SCHEDULE_EXACT_ALARM`, ask from Settings, warn in-app, fall back to inexact. `USE_EXACT_ALARM` was considered and rejected (Play policy) |
| Informational BT notifications | New low-importance channel + `setTimeoutAfter` |
| Tunnel detection | Speed gate + reset + cooldown + setting, keep ~20 s gap threshold |
| API keys | Accept the current setup for a trusted tester; rotate keys only if the APK or zip leaks. No code task |
| R8 | Shrink only, `-dontobfuscate` (crash-handler traces must stay readable) |

## T0. `holidays` flag check (Z does this, not you)

`SfHolidayCalendar.holidayName` assumes `StreetSegment.holidays == true` means a nightly/7-day route that is suspended only on New Year's, Thanksgiving and Christmas, while `false` means a weekday-daytime route suspended on the full holiday list. That mapping is an inference. Z should open this in a **browser** (not curl; data.sf.gov serves an incomplete cert chain):

```
https://data.sf.gov/resource/yhqp-riqs.json?$select=holidays,fromhour,tohour,count(*)&$group=holidays,fromhour,tohour
```

Field names come from the CSV headers, lowercased (**VERIFY**). Expected if the assumption holds: `holidays=1` rows are almost all `fromhour=0, tohour=6`. If not, derive "nightly" from the hours instead, and adjust `SfHolidayCalendar` and the tests in T6. The dangerous direction is a false "suspended": the app would show a street safe on a day it's swept. Do this before finalizing T6.

## T1. Re-arm + delivery markers (Room v12)

**Goal:** Reminders survive a reboot. Re-arming never re-posts a reminder the user already received or dismissed, and never cancels a notification that is on screen.

Background: `AlarmManager` alarms are wiped by a reboot or force-stop. Today `rescheduleAllActiveReminders` is only called from Settings changes and `undoDeleteCar`. Nothing re-arms at boot or launch.

**Schema (Room v11 → v12)**
- Add four nullable `Long` columns to `ParkedState` / `parked_state`: `normalDeliveredForMillis`, `urgentDeliveredForMillis`, `rppNormalDeliveredForMillis`, `rppUrgentDeliveredForMillis`. Each stores the **deadline (epoch ms)** the tier was last delivered for.
- Write `MIGRATION_11_12` (plain `ALTER TABLE ... ADD COLUMN ... INTEGER`, nullable), append it to `ALL_MIGRATIONS`, bump `version` to 12, and commit the exported `12.json`. Diff it against `11.json` to confirm only these columns changed.
- Write the project's first `MigrationTestHelper` test in `androidTest` (`room-testing` is already a dependency): create v11 with a parked row, migrate, and assert the row survives with null markers. Z runs it on a device.
- A fresh park replaces the row via the `REPLACE` upsert, so markers reset to null. **VERIFY** that is what actually happens (`carId` unique index, autoGenerate id).

**Recording deliveries**
- Add a DAO update such as `UPDATE parked_state SET <marker> = :deadline WHERE carId = :carId AND parkedAtMillis = :parkedAtMillis`. The `parkedAtMillis` guard stops a delivery that races a re-park from writing into the new row.
- Add a `parkedAtMillis` extra to the reminder PendingIntents (`buildPendingIntent`).
- `ParkingReminderReceiver` currently does no DB work. Give it the `goAsync()` treatment and record the delivery after posting.
- The immediate-fire branch in `scheduleOrFireImmediately` must also record the delivery.
- The RPP deadline is not stored anywhere; it is recomputed by `nextRppDeadline`. The RPP markers compare against that recomputed value. It is deterministic given `parkedSince` and the regulation, so it matches at re-arm time. Add a unit test that demonstrates this.

**Two scheduling paths (split them)**
1. *Schedule fresh* (existing `scheduleParkingReminders` / `scheduleRppReminders` via `saveParkedState`, plus Settings-change reschedules): behavior unchanged, except that the immediate-fire branch takes an `alreadyDeliveredForDeadline` flag computed from the markers. `saveParkedState` passes false (new row); the Settings-change path passes the marker comparison.
2. *Re-arm* (new function, e.g. `rearmAllActiveReminders(context)`): for each parked state, recompute the deadline (T3 provides `recomputeParkedSchedule`; until T3 lands, use the stored one), set alarms only for future triggers, immediate-fire only if the marker doesn't equal the deadline (i.e. a missed reminder), and never call `NotificationHelper.cancel`.

**Triggers**
- New `BootReceiver` (`RECEIVE_BOOT_COMPLETED` permission, manifest entry for `BOOT_COMPLETED`, `goAsync()`). `android:exported="false"` should work since the system uid is always allowed; confirm with a reboot test.
- App foreground: add `onStart` to the existing `ProcessLifecycleOwner` observer in `ParkApp` (it already has `onStop`). **Do not put it in `Application.onCreate`**; that runs on every cold process start, including the one that delivers an alarm.
- T2 adds a third trigger (exact-alarm permission changed).

**Acceptance**
- Before changing anything, record a baseline: park a car with a sweep a few hours out, run `adb shell dumpsys alarm | grep -A3 com.example.park`, reboot (emulator or device), run it again, and confirm the alarms are gone.
- After the change, the alarms are back after reboot and unlock.
- Sequence: urgent reminder fires → tap it (autoCancel) → cold start → it is **not** re-posted.
- Migration test passes.

## T2. Exact-alarm flow

**Problem:** `ensureExactAlarmPermission` runs in a `LaunchedEffect(Unit)` inside `MapScreen`. `MapScreen` remounts on navigation, so a user who declined is thrown into the system settings screen repeatedly. On Android 14+ a fresh install starts without the permission, so testers hit this immediately. When it's denied, `scheduleOrFireImmediately` logs a warning and sets nothing, and `notificationScheduled` still says true.

**Changes**
- Remove the `LaunchedEffect` prompt from `MapScreen`. Add a row in Settings → Parking & Notifications showing permission status with a button to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (mirror the battery-optimization and background-location rows).
- Persistent in-app warning banner while at least one car is parked with reminders and `!canScheduleExactAlarms()`. In the map's top-center Column, place the most persistent element first and transient ones last.
- Fallback: when exact alarms aren't allowed, schedule with inexact `setAndAllowWhileIdle` (same PendingIntent, so a later exact set replaces it) instead of nothing. Log it.
- Make `notificationScheduled` reflect the outcome (an alarm was set or an immediate fire happened). **VERIFY** where the field is read before changing its meaning.
- Add a manifest receiver for `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` that runs the T1 re-arm (`goAsync()`). **VERIFY** in the docs that a manifest-registered receiver is supported. This covers both grant (upgrade to exact) and revoke (the system cancels exact alarms, so re-arm falls back to inexact).

**Acceptance**
- Decline the permission, then navigate around the app: no repeated settings screen.
- The warning banner shows and a reminder still fires roughly on time via the fallback.
- Granting the permission converts to exact alarms without opening the app.

## T3. Schedule recompute, roll-forward, active-window notice

**Problems**
- `ParkedState.nextSweepAtMillis` is computed once at park time. "Fix schedule" (`ScheduleOverride`) and data refreshes leave stored deadlines and alarms stale. `SegmentDetailSheet`'s `onOverrideChanged` only redraws the map.
- After a sweep passes with the car still parked, nothing schedules the next one until the user opens the app.
- `nextSweepDateTime` requires the start to be after `from`, so a car parked at 7:15 inside a 6–8 window gets **next week's** reminder and no warning that sweeping is happening now.

**Changes**
1. `recomputeParkedSchedule(context, carId)`: load the `ParkedState` and its segment (**VERIFY** that `getById` applies the override via the LEFT JOIN + COALESCE), compute `NextSweepCalculator.nextSweepDateTime`, update `nextSweepAtMillis`, then schedule fresh. Call it after override save/remove (for every parked state whose `segmentBlockSweepId` matches), after a successful sync, and from the T1 re-arm. Because markers are keyed by deadline value, a changed deadline invalidates them automatically.
2. **Roll-forward alarm.** *This refines the earlier "chain from the receiver on delivery" idea:* rolling at delivery time would advance `nextSweepAtMillis` while the current sweep is still ahead, and UI countdowns would jump to next week early. Instead, schedule one small alarm per car (and one for RPP) at the deadline itself. When it fires, run `recomputeParkedSchedule` (RPP: recompute the RPP deadline) so the row moves to the next occurrence and reminders are scheduled for it. Use a separate small receiver (declared in the manifest), not `ParkingReminderReceiver`. New request-code offsets, e.g. 4_000_000 and 5_000_000, following the existing scheme (`URGENT` 1M, `RPP_NORMAL` 2M, `RPP_URGENT` 3M). **Check the offsets against `AUTO_DETECT_NOTIFICATION_ID_OFFSET` and `AUTO_UNPARK_NOTIFICATION_ID_OFFSET`** for collisions. T1's re-arm is the backstop if a roll-forward alarm is missed.
3. **Active-window notice.** In `saveParkedState` (**VERIFY** the Bluetooth auto-park path goes through it too), if `NextSweepCalculator.activeWindowEndDateTime(segment, now)` is non-null, post an immediate dismissible notice ("Sweeping in progress on X until HH:MM — move now") in addition to scheduling the next occurrence normally. A new `ReminderKind` (e.g. `SWEEP_ACTIVE`) needs an ID offset (e.g. 6_000_000). Adding an enum value will surface every exhaustive `when`. Update `reminderRequestCode`, `buildReminderContent` and `NotificationHelper.showReminder`.

**Acceptance**
- Park on a block, then "Fix schedule" it: alarms and displayed deadline follow the override.
- Park at 7:15 in a 6–8 window: an immediate notice appears.
- Leave a car parked through a sweep: after the deadline passes, the next occurrence is scheduled without opening the app.

## T4. Bluetooth notification channel + timeouts

**Problem:** The unpark notice sets `PRIORITY_LOW`, but on API 26+ the channel's importance wins and `parking_reminders` is `IMPORTANCE_HIGH`, so an informational message still heads-up pops. Informational notifications also never go away on their own.

**Changes**
- New channel `parking_status`, `IMPORTANCE_LOW`, named "Parking status" (created in `NotificationHelper.createChannel`). Channel importance can't be lowered after creation, hence a new id. Leave the old channels alone.
- Use it, plus `setTimeoutAfter(...)`, for: the confident auto-park notification and `showUnparkedNotification`. `showAutoDetectNotification` is shared with the ambiguous/no-match cases, so it needs a parameter distinguishing informational from actionable (**VERIFY** the call sites).
- **Ambiguous** match (best guess already saved with `sideConfirmed=false`): stays on the HIGH channel, timeout 4 hours (a constant, not a setting). **No-match**: keep as is, never times out (it's the only path into the manual flow).
- **Sweep and RPP reminders never time out.**
- New setting for the informational timeout: presets **1 min / 5 min / 30 min / Never**, default 5 min. Put it under Settings → Bluetooth & Background using the existing preset-dropdown pattern, stored in DataStore through `SettingsRepository`. Only apply `setTimeoutAfter` when the value is greater than 0.
- Keep the existing `MapScreenVisibility` suppression behavior.

**Acceptance:** an unpark/confident-park notification appears quietly (no heads-up) and disappears by itself at the chosen time; the ambiguous and no-match ones still behave as before.

## T5. Tunnel detection

**Problem:** During normal driving the map sometimes dims because the loop in `MapScreen` treats "no fix for more than `TUNNEL_GAP_THRESHOLD_MS` (20 s)" as a tunnel.

**Likely causes (VERIFY against Logcat, ideally with the diagnostic log below):**
1. `SingleSourceLocationProvider.DRIVING_MIN_DISTANCE_M = 2f`: with a 2 m distance filter, callbacks can stop while the car is stopped at a red light even though GPS is healthy. The loop can't tell "stationary" from "no signal".
2. `lastFixTimestamp` is initialized at composition and only updated in the driving-mode `onRawLocation` callback. Nothing resets it when driving mode turns on, so the first poll can see a stale gap. Check whether the idle path also updates it.

**Changes**
- Extract the decision into a pure function (e.g. `shouldSuspectTunnel(nowMs, lastFixMs, lastFixSpeedMps, lastRegainMs, config)`) so it can be unit tested on the JVM.
- **Speed gate:** only suspect a tunnel if the last fix's speed was above about 5 m/s. Record `lastFixSpeed` in `onRawLocation`.
- Reset `lastFixTimestamp` when driving mode activates.
- Set the driving distance filter to 0 (keep the 1 s time interval). The existing heading hold below the speed threshold handles stationary jitter.
- **Cooldown:** after "GPS regained", don't dim again for about 60 s.
- Keep the 20 s gap threshold as a named constant to tune later.
- New setting "Auto-dim map in tunnels", default **on**, in Settings → Map. When off, `suspectedTunnel` is always false.
- Log gap length, last speed and the decision under a `Tunnel` tag whenever it triggers, so the next false positive can be diagnosed from Logcat.
- Existing toasts stay ("GPS lost — dimming map" / "GPS regained — map back to normal"). Keep the guard against a false "regained" on first composition. Don't reintroduce a second writer to the location icon (see the earlier `LaunchedEffect(isMapDark)` bug).

**Acceptance:** JVM unit tests for the pure function (stopped at a light for 60 s: no dim; moving then gap: dim; cooldown respected; setting off: never dim). Z drives a route with long red lights and no false flicker.

## T6. Tests for `NextSweepCalculator` and `sweepStatus`

The core ticket-avoidance logic has no tests (only `SfHolidayCalendarTest`, `RppStatusTest` and a template `ExampleUnitTest` exist). Both functions already take `from` / `now` parameters. Build `StreetSegment` fixtures like `dummySegment` in `SfHolidayCalendarTest`. Avoid anything that calls `Color.parseColor` (JVM stub); `sweepStatus` itself doesn't, but `sweepStatusColor` does.

Cases:
- Recurrence: week 1–5 flags; day-of-month boundaries 7/8, 14/15, 21/22, 28/29; months with no 5th occurrence.
- `from` exactly at window start and at window end; ACTIVE at the start minute; the 30-minute "very soon" edge (30 in, 31 out); a sweep in progress returns next week's start from `nextSweepDateTime`.
- Holidays: a Monday holiday pushes a Monday sweep out a week; Thanksgiving Thursday and the day after (full list vs nightly `holidays=true` per T0); observed-date shifts (e.g. 2026-07-03).
- `dayOfWeekFromName` with full names, abbreviations and inputs like `"Mon 2nd & 4th"`; garbage returns null.
- `maxDaysToSearch = 60`: a week-5-only schedule can have a gap longer than 60 days, returning null (which `sweepStatus` treats as SAFE and `saveParkedState` treats as "no reminder"). Add a test documenting the behavior and consider raising the limit (e.g. ~120 days). Decide and note it in the commit.

## T7. RPP fixes

**7a. Window-end cliff.** `nextRppDeadline` clamps `moveBy` to the window end. With an 8–18 window and a 2 h limit, parking at 5:59 pm returns 6:00 pm ("limit is up" text is wrong) and schedules nothing for the next morning, while 6:01 pm correctly returns 10:00 am tomorrow. Fix: if `moveBy` is at or after that day's window end, there is no violation that day; continue to the next enforced day using the window-start clock. Update the existing test `nextRppDeadline_moveByCappedAtWindowEnd_whenLimitWouldExceedIt`, which encodes the old behavior, and add 5:59 vs 6:01 pm and Friday → Monday cases. Decide what `hrLimit <= 0` means (the parser defaults missing values to 0); default suggestion: return null, no deadline, and log. **VERIFY** which `HRLIMIT` values exist in the live feed.

**7b. Holidays.** RPP ignores `SfHolidayCalendar`. SFMTA lists time-limited RPP among programs not enforced on its holiday-calendar dates. Add `SfHolidayCalendar.isRppSuspended(date)` (the full list, including the New Year's spillover). `nextRppDeadline` skips suspended dates in its day loop; `activeRppWindowEndMillis` returns null on them, so the on-map zone label hides too. Tests for both.

**7c. Feed parsing** (`RppDataApi.kt`)
- Android's `org.json` `optString` returns the string `"null"` for a JSON null (as far as we know; **VERIFY**). Use `isNull(key)` checks for `RPPAREA1..3` and `DAYS` so a bogus `NULL` zone can't reach the permit picker. Check for a `"NULL"` entry in `loadDistinctRppZoneLetters` output on Z's synced data.
- Pass `outSR=4326` explicitly. **VERIFY** the layer's native spatial reference at `https://services.sfmta.com/arcgis/rest/services/DataSF/master/FeatureServer/24?f=json`.
- Add a parser unit test using a captured JSON fixture that includes nulls and `" "` values. This needs `testImplementation("org.json:json:<latest stable>")`, since Android's JVM stubs throw.

## T8. Stale-row cleanup

**Problem:** Neither sync deletes rows that vanish upstream (`insertAll` is `REPLACE` only), so retired segments and RPP blockfaces persist forever.

**Design**
- New migration (the next free Room version; v13 if T1 is already v12). Add a **nullable** `lastSeenSyncId INTEGER` to `street_segment` and `rpp_zone_regulation`. Nullable avoids the `@ColumnInfo(defaultValue)` schema-validation pitfall of `NOT NULL DEFAULT` columns.
- Each network sync generates an id (e.g. start time millis) and stamps every row it upserts. **Only after a fully successful sync** (the same condition that sets `lastRefreshMillis`), run `DELETE ... WHERE lastSeenSyncId IS NULL OR lastSeenSyncId < :id`.
- Safety guard: if the fetched row count is under about 50% of the existing count, skip the delete and log. This protects against truncated API responses.
- Manual JSON/CSV import stamps rows but **never deletes**.
- `parked_state.segmentBlockSweepId` and `rppRegulationId` may dangle afterward. **VERIFY** every consumer tolerates a null lookup (`resolveRppDeadline` already does).
- `ScheduleOverride` is keyed by `blockSweepId`, so it survives the cleanup. Leave overrides alone.
- Migration test, as in T1.

## T9. Build and hygiene

- **R8 shrink only.** Enable shrinking on release using the DSL the project already uses (`release { optimization { ... } }` in `app/build.gradle.kts`) and add `-dontobfuscate` to the ProGuard rules so the on-device crash-handler stack traces stay readable. Measure APK size before and after. Test on Z's devices: map, sync, Bluetooth hooks, widget, notifications (and the S9). Local release test builds need a signing config (e.g. the debug keystore). Later option: swap `material-icons-extended` for `material-icons-core` plus a few local vectors.
- **Time zone.** Add one `SF_ZONE = ZoneId.of("America/Los_Angeles")` and a helper like `sfNow()`. Replace `LocalDateTime.now()` and `ZoneId.systemDefault()` in sweep, RPP and reminder math (about 28 sites; grep for both) in **one mechanical commit**. A partial conversion is worse than none. DB epoch-millis timestamps are absolute and stay as they are; convert with `atZone(SF_ZONE)` consistently. Keep clock parameters injectable for tests. Do this last.
- API keys: no code task (see Decisions log).

## Device test matrix (Z runs these)

| Check | Devices |
|---|---|
| Reboot with a parked car; alarms return (T1, T2) | S25, S9 |
| Migration test (T1, T8) | any device or emulator |
| Reminders + snooze/dismiss buttons still work after each notification change | S25, S20, S9 |
| Widget refresh unaffected | S25, S9 |
| Tunnel dimming at long red lights (T5) | S25 (real drive) |
| Clean install: exact-alarm flow, all new receivers | S9 and a recent Android |