# Park — Public Works Permit Investigation + Curb-Source Refactor Spec

Two pieces of work, designed for handoff to Claude Code, **in this order**:

1. **Investigation (Part A):** a data check, no app changes. Does a Public Works dataset carry the
   short-notice no-parking permits that SFMTA's tow-zone feed stopped receiving after 2026-07-20?
2. **Refactor (Part B):** move every curb rule behind the shared `CurbRestrictionSource` shape that
   `docs/park-closures-spec.md` §1 described and v1.04 skipped. **No user-visible change.**

Part A goes first because its answer might add a sixth source for Part B to plug in. Part B does not
depend on it and must not wait on it if Part A stalls.

Grounded against the v1.04 source (`park_1_04.zip`, Room v22). Names below are real as of v1.04,
and the spec was revised after Claude Code checked it against the code (two rounds of findings, all addressed
below). Names that are **proposed new** here: `CurbRestrictionSource`, `SourceId`, `CurbContext`,
`SourceSettings`, `ArmOutcome`, `CurbSources`.

---

## Part A — Public Works permit investigation

### Why
`6r5h-j298` (SFMTA Enforced Temporary Tow Zones) is republished daily, but its newest permit entry
date is 2026-07-20 (`TowZoneApi.kt` / `TowAlerts.kt` staleness note). Hypothesis to test: Public Works
moved Street Space / Temporary Occupancy permits into its new **Clariti** permitting system around then,
and SFMTA's tow feed only reads the old system. If true, the permits exist, just not where Park looks.

### Datasets to check (all data.sf.gov / Socrata)
| ID | Name | Notes from the catalog |
|---|---|---|
| `fxfq-npa9` | Public Works Permits issued by Clariti | New system. Includes Street Space + Temporary Occupancy. Extract "once every 2 days (for now)". |
| `sftu-nd43` | Parking Signs / Street Space Permits | Construction / sidewalk-repair permits. Tagged sfmta, tow, verification. |
| `b6tj-gt35` | Street-Use Permits (all statuses, since 1996) | Daily at 12am, one-day lag. Has `cnn`. Many permit types unrelated to parking. |
| `x8nh-xzn6` | Active Street-Use Permits | View of `b6tj-gt35`: active/approved, TempOcc/Excavation not past end date. |

### Deliverable: `scripts/pw_permit_check.py`
Same conventions as `scripts/overlap_check.py`: read-only, touches no device, runs on Windows
(`python scripts\pw_permit_check.py`), uses the app token if present, respects data.sf.gov pacing.

For each dataset:
1. Pull `/api/views/<id>.json` and print: `rowsUpdatedAt`, column names + types.
2. Identify (and print which columns it chose for): permit type, entry/created date, start/end of the
   permit window, location key (`cnn`? lat/lng? address?), status.
3. **Entry-date histogram** of permits whose type plausibly posts no-parking signs (Temporary
   Occupancy, Street Space, and whatever `sftu-nd43` carries), by week, from 2026-05-01 to today.
   The key question: **are there entries after 2026-07-20?**
4. **Lead time**: for recent rows, (window start − entry date) distribution. Compare with the ~4-day
   median measured on the tow feed (closures spec §9).
5. Count of permits whose window covers any time in the next 7 days, citywide.

Cross-check against the tow feed:
6. For the currently live `6r5h-j298` zones, find each in the Public Works datasets by the tow feed's
   `permitnumber` column (fall back to CNN + overlapping window if a Public Works dataset lacks a
   permit number). Report found / not found, and in which dataset.

Print a short verdict, one of:
- **CLARITI_HAS_NEW_PERMITS** — sign-posting permit types keep being entered after 2026-07-20 in a
  Public Works dataset. Name the dataset(s).
- **NO_NEW_PERMITS_ANYWHERE** — nothing after 2026-07-20 in any of them (supports "timing thing").
- **INCONCLUSIVE** — say exactly which field was missing or ambiguous.

