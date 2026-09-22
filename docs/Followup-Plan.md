# Park — Follow-up Plan (after T0–T9)

Continuation of `docs/ReliabilityPlan.md`. Read that file's "How to work" and "Invariants" sections first; they still apply. This document covers what came out of reviewing the T0–T9 completion report and the device-testing plan.

## 0. How to work (recap)

- One task at a time. Small commits. Stop and report after each task. Never merge to `main` or push unless Z asks.
- **Branching:** Z is device-testing the tip of the stack (`location-permission-prompt`) before merging it. Start each follow-up branch from `main` if it already contains the stack; otherwise from the tip of the stack. Check with `git log --oneline main..location-permission-prompt`. If it's unclear, ask.
- **Report only means report only.** F1 changes no app code. F4 and F5 are blocked until Z answers.
- Z is new to Kotlin: explain Kotlin/Android concepts briefly and say why.
- **Failure direction principle.** For a ticket-avoidance app, a false alarm is cheap and a false "safe" is a ticket. Whenever the data is ambiguous, choose the option that warns.

### Status

| Task | Branch | State |
|---|---|---|
| F0 Housekeeping | (with F2) | not started |
| F1 Raw-data investigation (report only) | none | not started |
| F2 Notification ID collision | `notif-id-ranges` | not started |
| F3 Sweep-search performance check | `sweep-perf` | not started |
| F4 RPP `HRLIMIT` 0/null handling | `rpp-zero-limit` | **blocked on F1 + Z's decision** |
| F5 `HOLIDAY` route rows | `holiday-rows` | **blocked on F1 + Z's decision** |

## F0. Housekeeping

- `docs/ReliabilityPlan.md` Status table wasn't updated last round because those doc files had uncommitted edits by Z. Run `git status`; if the file still has uncommitted changes from Z, **don't overwrite them**. Show Z the intended edit and ask. Otherwise mark T0–T9 done and note the branch names.
- Note in the plan's Decisions log that the T0 finding contradicted the original assumption (`holidays=1` does not mean nightly) and that the rule is now "`holidays=1` **or** starts before 6 am". F1 re-verifies this.

## F1. Raw-data investigation (report only)

**Why:** Three things in the last report rest on data that was read through a summarizing fetch tool or left unexplained. Get the raw data and answer them with counts, not impressions.

**Method**
- Download the raw data to a scratch directory **outside the repo** (or a gitignored path) and analyze it with a script (e.g. Python). Don't rely on a summarizing fetch tool. Do not commit the raw data.
- If your network path can't fetch (data.sf.gov serves an incomplete cert chain), tell Z exactly which URL to open in a browser and where to save the file, and continue from that.
- Sources: the DataSF street-sweeping dataset (`yhqp-riqs`) and the SFMTA RPP ArcGIS layer the app already queries (see `RppDataApi.kt`, including the exact query it uses).

**Question A: what do `HRLIMIT` 0 and null mean?**
The live feed has about 580 rows with explicit `0.0` and about 268 with null, and rows whose limit is as long as the whole enforcement window (72 h exists). T7 now produces no RPP deadline for 0, null, or a limit as long as the window.
- Give the full distribution of `HRLIMIT` values (count per value, null counted separately).
- For the 0 and null rows: print at least 10 complete raw rows of each (all attributes). Cross-tab every categorical field (regulation type, exceptions, days, hours, etc.) against the `HRLIMIT` bucket (0 / null / positive).
- Does any field separate "permit only, no visitor parking" from "no limit / not enforced"? If a text field (regulation description, exceptions) says it, quote 3–5 examples.
- Do 0/null rows appear on blocks that also have positive-limit rows (mixed regulation on one blockface)?
- State plainly what you can and can't conclude.

**Question B: what are the roughly 824 `HOLIDAY` rows?**
The day parser returns null for them, so they always display as safe.
- Print the full attribute set for 10 of them, plus the distribution of `FromHour`/`ToHour` and of the `holidays` flag among them.
- For each one, does the same `CNN` + side also have normal weekday rows? Give counts of "HOLIDAY-only" versus "HOLIDAY plus weekday rows".
- What does the SFMTA holiday sweeping information say about which streets are swept on holidays and which holidays? (Cite the page.) Does that match these rows?

**Question C: re-verify the T0 holiday finding**
Group by `holidays`, `fromhour`, `tohour` with counts.
- Among `holidays=1` rows, how many start at or after 6 am (daytime)? Give examples.
- Among `holidays=0` rows, how many start before 6 am? Give examples.
- Does the current rule ("nightly if `holidays=1` or starts before 6 am" → suspended only on the 3 major holidays; otherwise the full list) have a case where it would show a street as *not swept* on a day SFMTA says it is swept? That is the dangerous direction. List any such class of rows.

**Deliverable:** a concise written report in chat with the tables above. Optionally commit a short summary (findings only, no raw data) to `docs/investigations/`. **Make no app-code changes.** Then stop.

