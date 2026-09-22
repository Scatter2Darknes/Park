# Park — Reliability Work: What's Done and What to Test

Written for handing to another Claude conversation, so it is self-contained. Android app "Park" (Kotlin, Compose, Room, WorkManager, AlarmManager, osmdroid) that maps San Francisco street-sweeping and residential-permit (RPP) rules so the owner avoids tickets. Reminder correctness is the core value.

**State:** all work is committed locally on stacked git branches. **Nothing is merged to `main` or pushed.** The plan being followed is `docs/ReliabilityPlan.md` (tasks T0–T9), plus one extra fix (location-permission screen).

## 1. Branch stack (each builds on the previous)

`main` ← `rearm-reminders` (T1) ← `exact-alarm-flow` (T2) ← `schedule-recompute` (T3) ← `bt-notif-timeouts` (T4) ← `tunnel-detection` (T5) ← `sweep-tests` (T6) ← `rpp-fixes` (T7) ← `stale-rows` (T8) ← `build-hygiene` (T9) ← `location-permission-prompt`

The tip, `location-permission-prompt`, contains everything. Test builds from the tip. Room database: v11 → **v13** (v12 = delivery markers, v13 = stale-row stamps); real migrations, schemas exported to `app/schemas/`.

## 2. What was completed

| Task | What changed |
|---|---|
| **T0** holiday flag | Ran the plan's data query (via a fetch tool, so please re-run in a browser to confirm). Result **contradicted the plan**: `holidays=1` does not mark nightly routes; overnight rows exist under both flag values. |
| **T1** re-arm + delivery markers | Alarms are wiped by reboot. Added a `BootReceiver` (BOOT_COMPLETED) and an app-foreground hook that re-arm all reminders. Four nullable columns on `parked_state` record the deadline each reminder tier was last delivered for, so re-arming never re-posts a reminder already received and never cancels one on screen. Delivery recorded from the alarm receiver (`goAsync`) and the immediate-fire path. |
| **T2** exact alarms | Removed the prompt that reopened system settings on every map remount. New Settings row ("Exact alarms") and a persistent red warning banner on the map when the permission is missing. Without the permission, reminders and snoozes fall back to inexact alarms instead of being dropped. New receiver re-arms as soon as the permission is granted. `notificationScheduled` now records the real outcome. |
| **T3** recompute / roll-forward | `recomputeParkedSchedule` recomputes a car's stored sweep deadline from its segment (override-aware) after "Fix schedule", after data syncs/imports, and on every re-arm. A tiny roll-forward alarm per car (sweep: at the deadline; RPP: at the window's close) moves a car left parked through a sweep on to the next occurrence without the app opening. Parking during an active sweep window posts a dismissible "Sweeping in progress" notice. Re-parking somewhere with no sweep cancels the old alarms. |
| **T4** Bluetooth notifications | New quiet channel "Parking status". Confident auto-park and unparked notices use it (no heads-up) and time out; ambiguous "Did X just park?" stays loud with a 4-hour timeout; no-match never times out; sweep/RPP reminders never time out. New setting (Bluetooth & Background): 1 min / 5 min / 30 min / Never, default 5 min. |
| **T5** tunnel detection | Pure function `shouldSuspectTunnel`: dim only if GPS silent >20 s **and** last fix was moving >5 m/s, with a 60 s cooldown after "GPS regained". Driving-mode distance filter 2 m → 0 (a 2 m filter silenced fixes at red lights). Gap clock reset when driving mode starts. New setting "Auto-dim map in tunnels" (Map), default on. Logs under tag `Tunnel`. |
| **T6** sweep tests | New tests for `NextSweepCalculator` and `sweepStatus` (week boundaries 7/8, 14/15, 21/22, 28/29, window edges, 30/31-minute edge, holidays, day-name parsing). Search limit raised **60 → 250 days** (a week-5-only schedule can go 210 days between occurrences; 60 returned "no sweep" = shown safe). Holiday rule changed per T0 finding: a route counts as nightly (3 major holidays only) if `holidays=1` **or** it starts before 6am. |
| **T7** RPP fixes | Fixed the window-end cliff (parked 5:59pm in an 8–6 window now gives tomorrow's deadline, not "6pm"). RPP now honors SF holidays. A limit ≤ 0, or one as long as the whole window (72 h rows exist), produces no deadline. Feed parsing rejects JSON nulls / `" "` / `"null"` so no bogus "NULL" zone; query passes `outSR=4326`. |
| **T8** stale rows | Nullable `lastSeenSyncId` on `street_segment` and `rpp_zone_regulation`. Each network sync stamps rows; after a **fully successful** sync, unseen rows are deleted (skipped if the sync returned <50% of stored rows). Manual import stamps but never deletes. Overrides untouched. |
| **T9** build hygiene | R8 shrink-only for release (no obfuscation/optimization); unsigned release APK 53.2 MB → 9.2 MB. Smoke test on emulator found R8 removed a WorkManager constructor (all background work failed); fixed with a keep rule. All sweep/RPP/reminder date math now uses San Francisco time (`SF_ZONE`, `sfNow()`), 28 sites. |
| **Extra** location permission | Denying location used to leave a dead screen. Now a "Location access needed" screen with "Allow location", "Open app settings", and the Settings gear; re-checks on resume; handles "Approximate only" wording. |

### Automated verification already done
- 84 JVM unit tests pass (0 failures). Debug, release and androidTest builds succeed.
- 4 instrumented tests passed on an emulator (Room migrations 11→12, 12→13, the 11→13 chain, plus the template test).
- Release APK smoke-tested on an emulator (launch, 206 random UI events, no app crash); WorkManager workers succeeded after the keep-rule fix.
- Location-permission screen exercised on an emulator: denied → blocked dialog → app settings → grant → map appears.
- **Nothing has been run on the owner's physical phones.**

## 3. What you need to test (physical devices)

Devices: S25 (main), S20, Tab S9, Galaxy S9 (API 29 = minSdk floor). `adb` is at `C:\Users\chank\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Turn notifications **on** for Park first. Filter Logcat on tag `Park` unless noted.

> ⚠️ **Do this first, before installing the new build (T1 baseline):** with the OLD build, park a car on a block with a sweep a few hours out, run `adb shell dumpsys alarm | grep -A3 com.example.park`, reboot, run it again, and confirm the alarms are gone. Installing the new build first spoils this comparison. Also note installing the new build over an old one runs the v11→v13 migration on real data; back up first if the data matters.

> ⚠️ Running `./gradlew connectedDebugAndroidTest` on a **physical phone uninstalls the app afterwards and wipes its data.** Run instrumented tests on an emulator only (`ANDROID_SERIAL=emulator-5554`).

### T1 — Reminders survive reboot (S25, S9)
1. Install the new build **clean (uninstall first)** so the new receivers are registered. Park with a sweep a few hours out.
2. `dumpsys alarm` shows alarms; reboot and unlock; run again — alarms should be **back**. Logcat: `BootReceiver: BOOT_COMPLETED — re-arming reminders`.
3. Let an **urgent** reminder fire → tap it → kill and cold-start the app → it must **not** be re-posted.
4. Missed reminder: power off past a reminder's trigger time but before the deadline, boot → it should fire once.
5. Snooze 10 min and "I moved my car" buttons still work.

### T2 — Exact alarms (clean install, Android 14+; S9 too)
1. Leave "Alarms & reminders" **off**. Park; navigate around: the system settings page must **not** open by itself. Red banner "Reminders may arrive late — exact alarms are off" shows; Settings → Parking & Notifications shows "Not allowed". A reminder still fires roughly on time (Logcat: "scheduled as an inexact fallback").
2. Tap Fix / Allow Exact Alarms, enable it. Logcat should show `ExactAlarmPermissionReceiver: exact alarms granted — re-arming reminders` and the banner disappears.
3. Revoke **"Alarms & reminders"** (not notifications) in system settings. Android force-stops the app (expected). Reopen: banner returns, alarms exist as inexact (`dumpsys alarm` shows `exactAllowReason=none` instead of `permission`).
*(1 and 2 already confirmed working by the owner; 3 not yet exercised.)*

### T3 — Recompute, roll-forward, active notice
1. **Fix schedule:** park on a block, tap the block → "Doesn't match the sign? Fix it", change the schedule. The parked-cars banner countdown and alarms should follow the override.
2. **Active notice:** use a Fix-schedule override for today's weekday with hours that include the current time, then park there → immediate "Sweeping in progress — move X now" notification (swipeable, no snooze). Also try the Bluetooth auto-park path.
3. **Roll-forward:** override for today with a start hour a little ahead; park; leave the car past that hour without opening the app; open it later → the next occurrence should already be scheduled.

### T4 — Bluetooth notification behavior (use Settings → "Test Bluetooth Hooks", or a real linked car)
- Confident auto-park and "unparked" notices appear **quietly** (no pop-up) and remove themselves at the chosen time (Settings → Bluetooth & Background → dropdown). Try 1 min and Never.
- Ambiguous and no-match "Did X just park?" prompts still pop up; no-match never disappears by itself.
- Sweep/RPP reminders never time out. Reminder Snooze/Dismiss buttons still work after all notification changes (S25, S20, S9).

### T5 — Tunnel dimming (real drive, S25)
Drive with Driving Mode on through long red lights: **no** dim/flicker while stopped. Logcat `adb logcat -s Tunnel` shows gap, speed and decision. A real tunnel/garage should still dim and then restore ("GPS regained"). Settings → Map → "Auto-dim map in tunnels" off = never dims.

### T7 / T8 — RPP and data cleanup (light manual checks)
- Settings → Sync Now (streets and RPP). Logcat tags `DataSF` / `RppSync` should show `Stale-segment cleanup removed N rows` / `Stale-RPP cleanup removed N rows` (or "Skipping…looks truncated" on a bad sync). The car-permit-zone picker must have **no "NULL" entry**.
- Park on an RPP-zone block near the end of enforcement (e.g. 5:59pm with a 2 h limit) → reminder should be for the next morning, not "6pm".

### T9 — Release build and time zone
- Release test APK (debug-signed, installs over an existing debug install and keeps data): `app/build/outputs/apk/release/park-release-shrunk-test.apk`. Install on **S25 and S9**; check map, sync, Bluetooth hooks, widget, notifications, and background refresh (Logcat `WM-WorkerWrapper` should show `SUCCESS`, no `Could not create Input Merger`).
- Time zone: set the phone to a non-SF zone (e.g. New York). Reminders and countdowns must still be in **San Francisco** time.

### Location permission fix (any device)
Fresh install, tap "Don't allow": the new "Location access needed" screen appears with a working Settings gear. "Allow location" re-prompts (may do nothing if Android blocked it — then use "Open app settings", grant location, press back → map appears). Also try granting only "Approximate" → wording asks for Precise.

### Regression checklist (after each area)
Notification action buttons (Snooze / "I moved my car"), home-screen widget refresh (S25, S9), Sweep and RPP reminders both still independent per car.

## 4. Known limitations and open decisions

- **RPP `HRLIMIT` of 0/null — CORRECTED after checking raw data (see `docs/investigations/F1-raw-data-findings.md`):** an earlier version of this guide said 580 rows were `0.0` and 268 null. Those counts were for the *whole* ArcGIS layer (all regulation types); among the 6,502 rows the app actually loads there are **zero** `0.0` rows and only **10** null (2 junk "No parking any time" rows, 3 "Paid + Permit" meter rows, 5 "Time Limited" rows with a missing limit). So the practical exposure is ~5 blocks, not ~850.
- **`HOLIDAY` segments — CORRECTED:** the ~824 `HOLIDAY` rows are not standalone routes. All 824 sit on curbs (cnn + side) that also have ordinary weekday rows (the 7-day nightly routes), so they are redundant with the current nightly-holiday rule. The real risk is different: **25.5% of curbs have more than one sweeping row (4,784 with different weekdays), and the park flow saves only one row** — see the investigation file. Not fixed.
- **Junk RPP zone "0":** two "No parking any time" rows have `RPPAREA1 = "0"` and get past the app's `<> ' '` filter, so a zone "0" can appear in the permit picker. Not fixed.
- **Notification ID collision (pre-existing, not fixed):** Bluetooth notification IDs (`carId + 2_000_000` / `+ 3_000_000`) equal the RPP reminder IDs, so they overwrite each other for the same car.
- **Force-stop** can't be recovered until the app is next opened (documented limitation). **Snooze + reboot** inside the 10-minute window loses the snoozed alarm (accepted).
- Revoking the exact-alarm permission makes Android stop the app; reminders return (inexact) on next open.
- Inexact fallback alarms can be late by minutes.
- T0's query went through a summarizing fetch tool — re-run in a browser: `https://data.sf.gov/resource/yhqp-riqs.json?$select=holidays,fromhour,tohour,count(*)&$group=holidays,fromhour,tohour`.
- `docs/ReliabilityPlan.md` Status table not updated (those doc files were uncommitted user edits). The last commit also included the owner's own `versionName` 1.010 → 1.011 change.
- Nothing merged to `main`; merge order is the stack order above.

## 5. Follow-up round (F0–F6), stacked after `location-permission-prompt`

New branches, each on the previous: `notif-id-ranges` (F0+F2) ← `sweep-perf` (F3) ← `rpp-zero-limit` (F4) ← `curb-merge` (F5+F6, the new tip). Room is now **v15**. F1 is a report only (`docs/investigations/F1-raw-data-findings.md`); it corrected two earlier claims (the HRLIMIT 0/null counts, and what the `HOLIDAY` rows are).

| Item | What changed |
|---|---|
| **F0** | Plan status table marked T0–T9 done; T0 finding recorded in the decisions log. |
| **F2** | All notification IDs / request codes now come from one `NotificationIds` object (a 1,000,000-wide range per purpose). Bluetooth notices used the same IDs as the RPP reminders and overwrote them; they moved to 7M/8M. Reminder/roll-forward IDs are unchanged. Unpark/car-delete now also clear that car's Bluetooth notices. |
| **F3** | Measured the sweep math after the 60→250 day change: ~9 ms for 6,400 realistic segments, ~11 ms worst case, on a JVM. No optimization needed. Benchmark kept as a test. |
| **F4** | Among the RPP rows the app loads there are no HRLIMIT=0 rows and 10 null: the 5 "Time Limited" ones now assume **2 h** and say so in the reminder ("RPP Zone S (limit not posted — assumed 2 h; check signs)"); metered and 72 h rows keep no deadline; the junk zone "0" is dropped from the permit picker. (Room v14.) |
| **F5+F6** | A curb is described by several database rows (25.5% of curbs). Parking used to save just one, so a Monday+Thursday curb could subscribe you to Monday only, and a 7-day nightly curb to one weekday or to the empty `HOLIDAY` row. Now the next sweep is the **earliest across all rows of the curb**, the matcher collapses same-curb rows into one candidate (no more false "ambiguous"), recompute/roll-forward/overrides cover every row, and a `HOLIDAY` row can never win over a real one. (Room v15: index on cnn+side.) |

Automated: 115 JVM tests pass; 7 instrumented tests pass on an emulator (migrations 11→…→15, the curb query with an override).

### Extra device checks for this round
- **F2:** Settings → Test Bluetooth Hooks: fire a park and an unpark notice while an RPP or sweep reminder for the same car is on screen — neither should replace the other. Tap **Snooze** on a Settings test notification (it used a fake car id that the new guard would have rejected; it now uses a reserved one).
- **F4:** after a Sync Now (RPP), the car permit-zone picker must not list "0". If you can find a "Time Limited" RPP block in zones S, D, Q or U, park a non-permit car there in the window and check the reminder text says the limit is assumed.
- **F6 (most important):** find a curb that is swept on **two different weekdays** (a downtown commercial block, or a 7-day nightly route such as 3rd St between Howard and Clementina). Park on it with a car and check the parked-cars banner and the reminders use the **soonest** of the days, not just one. Use "Fix schedule" on any row of that curb and confirm the parked car's deadline updates. Bluetooth auto-park on such a curb should now be "confident", not "Did you just park?".
- **Not yet verified on a device:** all of the above; F6 was verified only by unit tests and a Room test.