Append the findings (columns chosen, histogram, lead times, verdict) to this doc as a "Part A findings"
section. **Do not change app code in Part A.** If a dataset looks usable, say so and stop; adding it as
a source is a follow-up decision for the owner.

Things to watch for, per the closures experience:
- A dataset's own description can be wrong about what it contains (the closures feed claimed
  "permitted only" and wasn't). Check statuses actually present.
- Time columns can be off (closures `*_utc` was an hour late for ~10% of DST rows). Note which time
  columns look trustworthy.
- "Updated today" on the portal ≠ new rows. Use entry dates inside the rows.
- Not every street-use permit posts no-parking signs (tables/chairs, banners, food trucks). List the
  permit types found and which ones were counted.

---

## Part B — `CurbRestrictionSource` refactor

### Goal
Today each curb rule is wired separately in several places:
- `armParkedState` (`NotificationScheduler.kt`): sweep and RPP inline, then `armClosureAlert` and
  `armTowReminders` each in their own try.
- `loadCarsWithStatus` / `CarWithStatus` (`CarActions.kt`): one field per source (`rppDeadline`,
  `closureStatus`, `towDeadlineMillis`, `towStatus`) resolved one by one.
- `soonestDeadline()` (`CarActions.kt`): a hand-written list of sweep/RPP/meter/tow.
- `ReminderKind`, `RollForwardKind`, `DeadlineKind`: per-source enum entries.
- Park-time checks: `runParkTimeClosureCheck`, `notifyTowOnPark`.

After this pass, adding a source means **writing one class and registering it**, not editing the
arming, status, and ranking code. Turning a source off is its own `isEnabled` check.

### Hard rule: no user-visible change
The one deliberate exception is the error-isolation commit (see Order of commits), which only changes
what happens when something throws.

Same reminders, same times, same text, same channels, same banner lines, same widget, same settings.
In particular these must stay **byte-for-byte identical** across the update, because alarms and
notifications from v1.04 are already armed on users' phones:
- `ReminderKind` names and every notification ID from `reminderNotificationId` / `NotificationIds`.
- `RollForwardKind` names. Armed v1.04 alarms carry them as strings (`putExtra("rollKind", kind.name)`).
- Alarm and roll-forward **request codes** (`rollForwardRequestCode` etc.). A changed code would leave a
  v1.04 alarm orphaned and fire a duplicate.
- The per-kind delivery-marker columns on `ParkedState` (`normalDeliveredForMillis`,
  `rppNormalDeliveredForMillis`, `towNormalDeliveredForMillis`, `towAdvanceDeliveredForMillis`,
  `closureDeliveredForMillis`, …).

**No Room schema change in this pass.** Stay on v22. A keyed delivery-marker table would be cleaner, but
it's a separate change; note it as a follow-up if it's tempting.

### Suggested shape (adjust to what the code actually needs; explain deviations)
```kotlin
/** Built ONCE per arm pass / status pass, then handed to every source. */
data class CurbContext(
    val settings: SourceSettings,     // public; includes closure last-sync time and lead time
    val nowLocal: LocalDateTime,      // sfNow()
    val nowMillis: Long
)

interface CurbRestrictionSource {
    val id: SourceId                  // SWEEP, RPP, METER, TOW, CLOSURE

    fun isEnabled(ctx: CurbContext): Boolean

    /** Everything this source knows about one parked car, from stored data only. */
    suspend fun resolve(context: Context, parked: ParkedState, car: Car, ctx: CurbContext): SourceResult

    /** Arm/cancel this source's alarms. Reuses scheduleTiers / scheduleOrFireImmediately. */
    suspend fun arm(context: Context, parked: ParkedState, car: Car, result: SourceResult,
                    ctx: CurbContext, clearStale: Boolean): ArmOutcome   // sweep reports deadlineChanged here

    /** Cancel everything this source owns for the car. */
    fun cancelAll(context: Context, carId: Long, clearNotifications: Boolean)

    /** Park-time, phase 1: refresh this source's data. Runs in parallel with other sources
     *  under ONE shared time budget. Default: nothing. */
    suspend fun refreshForPark(context: Context) {}

    /** Park-time, phase 2: post this source's park-time notice, AFTER the single shared re-arm.
     *  Default: nothing. */
    suspend fun parkTimeNotice(context: Context, carId: Long, parkedAtMillis: Long) {}
}
```

