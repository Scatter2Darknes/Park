# Park — Temporary Tow Zones & Street Closures Spec

Designed for handoff to Claude Code. Covers the new **tier** framework, the **Tow Zones** feed
(built first), and the **Street Closures** feed (built second). Each section has: what it does,
where it plugs in, data model changes, and open implementation notes.

> **Verify names against the tree.** Unlike the previous spec, this one was written without a
> fresh source upload. File/class names below (`StreetDataSyncCenter`, `RppMatcher`,
> `NotificationHelper`, `SettingsRepository`, `AppDatabase`, `DatabaseMigrations.kt`,
> `DebugControlReceiver`, `ParkingFlowUI.kt`, `scripts/`) come from earlier sessions. Confirm each
> exists with the shape assumed here before editing; if something differs, follow the code, not
> this doc, and note the difference.
>
> **Checked against the tree (2026-09-23, DB v19).** All of the names above exist. Notes:
> `DataSfTrustConfig` is an `object` in `DataTrustConfig.kt`; `DebugControlReceiver` is debug-only
> (`app/src/debug/`). Sections 1, 3, 4, 5, 6 and 8 below have been corrected where the code disagreed;
> each correction is marked **(corrected)**. The owner's decisions on the open items are marked
> **Decided**.

---

## 0. Scope

Park is street information for **where and when you're parked**, or **while finding a spot**.

- Not a navigation app. No routing, no "how to get around the closure".
- No planning for the user. Park shows what the street will do; the user decides what to do.

Goals:
1. Avoid tows from temporary no-parking zones.
2. Warn when a legally parked car will be blocked in by a street closure.
3. Show upcoming closures nearby, to help gauge parking difficulty.

---

## 1. Tier framework

### Tiers
| Tier | Contents | Default |
|---|---|---|
| 1 | Everything Park does today + **park-time closure fetch** | On (park-time fetch has its own off switch) |
| 2 | **Background sync** of Tow Zones + Street Closures, **closures map layer** | Off (opt-in) |
| 3 | Future account features (cross-device sync, family sharing). Not built here. | Off (opt-in) |

Rules:
- Core (Tier 1) must work fully with every optional tier off.
- Tier switches always live in Settings. Tier 2 lives under the existing **Data & Sync** section.
  Leave room for a separate Tier 3 section later; don't build it now.
- **One-time contextual offer** for Tier 2: shown the first time the user **manually** parks after
  this update ships, never again. **(corrected)** A Bluetooth auto-park has no screen to show it
  on, so it doesn't count and doesn't consume the offer. It should name the concrete payoff: background tow/closure checks and
  upcoming closures on the map. Record "shown" in settings so it can't reappear.

### How restrictions plug in **(corrected)**
The original plan assumed a shared curb-level earliest-deadline merge that sweep, RPP and meter all
feed. **It doesn't exist.** What the code actually has:

- `CurbSchedule` merges **sweep rows only** across one curb (the reliability-pass curb fix).
- Sweep and RPP are **independent reminder families**. `armParkedState` (`NotificationScheduler.kt`)
  calls `scheduleTiers` once per family, each with its own `ReminderKind`s, `NotificationIds`
  purposes, delivery markers on `ParkedState` (`*DeliveredForMillis`) and `RollForwardKind`.
- The meter is a separate manual timer (`MeterTimer.kt`, `ParkedState.meterTimerAtMillis`).
- The only cross-source merge is **display**: `CarWithStatus.soonestDeadline()` in `CarActions.kt`,
  used by the map banner and the widget.

So retrofitting sweep/RPP/meter behind a common interface would mean rewriting the scheduler. That
is exactly the "invasive" case the original plan said to stop at. **Do not retrofit.** Instead,
add tow as a **third reminder family** alongside sweep and RPP:

- New `ReminderKind`s (e.g. `TOW_NORMAL`, `TOW_URGENT`), matching `NotificationIds.Purpose`
  entries, and a `ReminderKind.idPurpose()` / `deliveredForMillis()` branch for each.
- New nullable delivery-marker columns on `parked_state` → real `Migration` v19 → v20.
- A tow block in `armParkedState`, so boot re-arm, force-stop re-arm, settings changes and
  post-sync refresh (`refreshParkedSchedulesAfterSync`) all cover tow alarms with no extra wiring.
