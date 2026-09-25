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