Per-source results carry what each source needs; don't force one flat type if it doesn't fit:
- **Sweep:** deadline; `arm` returns whether the stored deadline changed (still reaches `armAllParkedStates`).
- **RPP:** merge `currentRppDeadline` and `resolveRppDeadline` into one resolver (they are logically the same).
- **Tow:** result carries **both** confident matches (for arming) and uncertain matches (for the banner status).
- **Meter:** user-set timer; contributes to `soonestDeadline()` only. Its own notification path is untouched.
- **Closure:** banner status + its own alert slots; never a deadline.
- `Unchecked` / `Stale` must still never collapse into "clear".

Wiring:
- A registry (e.g. `CurbSources.all`) lists the five sources in a fixed order. `armParkedState`,
  `loadCarsWithStatus`, `soonestDeadline`, unpark cancel, and the park-time step loop over it.
- `CarWithStatus` may keep its existing fields as computed views over the per-source results, so
  map/widget/banner code doesn't change in this pass. Fix the stale `soonestDeadline()` comment
  (it omits tow).
- The enums can stay; each source owns its entries.

**Park-time step** (replaces `runParkTimeClosureCheck`'s body, same behavior):
- Entry condition unchanged: the step still returns early when closures are off
  (`ClosureAlerts.kt:426`).
- Phase 1: sources' `refreshForPark` run in parallel under the existing single time budget
  (`CLOSURE_PARK_TIME_FETCH_TIMEOUT_MILLIS`); the Bluetooth receiver's wait
  (`CLOSURE_CHECK_RECEIVER_WAIT_MILLIS`) must still cover it. Fetch rules unchanged: a network fetch
  happens only when the **park-time check** setting is on (not merely any closure setting), and data
  still fresh is skipped (`refreshIfOlderThan`). Otherwise the step uses stored data.
- Re-arm the car **once**.
- Phase 2: `parkTimeNotice` in fixed order: closure, then tow. Nothing else.

**Sweep-in-progress notice stays where it is**, posted by `saveParkedState` at save time on every park,
regardless of closure settings (`AppDatabase.kt:155-175`). It does **not** move into the park-time step:
that step is skipped when closures are off and runs after a network wait, so moving it would suppress
or delay the notice.

**Save path is a known leftover this pass.** `saveParkedState` and `saveUnmanagedParkedState` arm sweep
and RPP directly (`scheduleParkingReminders`, `scheduleRppForParkedCar`, `AppDatabase.kt:127-147,
195-230, 304`), first clear the old spot's closure/tow/meter alarms, and record
`sweepHandled || rppHandled` in `notificationScheduled`. Its RPP resolution uses
`nextRppDeadline(..., from = parkedAt)` with its own roll-forward fallback, a third RPP resolver.
Leave all of this **untouched** in this pass:
- The RPP merge covers only `currentRppDeadline` and `resolveRppDeadline`. The save-time resolver stays
  separate; note it in the report as the remaining duplicate.
- Routing the save path through the source list is a follow-up pass, once this one is proven.
- The characterization tests still cover it (fresh park + no-street park scenarios), so the follow-up
  has a safety net.

**Unpark:** every source's `cancelAll`, **plus** the existing non-source cleanup in
`cancelParkingReminder` (Bluetooth notices, meter timer), kept exactly as it is.

**On/off switches:** tow has no switch of its own today (`towEnabled()` returns `closuresEnabled()`).
Keep that: the tow source's `isEnabled` reads the closures switch. Do not add a setting. (A separate
tow switch is now a small follow-up the new shape makes easy; not in this pass.)