- A `DeadlineKind.TOW` in `soonestDeadline()` so the banner and widget show it.

Closures produce **no alarms of the deadline kind** (see §4), so they need their own notification
path, not a `scheduleTiers` family.

The interface below is kept only as a **naming model** for the new code (so tow and closure
results carry the Deadline / BlockedIn / Nearby distinction). It is not a retrofit target, and
`CurbKey` / `TimeWindow` don't exist yet; define them only if the new code needs them.

```kotlin
interface CurbRestrictionSource {
    val id: String                  // "sweep", "rpp", "meter", "tow", "closure"
    fun isEnabled(settings: Settings): Boolean
    suspend fun restrictionsFor(curb: CurbKey, window: TimeWindow): List<CurbRestriction>
}

sealed interface CurbRestriction {
    data class Deadline(...)  : CurbRestriction  // must leave by X or pay (sweep, RPP, meter, tow)
    data class BlockedIn(...) : CurbRestriction  // can't leave between X and Y (closure)
    data class Nearby(...)    : CurbRestriction  // informational, within radius (closure)
}
```

- Sweep, RPP and meter behavior must not change.
- Disabling a tier = its workers are cancelled and the tow/closure code paths check the setting.
  Park-time tow checks are Tier 1, so disabling Tier 2 does not remove tow alarms.
- `Deadline` and `BlockedIn` are deliberately different types. A closure is not a deadline and must
  never render as "move or get towed".

---

## 2. Step 0 — inspect the data before building

Before writing the pipelines, pull sample rows from both feeds and record findings in this doc
(append a "Findings" section):

- **Tow Zones** `6r5h-j298`, **Street Closures** `8x25-yybr` (both on data.sf.gov / Socrata).
- Geometry format (line / point / text only) and apparent precision.
- Start/end fields: types, timezone, continuous windows vs. daily hours.
- Whether rows carry `cnn` (and side) or need proximity matching.
- Closures only: is there a field distinguishing **full** closures from partial (lane/sidewalk)?
  How are **recurring** closures represented?
- Typical row counts (both feeds are expected to be small, current + upcoming only).

If a finding contradicts an assumption below, stop and flag it rather than working around it.

### Tooling (Python, under `scripts/`, same conventions and device-safety rules as the existing suite)
- `socrata_publish_times.py` — polls each feed's `/api/views/<id>.json` hourly for several days,
  logs `rowsUpdatedAt`. Output: when each feed actually publishes. Used to set the sync interval.
- First-seen logging — for each permit ID, record when it first appears vs. its start time.
  Output: real lead-time distribution. Used to validate the one-week map horizon and the alert
  lead-time default.

---

## 3. Phase A — Tow Zones (build first)

### Sync
- New repository + sync path for `6r5h-j298`, following the existing data.sf.gov pattern:
  `DataSfTrustConfig`, token-aware pacing/backoff, Mutex-guarded page-by-page insert, and the
  prune guard from the RPP paging fix (`pruneIfSafe` in `StaleRowPruning.kt`).
- **Always fetch the full citywide feed.** Never a location-filtered query. A bbox query around the
  car would put the user's parking location in DataSF's logs. Matching happens on-device.
- **Two kinds of pruning (corrected):**
  - **By time:** delete rows whose enforcement window has ended. Always safe, no guard needed.
  - **Unseen rows** (a permit cancelled before its end): only through `pruneIfSafe`. Note that the
    guard is stricter than "count matches the server total". It also refuses when a sync returns
    under 20% of the rows already stored (`PRUNE_MIN_FETCHED_FRACTION`), and it can never prune
    down to 0 rows. A high-churn permit feed can hit that legitimately (40 rows → 5). Result:
    a cancelled permit may linger until its end time, giving a false tow warning. That is the
    conservative direction and is **accepted**. Don't loosen the shared constant for this feed.
- After each tow sync, call `refreshParkedSchedulesAfterSync` so parked cars pick up new or
  removed zones.

### Park-time fetch (Tier 1, on by default, toggle in Settings)
- Runs **after** the parked state is saved. Never delay the GPS fix or the save.
- Short timeout. On success, match the saved curb and surface a warning if a zone applies.
- No signal / timeout: fall back to the last synced data if any; if none or too old, say plainly
  that the tow check couldn't run. Never imply the curb is clear.
