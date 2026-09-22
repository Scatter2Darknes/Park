# Park — Status Update: the follow-up pass (F0–F6)

For updating another Claude conversation. It builds on what that chat already has (the reliability plan T0–T9 and `docs/TestingGuide.md`). Everything below is committed locally on stacked git branches; **nothing is merged to `main` or pushed, and none of it has been run on the owner's physical phones.**

## Where things stand

- **Branch stack** (each on the previous): `main` ← `rearm-reminders` (T1) ← `exact-alarm-flow` (T2) ← `schedule-recompute` (T3) ← `bt-notif-timeouts` (T4) ← `tunnel-detection` (T5) ← `sweep-tests` (T6) ← `rpp-fixes` (T7) ← `stale-rows` (T8) ← `build-hygiene` (T9) ← `location-permission-prompt` ← `notif-id-ranges` (F0+F2) ← `sweep-perf` (F3) ← `rpp-zero-limit` (F4) ← **`curb-merge` (F5+F6, the tip)**.
- **Room database** is now **v15** (v12 delivery markers, v13 stale-row stamps, v14 `limitAssumed`, v15 curb index). All are real `Migration` objects with exported schemas.
- **Automated checks:** 115 JVM unit tests pass, 0 failures. 7 instrumented tests pass on an emulator (migrations 11→12, 12→13, 13→14, 14→15, the whole 11→15 chain, a curb query with an override against a real Room database, and the template test). The debug, release and androidTest builds succeed.
- **Not done:** every device check. The checklist is in `docs/CheckFirst.md`; full steps are in `docs/TestingGuide.md` (section 5 covers this pass).

## What the follow-up pass did

### F1 — Raw-data investigation (report only, no app code)
Downloaded the raw data directly (no summarizing tool): the DataSF sweeping dataset (37,878 rows) and SFMTA's RPP layer using the app's own filter (6,502 rows). Findings are in `docs/investigations/F1-raw-data-findings.md`. These **corrected two earlier claims** and found one bigger problem:

1. **The earlier "580 rows with HRLIMIT 0 and 268 null" was wrong.** Those counts came from a whole-layer query covering every regulation type. Among the 6,502 RPP rows the app actually loads there are **zero** `HRLIMIT = 0` rows and only **10** null: 2 junk "No parking any time" rows (zone `"0"`), 3 metered "Paid + Permit" rows, and 5 "Time Limited" rows whose limit is simply missing. There are also 59 zone-HV "Pay or Permit" rows at 72 hours.
2. **The 824 `HOLIDAY` sweeping rows are not standalone routes.** All 824 sit on curbs that also have ordinary weekday rows; they are the 7-day nightly routes (one row per weekday plus a `HOLIDAY` row). They are redundant with the current holiday rule.
3. **The T0 finding was re-verified:** `holidays=1` does not mark nightly routes (113 rows with `holidays=1` start at or after 6am; 10,522 rows with `holidays=0` start before 6am). No class of rows was found where the current rule shows "not swept" when SFMTA says it is swept.
4. **A bigger problem (not in the plan): 25.5% of curbs (5,761 of 22,573) are described by more than one database row, and 4,784 of those sweep on different weekdays.** Parking saved only one row, so a Monday-and-Thursday curb could subscribe the user to Monday alone. This became F6.

### F0 — Housekeeping
`docs/ReliabilityPlan.md` Status table now marks T0–T9 done (device testing pending), and the Decisions log records that the `holidays=1` assumption was disproved and the current rule ("nightly if `holidays=1` **or** starts before 6 am" → suspended only on the 3 major holidays; otherwise the full list).

### F2 — Notification ID collision (`notif-id-ranges`)
- A new `NotificationIds` object holds every notification ID and per-car PendingIntent request code, one 1,000,000-wide range per purpose. The existing reminder, roll-forward and sweep-active offsets are **unchanged** (so alarms scheduled by an earlier version still match). Only the Bluetooth IDs moved (to 7M and 8M).
- The collision was worse than described: Bluetooth notifications shared IDs **and** MainActivity tap-intent request codes with the RPP reminders, so one could overwrite the other's extras.
- `forCar()` rejects car ids outside the range. Receivers that read a car id from an intent bail out on the `-1` default. The Settings test buttons' fake car id (`999_999_999`) would have been rejected (Snooze on a test notification would have crashed), so it now uses a reserved id.
- Unpark and car delete now also clear that car's Bluetooth notices; a new park clears a stale "Did X just park?" prompt.
- 7 new tests prove no two purposes can collide.