### Order of commits
1. **Add Robolectric as a test-only dependency** (`testImplementation`; must not appear in the release
   APK). Confirm CI still runs the JVM tests. Setup notes:
    - The app targets SDK 37. If Robolectric has no SDK-37 runtime, pin tests with
      `@Config(sdk = [36])`, the closest available, and say so in the report.
    - AGP 9 needs `unitTests.isIncludeAndroidResources = true`.
    - `AppDatabase.getInstance` is a shared singleton. Add a tiny `@VisibleForTesting` reset hook (e.g.
      swap in an in-memory instance / clear the instance) so tests are independent. This is the **only**
      production-code change allowed before commit 2, and it must not change runtime behavior.
2. **Characterization tests against unmodified v1.04 code** (Tests §1, §2). Commit them green before
   any other production code changes.
3. **The refactor.** Pure restructure. Tests §1 and §2 must stay green **without being edited**. The
   ownership test (§4) is new in this commit, because it checks the new source list, which doesn't
   exist before; that is expected, not a broken rule.
4. **Error isolation, as its own commit** (a deliberate behavior change, only in failure cases):
    - wrap each source's resolve/arm in its own try (today only closures and tow have one; sweep and RPP
      don't, and RPP resolution in `loadCarsWithStatus` isn't protected);
    - wrap each car in `armAllParkedStates` in its own try, so one car's failure no longer stops later
      cars from being re-armed;
    - the sweep "deadline changed" result must survive its new try (a failed sweep reports "unchanged",
      not a crash);
    - wrap each park-time notice in its own try too (tow's has one; the closure notices, e.g.
      "closure nearby" at `ClosureAlerts.kt:439`, don't);
    - add the isolation tests (Tests §3) in this commit.

### Tests
Robolectric is chosen so the "before" tests run against the real v1.04 arming code, untouched.
1. **Arming snapshot** (Robolectric: real Room in-memory DB + shadow `AlarmManager` /
   `NotificationManager`). For fixture scenarios, record the exact alarms (kind, request code, trigger
   time, `rollKind` extra) and notifications v1.04 arms. After the refactor, identical output.
   Scenarios:
    - **Re-arm:** sweep only; sweep + RPP; tow confident with advance inside the lead window (fires at
      once); tow uncertain; closure blocked-in; closure nearby; off-street saved location;
      closures+tow off (one switch today, so one scenario); everything off.
    - **Fresh park** through `saveParkedState` (sweep + RPP, and a park while sweeping is in progress,
      which must post the notice at save time, including with closures off).
    - **No-street / garage park** through `saveUnmanagedParkedState`.
    - **Park-time step** with park-time check on and off, and with closures off entirely.
    - **Unpark** (everything cancelled, including Bluetooth notices and meter timer).
      Test setup marks closure/tow data as recently synced, so no Robolectric test touches the network.
2. **`soonestDeadline()` table test** across source combinations, unchanged results.
3. **Isolation** (commit 4 only): a source that throws in `resolve` or `arm` leaves the others armed;
   a car that throws leaves the other cars armed.
4. **Ownership**: every `SourceId` registered exactly once; every per-car notification ID slot
   (`NotificationIds.Purpose`) has exactly one owner, where the owner is a source or one of the
   explicitly listed non-source owners (Bluetooth, saved location, meter timer).
5. All existing JVM tests and the 193 Python script tests still pass.

If Robolectric can't host a scenario (e.g. exact-alarm permission shadows), say which one and test that
piece another way; don't drop it silently.

### Device check
Run the v1.04 checklist on the S25 and one emulator, including boot re-arm. Additionally: install
v1.04, park with an armed sweep + tow reminder, **upgrade in place** to the refactor build, and confirm
the reminders fire once each (no orphans, no duplicates).

### Out of scope
- Any new source (including whatever Part A finds).
- Schema changes, notification text changes, settings changes.
- The tow/closure merge rule (still waiting on `overlap_check.py`).
- Tier 3.

### Report back
- What shape the interface ended up in, and every place it deviates from the sketch above, with why.
- The remaining save-path leftover (third RPP resolver, direct sweep/RPP arming), described precisely
  enough to spec the follow-up pass.
- Anything that didn't fit the interface cleanly (these are the interesting findings).
- Test and device results, including the in-place upgrade check.

---

## Part A findings (2026-09-24)

Run: `python scripts\pw_permit_check.py` on 2026-09-24 (no app token in the worktree, so unauthenticated; paced
1 s between requests). `--save-dir` keeps the raw downloads for offline re-runs (`--from-dir`).

### Verdict: **CLARITI_HAS_NEW_PERMITS** (`fxfq-npa9`, `sftu-nd43`, `b6tj-gt35`)

Sign-posting permits kept being entered after 2026-07-20 in every Public Works dataset, at an unchanged rate.
But the hypothesis behind the question is **wrong in its mechanism**: the permits did not move to Clariti and
leave the tow feed behind. The tow feed never carried Clariti permits, and the old street-use system it drew from
is still issuing them at full rate. The break is on SFMTA's side (the tow feed's own export), not a Public Works
migration.