- Also runs for Bluetooth auto-park saves. **(corrected)** Those saves happen in a
  `BroadcastReceiver`. Follow the Reliability Plan decision for receiver work: `goAsync()` +
  coroutine, `finish()` in `finally`, with the fetch's short timeout well inside the receiver's time
  budget. **No WorkManager and no `setExpedited`** there (`setExpedited` crashes on API < 31
  without `getForegroundInfo`, and the S9 is API 29). Manual park runs the same fetch function
  from the UI's coroutine scope.
- **Ignores the Wi-Fi-only setting.** You're at the curb on cellular; that's the point of the check.
  Wi-Fi-only applies to the Tier 2 background worker only.
- Match from the parked **lat/lng** (`parkedLat/Lng`, or the exact pin when set), not only the
  segment. Safe-location and manual no-street parks are saved without a segment
  (`saveUnmanagedParkedState`).

### Background sync (Tier 2)
- Own WorkManager periodic worker, **independent** of the sweep/meter/RPP schedule (which stays at
  72h). Runs whether or not a car is parked.
- Interval: 12–24h. Start at 12h; final value set from `socrata_publish_times.py` results. Keep it
  one constant. (The sweep/meter/RPP interval is a user setting, default 72h,
  `SettingsDefaults.REFRESH_INTERVAL_HOURS`; this one is not user-adjustable.)
- Respects the existing Wi-Fi-only setting.

### Storage
- New Room entity (e.g. `TowZone`): permit ID, source agency, geometry, enforcement start/end,
  description, first-seen timestamp. Add with a real `Migration` in `DatabaseMigrations.kt`
  (no destructive fallback).

### Matching
- Prefer `cnn` + side if Step 0 shows it's present; otherwise proximity matching modeled on
  `RppMatcher`.
- Bias toward precision. Uncertain matches become "tow zone nearby — check signs", not a hard
  warning.

### Alerts
- A matched tow zone is a `Deadline` at enforcement start, scheduled as the tow reminder family
  (see "How restrictions plug in", §1) and shown through `soonestDeadline()` in the banner and
  widget.
- **(corrected) Timing.** Existing reminders fire a set number of *minutes* before the deadline
  (`notificationOffsetMinutes`, `urgentOffsetMinutes`). A 2-day heads-up is a different kind of
  alert. **Decided (owner, 2026-09-23):**
  - an **advance alert** at the lead time (default 2 days, adjustable), on the normal channel, **plus**
  - the usual **normal + urgent reminders** at the existing offsets, so a tow gets at least the
    same last-minute nagging a sweep does.
- **(corrected) "Urgent".** Both channels (`CHANNEL_ID_NORMAL`, `CHANNEL_ID_URGENT`) are already
  `IMPORTANCE_HIGH`; "urgent" here means the final reminder uses `CHANNEL_ID_URGENT`, like the
  sweep urgent tier.
- *Default (confirm/override):* if a zone first becomes known **inside** the lead window (late
  permit), alert immediately rather than skipping it. (`scheduleOrFireImmediately` already does
  this for past-due triggers; reuse it.)
- Park-time: if the chosen spot is already inside an upcoming zone, warn in the post-confirmation
  step, before the user walks away.

### Saved locations **(corrected)**
The original plan split "garage / off-street" (no tow warnings) from "on-street safe from sweeping"
(still warned). **The data model has no such split.** `SavedLocation` only has
`isSafeFromSweeping: Boolean?` (read it as `== true`). **Decided (owner, 2026-09-23):** add an
off-street flag.

- New nullable column `isOffStreet` on `saved_location`, added in the same v19 → v20 `Migration`.
  `null` means "not off-street", so read it as `== true`, like `isSafeFromSweeping`.
- **Default is off.** Every existing and new safe location keeps getting tow warnings until the
  owner turns the flag on. That keeps the conservative default.
- **Off-street on:** tow checks are skipped for cars parked there.
- **Off-street off** (including on-street spots marked safe from sweeping): tow warnings apply.
  Safe from sweeping ≠ safe from tows.
- The flag **only affects tow zones.** Closure alerts still apply to off-street locations, because a
  closed street can block a garage exit (see §4).