### F3 — Sweep-search performance (`sweep-perf`)
Measured after the search limit went 60 → 250 days. On a JVM: ~9 ms for 6,400 realistic segments (the densest downtown viewport at the maximum radius), ~11 ms for 6,400 worst-case segments, ~1 µs per ordinary call and ~3.6 µs for a 210-day worst-case call. A phone is several times slower, so roughly 15–30 ms at the default radius. **No optimization needed.** The benchmark is committed as a test. The sweep math is not the likely bottleneck; a real pan check on the S9 is still worthwhile.

### F4 — RPP missing limit (`rpp-zero-limit`)
- "Time Limited" RPP rows with a missing limit now **assume 2 hours** (the most common value; a false alarm is cheap, a false "safe" is a ticket) and the reminder says so: `RPP Zone S (limit not posted — assumed 2 h; check signs)`. Stored via a new nullable `limitAssumed` column (Room v14).
- Metered "Paid + Permit" and 72-hour rows keep **no** deadline.
- Rows whose zone isn't letters (the junk zone `"0"`) are dropped so nothing reaches the permit picker.
- For a non-permit car in an 8am–6pm window with an assumed 2 h limit: parked 5:59pm or 6:01pm → tomorrow 10am; parked 8am → today 10am; a permit holder → no deadline.

### F5 + F6 — A curb is one schedule (`curb-merge`)
- New `CurbSchedule` (pure, JVM-tested): next sweep = the **earliest across all rows of the curb**; sweep-in-progress = the latest end; status = most urgent; representative = a real weekday row, never a `HOLIDAY` row.
- Wired into: `saveParkedState` (deadline and the "sweeping in progress" notice); the recompute path that runs on override, sync and roll-forward; `recomputeSchedulesForSegment` (an override on *any* row of a curb now moves cars parked on a sibling row); the widget colour; and the map's per-curb de-duplication (a tie no longer lets a `HOLIDAY` row become the tap target).
- `findNearbySegmentMatches` collapses same-curb rows into one candidate, so a multi-row curb is no longer classified AMBIGUOUS and the manual picker doesn't list it repeatedly. Two genuinely different curbs close together (e.g. the two sides of a street) remain ambiguous.
- New override-aware `StreetSegmentDao.getByCurb` and an index on `(cnn, cnnRightLeft)` (Room v15).
- `urgencyRank()` moved from `MapUtils.kt` to `SweepStatus.kt` because `MapUtils.kt` cannot load in JVM tests.
- **Known limitation:** the parking confirm screen still shows a single row's schedule for a multi-row curb, even though the reminders now cover all rows.

## Decisions made this pass
- F4: assume 2 h with a visible label for the 5 missing-limit "Time Limited" rows (recommended option chosen), and drop the junk zone `"0"`.
- F5: no separate holiday-row logic; resolved by F6, since the `HOLIDAY` rows are redundant with the day rows.

## Still open
- **Device testing of everything** (see `docs/CheckFirst.md`). The most valuable single check is F6: park on a curb swept on two different weekdays (e.g. 3rd St between Howard and Clementina) and confirm the banner and reminders use the soonest day.
- **Verify in a browser:** the T0 query and SFMTA's holiday page (whether nightly sweeping is also off on Thanksgiving Day). Both were read through a summarizing tool; the raw-data numbers above were not.
- **Housekeeping mistakes to know about:**
  - The F2 commit (`4d4ad91`) accidentally includes an empty `docs/Followup-Plan.md` (the owner had staged it). The real content is still an uncommitted change. Can be removed by rewriting that commit if wanted.
  - An earlier commit (`a1fbe45`) swept in the owner's own `versionName` 1.010 → 1.011 change in `app/build.gradle.kts`.
- `docs/ReliabilityPlan.md` was updated and committed; `docs/Claude.md`, `docs/Followup-Plan.md` (its status table isn't updated), `docs/TestingGuide.md` and `docs/CheckFirst.md` are uncommitted.
- Possible next steps (not started): show all of a multi-row curb's schedules on the confirm screen; merge order and pushing branches once the tip passes device testing (`git push -u origin --all`, then `git merge --ff-only`, then tag).