| Dataset | Sign-posting, issued | Entered after 07-20 | Per week |
|---|---|---|---|
| `fxfq-npa9` Clariti | 6,017 (since 2026-01-28) | 1,259 (Street Space 962, Temporary Occupancy 297) | ~134 |
| `sftu-nd43` Parking Signs | 191 (the whole dataset) | 178 (177 City Agency, 1 Private) | ~19 |
| `b6tj-gt35` Street-Use (approved since May) | 10,450 | 4,463 (Excavation 2,346, TempOccup 1,988, ExcStreet 116, AddlStSpac 12) | ~473 |
| `x8nh-xzn6` Active Street-Use (snapshot) | 4,551 | 2,086 | (snapshot; not a rate) |
| `6r5h-j298` tow feed (reference) | 2,240 since May | **0** | 0 |

### Which tow permits came from where
- Of the tow feed's 2,240 May–Jul rows, 814 have **no permit number** (sources: Verbal 1,299 / Web 941 overall).
- The 382 distinct permit numbers: **273 old-system Excavation (`..EXC-`)**, 6 `E`, 6 `TE`, 3 `IE`, 94 other shapes
  (`26-0974`, `26SF168`: in no Public Works dataset).
- Found by number: **288 in `b6tj-gt35`**, 33 in `x8nh-xzn6`, **0 in Clariti**, 0 in `sftu-nd43`; 94 nowhere.
- **No `TOC` (temporary occupancy) permit appears in the tow feed at all**, and no Clariti number (`TOC-26-…`,
  `SSP-26-…`) ever did, although Clariti has issued ~170 sign-posting permits a week since January. So even while it
  worked, the tow feed missed Clariti's street-space / temporary-occupancy permits.
- Clariti has 1,153 permits in a workflow phase literally named **"Tow Sign Photo"**: its permits do post tow-away signs.

### Live tow zones (step 6)
7 rows end today or later. **None has a permit number** (6 are "Verbal" entries from 2026-07-10, 1 is a 2017 row
with a 2043–2046 window, clearly junk). By CNN + overlapping window: 5 match an old-system street-use permit, 2 match
nothing. Matching by number, as the spec asked, was impossible for the live set; the May–Jul history above is the
meaningful cross-check.

### Weekly entry histogram (sign-posting types, Monday weeks)
```
week         clariti  signs  street_use  tow
2026-05-04       172      0         537  362
2026-06-01       168      0         489  215
2026-07-06       196      0         699  177
2026-07-13       214      1         517  167
2026-07-20       161      0         635   32   <- tow feed stops
2026-07-27       187      0         709    0
2026-08-10       173      0         711    0
2026-08-31       102     47         539    0
2026-09-14        99     92         441    0
```
(Excerpt; the script prints every week. The last week is partial. `sftu-nd43` only fills from mid-August: it holds
current and upcoming signs, not history.)