- UI: a switch in `LocationStyleDialog` next to "safe from sweeping", included in
  `DataBackup.kt` export/import, and a matching `onSave` parameter in `SavedLocationsScreen`.
- Turning the flag on or off for a location with a car parked there re-runs that car's tow check.
  Hook this into the existing edit path in `SavedLocationRecompute.kt`.

**Manual "not a street cleaning risk" parks** (`ParkingFlowState.NoStreetNearby`), which the
original didn't cover: **Decided**, tow checks apply there too (same lat/lng match).

---

## 4. Phase B — Street Closures (build second)

### Overlap test first
Before the merge logic, compare the two feeds over a sample window: how often does a closure have a
matching tow zone (same street, overlapping time, nearby geometry)? There is probably no shared
permit ID, so pairing is heuristic. Record results in "Findings".

### Sync
- Same pattern as Tow Zones for `8x25-yybr`: full citywide fetch, same prune guard, same Tier 2
  worker (can share the worker with Tow Zones), and included in the park-time fetch.

### Merge rule with Tow Zones
- Where a tow zone and a closure overlap in **both place and time**, the tow `Deadline` wins for
  the overlapping interval.
- Any part of the closure the tow zone doesn't cover (other side of the street, hours after the
  tow window ends) still produces a `BlockedIn` for that remainder.
- Exact match → the user sees a single tow warning.

### Deriving "blocked in"
Not a field in the data. Derived when the car's curb lies inside a closure's extent and the car is
still parked at closure start.
- If Step 0 finds a full/partial field: only full closures produce `BlockedIn`.
- If not: flag it — don't guess.
- Out of scope: cars on an open block whose only exit runs through the closure (street-graph
  reasoning = navigation).

### Alerts
- **Confident match** (curb clearly inside extent): `BlockedIn`, e.g. "Your block closes Sat
  8am–8pm — you may not be able to move your car until then."
- **Nearby** (within radius): `Nearby`, e.g. "Street closure nearby Sat 8am–8pm — check signs."
- Radius: straight-line distance, ~150–250 m (1–2 blocks). One constant. Not street-graph.
- **Standard** notification tier for both. Never urgent-tier. Wording must not read as "move or get
  towed".
- **(corrected) Not a `scheduleTiers` family.** A closure isn't a deadline, so it doesn't go into
  `soonestDeadline()` or the sweep/RPP alarm slots. It needs its own one-shot alert at the lead
  time, re-armed from `armParkedState` like the others so it survives reboot and force-stop, with
  its own `NotificationIds.Purpose`.
- **(corrected) Banner.** The map banner shows the distinct "safe" treatment when a car has no
  segment **and** no deadline (`MapScreen.kt`, around the `soonestDeadline()` calls). A
  safe-location car with an upcoming `BlockedIn` must not show as "safe" with nothing else.
  Show the closure line in the banner too.
- Same lead-time setting as tow zones.
- Safe-from-sweeping locations (including garages) **do** get closure alerts, worded "you may not be
  able to move your car for a while". A closed street can block a garage exit.

### Map layer (Tier 2, on by default once Tier 2 is enabled)
- Draw upcoming closures on the streets they'll close, with time window on tap.
- Horizon: **one week**, as a single constant (tune from first-seen data).
- Label the horizon on the layer, e.g. "Closures through Sep 30", so empty map ≠ "no closures ever".
- Recurring closures show up naturally inside the window.
- Must play nicely with existing overlap suppression / badge z-ordering. Check against the
  existing overlay-ordering learnings before adding.
- Tier 1 users: no closures layer (a layer built from a days-old park-time fetch would look current
  but not be).

---

## 5. Settings summary

| Setting | Section | Default |
|---|---|---|
| Park-time tow/closure check | Data & Sync | On |
| Tier 2: background tow & closure sync | Data & Sync | Off |
| Closures map layer | Map | On (only visible when Tier 2 on) |
| Tow/closure alert lead time | Parking & Notifications | 2 days |
| Last checked (tow/closures) | Data & Sync | display only |

Plus the one-time Tier 2 offer flag (internal).

**(corrected)** Section names now match `SettingsCategory` in `SettingsScreen.kt` (there is no
plain "Notifications" section, and the layer toggle goes with the other layer settings under Map).
Every new setting also goes into `DataBackup.kt` export/import, like `wifiOnlyRefresh`.

---