## F2. Notification ID collision

**Problem (pre-existing):** Bluetooth notification IDs (`carId + 2_000_000` / `+ 3_000_000`) equal the RPP reminder notification IDs, so a Bluetooth notice and an RPP reminder for the same car overwrite each other. Now that Bluetooth notices time out (T4), a notice could also wipe a safety reminder.

**Change**
- Find every notification ID and PendingIntent request-code scheme (`reminderRequestCode`, the roll-forward alarms, `SWEEP_ACTIVE`, `AUTO_DETECT_NOTIFICATION_ID_OFFSET`, `AUTO_UNPARK_NOTIFICATION_ID_OFFSET`, anything else). Put them in **one** object (e.g. `NotificationIds`) with a documented range per purpose, spaced so no two purposes can overlap for any plausible car id.
- Move the Bluetooth IDs to fresh, non-overlapping ranges. Note that PendingIntent request codes and notification IDs are separate namespaces. Collisions only matter within each, but keep the schemes readable.
- Guard the conversion: `carId.toInt() + offset` breaks if an id ever exceeds the range spacing. Add a `require(carId in 0 until <spacing>)` (or equivalent) with a clear message.
- **Unit test:** for a range of car ids (including the largest allowed), assert that all IDs from all purposes are pairwise distinct.
- Cancel calls (unpark, delete car, re-park) must use the same helpers, so nothing is orphaned. Grep for every `cancel(` on notifications.

**Acceptance:** the collision test passes; Bluetooth test hooks and reminder notifications still post and cancel correctly; note in the report that already-posted notifications from the old scheme are irrelevant across an app update.

## F3. Sweep-search performance check

**Why:** T6 raised `maxDaysToSearch` from 60 to 250 so week-5-only schedules are found. `nextSweepDateTime` runs per segment on map reloads, and Z's oldest test phone is a Galaxy S9.

- Measure first. Write a JVM test or micro-benchmark that computes `nextSweepDateTime` (and `sweepStatus`) for a realistic viewport's worth of segments (a few thousand) with mixed schedules, plus the worst case (week-5-only schedules that need the whole 250-day search, a schedule that returns null).
- Report the numbers, with a note that the JVM is much faster than an S9 (assume a 5–10× margin) and how many calls a typical reload makes (read the reload path to count).
- **Optimize only if the estimate is worrying** (a reload's sweep math on the order of 100 ms or more on the S9). If needed, the usual fix is to skip whole weeks or months instead of stepping day by day, or to stop early once a matching week flag is found. Keep behavior identical and keep the T6 tests passing. Don't change semantics.
- Add the measurement to the report. If no change is warranted, say so and stop.

## F4. RPP `HRLIMIT` 0/null handling (blocked on F1 and Z's decision)

Depending on what F1 shows, present Z with a concrete proposal covering these cases:

| If F1 finds | Behavior to propose |
|---|---|
| 0/null means permit-only (no visitor parking during enforcement) | A non-permit car parked in the enforcement window gets an immediate "No non-permit parking here until HH:MM" notice, and outside the window a reminder before the window opens. Reuse the existing pattern (T3's active-window notice) rather than inventing a new one. |
| 0/null means no limit or not enforced | Keep no deadline. Say so in a code comment with the evidence. |
| Rows can't be told apart | Warn conservatively: a softer message ("Permit zone, limit unclear — check signs") for non-permit cars. Never silence. |

Include tests, and describe the change in terms of what a user parked at 5:59 pm, 6:01 pm, and 8 am would see. Do not implement until Z picks.

## F5. `HOLIDAY` route rows (blocked on F1 and Z's decision)

Depending on F1, propose one of:
- **Holiday-only sweeps:** the segment's next sweep is the next date in `SfHolidayCalendar` that qualifies, using the row's hours. Extend `NextSweepCalculator` without changing behavior for weekday rows.
- **Complementary rows:** the segment already has weekday rows, and the `HOLIDAY` row adds nothing. Don't double count; make sure the dedup (`cnn + cnnRightLeft`, most urgent wins) doesn't pick the always-safe HOLIDAY row over a real weekday row.
- **Unknowable:** at minimum stop showing "safe" for them. A distinct, cautious status or a note in the segment detail sheet ("Also swept on holidays — check signs"), whichever needs the smaller UI change.

Include tests. Do not implement until Z picks.

## Not for Claude Code (Z's checklist)

- Test from the tip of the stack per the device test plan. If a task misbehaves, fix forward with a commit on top instead of rebasing eight branches.
- Before installing over an old build (it migrates v11 to v13 on real data), back up the database, including the `-wal` file.
- Add a perf check on the S9 when you pan the map, for F3.
- Push branches for backup without merging: `git push -u origin --all`. When the tip passes, fast-forward merge (`git merge --ff-only location-permission-prompt`) and tag it.