### Lead time (window start − entry, permits entered since 2026-06-01)
| Dataset | n | Median | p10 | p90 | Under 2 days |
|---|---|---|---|---|---|
| Clariti | 2,542 | 5.0 d | 2.0 | 13.0 | 7% |
| `sftu-nd43` | 179 | 4.4 d | 2.6 | 12.7 | 5% |
| Street-Use | 8,259 | 5.7 d | 2.8 | 20.4 | 6% |
| Tow feed (May–Jul) | 2,240 | 4.2 d | 2.3 | 12.3 | 4% |

Consistent with the ~4-day median in closures spec §9. The app's 2-day lead time would catch 93–96% of them.
Street-Use has 1,111 permits whose window starts *before* approval (renewals / back-dated), excluded from the stats.

### Next 7 days, citywide
Clariti 838, `sftu-nd43` 155, Active Street-Use 4,206 (Street-Use approved since May: 2,661). The tow feed has 7.

### Columns chosen, and how far to trust them
| Dataset | Type | Entry | Window | Location | Status |
|---|---|---|---|---|---|
| Clariti | `permit_type` | `issue_date` | `permit_start_date` → `permit_end_date` | `cnn` (text) | `status` (+ `phase`) |
| `sftu-nd43` | `category` | `datetimeentered` | `startdate` → `enddate` (+ `starttime`/`endtime`, `notes`) | `cnn` | none |
| Street-Use / Active | `permit_type` | `approved_date` | `permit_start_date` → `permit_end_date` | `cnn` | `status` |

- **Clariti stores every date as text, and dates only** (`2026-09-14`): no time of day for the window, so a
  source built on it can't know enforcement hours. All values parsed. It has no `datetimeentered`; `issue_date` is
  the closest thing, and is blank for permits not issued yet.
- **Street-Use has no entry date**: `approved_date` stands in. Its `permit_start_date` carries a time
  (`07:00`) on some rows and midnight on others.
- **`sftu-nd43` has exactly the tow feed's schema** (`datetimeentered`, `permitnumber`, `startdate`, `notes`,
  `_24hourenforcement`, `signid`...): it looks like the same SFMTA sign system, but its category is almost only
  "Construction - City Agency" and it's small. Its dates are `MM/DD/YYYY` text.
- No DST check was possible: none of these have a `*_utc` twin column to compare with.

### Statuses actually present (the "description can be wrong" check)
- Clariti: Expired 5,541, Awaiting Applicant Info 1,299, Active 789, Pending 223, Void 207, Completed 78, …
  Counted as issued: everything except Void / Withdrawn / Cancelled / Pending / Pending Review /
  Awaiting Applicant Info / blank.
- Street-Use since May: APPROVED 6,474, CLOSED 4,224, EXPIRED 2,491, RENEWED 2,463, ACTIVE 538, ELEMENT 91, …
  Excluded: VOID, WITHDRAW, CANCELLED, APPLCNT, PLANCHK, ONHOLD.
- The "Active" view `x8nh-xzn6` does hold only APPROVED / ACTIVE, but includes permits approved as far back as
  2000 (Wireless, Excavation): "active" there doesn't mean "short-notice".