## 6. Testing

- `DebugControlReceiver` actions to inject a fake tow zone and a fake closure on the parked curb
  (and one "nearby"), with configurable start offset. Lets alerts be tested without waiting for a
  real permit.
- Unit tests:
  - merge rule (tow wins on overlap; closure remainder still emits `BlockedIn`),
  - late-permit immediate alert,
  - prune guard on count mismatch (and time-based pruning of ended zones),
  - park-time fallback when offline (must not report "clear"),
  - tow reminders re-armed by `armParkedState` (reboot/force-stop path), and tow delivery markers
    reset on re-park,
  - Room migration v19 → v20 (new tables, `parked_state` columns, `saved_location.isOffStreet`),
  - off-street flag: skips tow checks when on, never skips closure alerts, and `null` behaves as off.
- Device checks on the usual fleet, including the pre-API-30 path on the S9.
- Verify Tier 1 behaves identically to today with park-time fetch toggled off.

---

## 7. Out of scope

- Meter pricing (on hold; `qq7v-hds4` noted for later).
- Tier 3 / any backend.
- Routing or detour suggestions.
- Unpermitted signs, police/emergency closures (not in either feed).

---

## 8. Build order

1. Step 0 inspection + `socrata_publish_times.py` + first-seen logging.
2. ~~Resolve the open decisions~~ Done 2026-09-23 (tow timing in §3 Alerts, off-street flag in
   §3 Saved locations).
3. Tier settings + one-time offer.
4. Tow Zones: sync → storage → matching → park-time fetch (`goAsync()` in receivers) → tow reminder family in
   `armParkedState` + `soonestDeadline()`. **(corrected)** Replaces the old step 2
   (`CurbRestrictionSource` retrofit); see §1.
5. Tier 2 background worker.
6. Overlap test.
7. Street Closures: sync → blocked-in/nearby → merge rule → map layer.

---

## 9. Findings (Step 0, 2026-09-23)

Pulled from `https://data.sf.gov/resource/<id>.json` at ~21:00 PDT (citywide, no location filter).
Raw downloads were kept in a scratch folder outside the repo and not committed. Note the host is
`data.sf.gov`; `data.sfgov.org` only returns an HTML redirect page.

### Tow Zones `6r5h-j298` ("SFMTA - Enforced Temporary Tow Zones")

**⚠ Blocker: the feed appears stale upstream.** DataSF re-publishes it daily (`rowsUpdatedAt` =
2026-09-23 20:54), but the newest row was **entered 2026-07-18** (`max(datetimeentered)`,
`max(datetimeposted)` = 07-17). Monthly entries in 2026: Jan 799, Apr 1,493, Jun 657, Jul 413
(through the 18th), then **none**. Median lead time is ~4 days, so a working feed should hold
permits entered in the last few weeks. **Only 6 real permits are active or upcoming right now**
(plus one junk row dated 2043–2046). Until SFMTA fixes this, a tow warning would almost never fire,
and the app must not treat "no tow zone found" as "clear". Needs an owner decision (see
"What changes in the plan" below) before Phase A is built.

Other findings, from the 15,851 rows entered since 2025-01-01:

| Question | Finding |
|---|---|
| Row count | **172,269**, not "small". It holds history back to 2005 (`status` blank on 154,582 rows; `Approved` 16,987; `Installed` 700). Sync must filter server-side by time (`enddate >= today`), which is still citywide and reveals no location. The prune guard's server total must use the same filter. |
| Geometry | **None usable.** `location` is always empty; `latitude/longitude` on 15% of rows only. |
| `cnn` | Present on 99.9%. **33% of rows list several CNNs**, comma-separated (`"5668000,5667000,5666000"`). Same format as the sweep dataset's `cnn`. |
| Side of street | `sideofstreet` is **always empty**. Match by `cnn` only and treat the zone as covering **both sides** (conservative). |
| Extent within the block | `streetfrontagefeet` (e.g. 140 of a block), `address` range, from/to streets. Too coarse to place on one part of the block: treat as the whole block. |
| Time fields | `startdate`/`enddate` are dates (time `00:00`); `starttime`/`endtime` are text like `7:00 AM` / `11:59 PM`. So a zone is **daily hours over a date range**, local SF time. |
| Days of week | Free text in `notes`: `Monday - Friday` (43%), `Monday - Sunday`, `Monday - Saturday`, single days, comma lists (`Monday,Tuesday`). 3% blank. Parse it, and when it can't be parsed treat the zone as **every day**. |
| 24-hour flag | `_24hourenforcement` Yes/No (29% Yes); Yes rows use `12:00 AM`–`11:59 PM`. |
| IDs | `casenumber` always present (15,740 distinct; up to 15 rows per case). `permitnumber` on 63%. Use `casenumber` + row as the key. |
| Lead time (start − entered) | p5 2.1 d, p25 3.2 d, **median 4.3 d**, p95 14.3 d. 3% under 2 days, 1.6% under 1 day. **The 2-day default fits**; the late-permit "alert immediately" rule matters for ~3%. |
| Duration | Median 19 days, p90 32 days. |
| Categories | `category` and `signtype` mostly blank recently; `source` Web / Verbal. Not useful for filtering. |

### Street Closures `8x25-yybr` ("Temporary Street Closures")

| Question | Finding |
|---|---|
| Row count | **4,628** rows, 429 cases. Small, as expected. 4,337 end in the future; 350 start within 7 days. |
| Freshness | Looks live: `data_loaded_at` today, rows start from 2026-09-14. Some rows already ended (lag of ~a week), so time-pruning is needed. `data_as_of` is always null. |
| Status | **Not only "Permitted"**, despite the column description: Permitted 4,283, In Review 216, Submitted 92, Pending Payment 29, etc. **Filter to `Permitted`.** |
| Geometry | `shape` is a **LineString** on every row (street centerline between two intersections). |
| `cnn` | On every row, **exactly one** per row. Matches the sweep `cnn` format. |
| Side / direction | `direction` is `undefined` / `unknown` / `both`: no per-side info. A closure closes the block. |
| Full vs partial | **Yes: `veh_imp`** (WZDx VehicleImpact): `all-lanes-closed` 94%, `some-lanes-closed` 6%, `all-lanes-open` 0.4%. Only `all-lanes-closed` produces `BlockedIn`; the other two become `Nearby` at most. |
| Time fields | `start_dt`/`end_dt` local SF time, plus `start_utc`/`end_utc`. **The `_utc` fields are wrong on ~10% of daylight-saving rows** (253 of ~2,780 starts: local + 8 h instead of + 7 h, i.e. an hour late); winter rows are all correct. **Use the local fields, read as `America/Los_Angeles`**, with `_utc` only as a fallback. (Corrected while building; the first pass of these findings said the opposite.) |
| Windows | Median 13 h. 325 rows span several days. 165 of those don't run midnight-to-midnight (e.g. Fri 16:00 → Mon 06:00, or a year-long Shared Space 10:00 → 23:59), and the feed can't say whether that is continuous or daily. **Treat `start → end` as continuous** (it over-warns, never under-warns). |
| Recurring | **Expanded into one row per occurrence**: e.g. a Shared Space case has 326 rows for one CNN, one per day. No recurrence rule to parse. |
| Types | Roadway Shared Spaces 3,143 (car-free streets, mostly permanent-ish), Special Event 1,226, Special Traffic Permit 259 (construction; `info` free text). |
| Precision caveat | `start/end` can be wider than the real work: a PG&E row says 00:00–23:59 while its `info` says 8 am–5 pm. Fine for "blocked in" (conservative). |

### Overlap between the feeds
The tow feed has 527 rows with `source = "Street Closure Case"`, so SFMTA does link some closures to
tow zones. A real overlap test can't run while the tow feed is stale (its active set is 6 rows).
Deferred until the tow feed is current again.

### What changes in the plan
- **Tow Zones:** blocked on the stale feed (owner decision). If built anyway: time-filtered
  sync, `cnn`-list matching (split on commas), whole block both sides, daily hours + parsed days,
  and the park-time result must say "tow data may be out of date" when the newest entry is
  more than a few days old. A freshness check on `max(datetimeentered)` is cheap and should gate
  any "no tow zones" wording.
- **Street Closures:** everything the spec needs is there. Filter `status = Permitted`, use
  `veh_imp` for full vs partial, `cnn` for confident matches and the LineString for "nearby"
  distance and the map layer.
- **Build order:** consider building **Street Closures first**, since that feed is live.