### Permit types counted
- Clariti: **Street Space, Temporary Occupancy** counted; Sidewalk Repair (520) and Inspection ROW conformity (149) not.
- Street-Use: **TempOccup, Excavation, ExcStreet, StreetSpace, AddlStSpac** counted (Excavation because the tow
  feed's own permits are excavation permits). Not counted: Banners, Emergency, NightNoise, StorCont, StrtImprov,
  MinorEnc, Parklet, TableChair, Wireless, FoodFac and the rest.
- `sftu-nd43`: every row (it is a signs dataset).

### What this means for Park (owner's decision, not acted on)
- A source for short-notice no-parking signs is **available**, but none of these is a drop-in replacement for the
  tow feed: Clariti and Street-Use have **no enforcement hours or days** (the tow feed's `starttime`/`endtime`/
  `notes`), only date windows, and don't say whether signs are tow-away or just no-parking.
- Street-Use (old system) carries the permit types the tow feed was fed from, at a larger volume (it isn't
  filtered to permits that actually posted tow signs).
- Clariti covers permits the tow feed **never** had, and its "Tow Sign Photo" phase is a hint that a permit posts
  tow-away signs.
- `sftu-nd43` has the tow feed's exact shape, including hours, but covers city agencies only.
- Anything built on these would be an "uncertain / check signs" match at best, like the app's uncertain tow matches.

---

## Part B results (2026-09-25, branch `curb-sources`)

### Commits
1. Robolectric 4.17 (test-only; runs the API 37 runtime with no SDK pin, needs `--add-exports` for
   `jdk.internal.access` on the test JVM) + `AppDatabase.replaceInstanceForTests` (never called by the app).
2. Characterization tests against unmodified v1.04: `ArmingCharacterizationTest` (19 scenarios: every alarm,
   notification, delivery marker and banner status) and `SoonestDeadlineTableTest`. Mutation-checked (3
   deliberate breaks, all caught). The two stale-data scenarios were added in a follow-up commit, still on v1.04.
3. The refactor: `CurbSources.kt` (interface, `SourceSettings`, per-source `SourceResult`, five sources in the
   order sweep, RPP, meter, closure, tow). Characterization tests passed **without edits**. `CurbSourcesTest`
   checks one owner per source / kind / id slot.
4. Error isolation: per source (arm, resolve, cancel, park-time notice) and per car; a failed resolve shows
   "can't tell", never "clear"; cancellation always rethrown. `SourceIsolationTest` (4 scenarios, corrupt rows)
   fails on the pre-isolation code and passes after.

### Deviations from the sketch
- `arm` doesn't take `resolve`'s result: arming and the banner are separate passes needing different data
  (tow arms on confident matches, the banner also uses uncertain ones), so each `arm` looks up what it needs.
- `CurbContext` became `SourceSettings` (settings only): the existing functions read the clock at the moment they
  act, and changing that is beyond a pure restructure.
- `cancelAll` has no `clearNotifications` flag: only unpark uses it, and unpark always clears. Partial cancels
  (RPP re-arm, a source switched off) stay inside `arm`.
- `CarWithStatus` keeps its fields (map/widget read them), so a new source with its own banner line still
  needs a field there.

### Didn't fit cleanly
- Sweep is the only source that changes the parked row, so `arm` returns the row for the next source (`ArmOutcome`).
- Meter owns no alarm or id slot: its timer is cancelled directly by the unpark cleanup, per the spec.
- RPP has no "can't tell" state: a failed RPP resolve shows no RPP line.

### Remaining save-path leftover (for the follow-up spec)
`saveParkedState` / `saveUnmanagedParkedState` (`AppDatabase.kt:127-147, 195-230, 304`) still arm sweep and RPP
directly via `scheduleParkingReminders` / `scheduleRppForParkedCar`, with a third RPP resolver
(`nextRppDeadline(..., from = parkedAt)`, own roll-forward fallback when the regulation is null); post the
sweep-in-progress notice at save time; first clear the old spot's closure / tow / meter alarms; and store
`sweepHandled || rppHandled` in `notificationScheduled`. All covered by the characterization tests.

### Test and device results
- JVM: 318 tests pass (0 skipped at a non-11 PM run); Python: 205 pass; GitHub Actions green.
- S25, upgrade in place from v1.04 with a parked car: the alarm table after the upgrade matched the one before
  (`scripts/alarms.py`), each reminder fired exactly once, and boot re-arm passed.