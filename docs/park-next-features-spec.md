# Park — Next Features Spec

Four features, designed for handoff to Claude Code. Each section has: what it does, the exact
files/functions it touches, data model changes, and open implementation notes. Grounded against
the actual current codebase (paths/signatures below are real, not illustrative) as of the
Sept 2026 reliability pass.

---

## 1. "I moved my car" — fix + configurable behavior

### Current state (bug)
`NotificationHelper.kt` wires the urgent-tier "I moved my car" action button to
`DismissReminderReceiver`, which **only cancels the notification**. It never touches
`ParkedState`. The app still believes the car is at the old spot after this is tapped.

### New default behavior
On tap: clear the car's `ParkedState` (`ParkedStateDao.clearForCar(carId)`), cancel any
scheduled reminders for it (mirror what `cancelSweepReminder`/the RPP equivalent already do on
re-park), and open `MainActivity` to the map — **not** into the parking flow. No forced dialog.

### Configurable alternatives (Settings)
Add a new `SettingsRepository` entry, e.g. `movedCarAction: MovedCarAction`, enum:
- `CLEAR_AND_OPEN_MAP` (new default, described above)
- `SILENT_AUTO_REPARK` — reuse `ParkingMatcher.findNearbySegmentMatches` +
  `classifyMatch` against current GPS at tap time, same as `BluetoothDisconnectReceiver`'s
  auto-detect:
  - `CONFIDENT` → auto-reparks via the normal `saveParkedState` path, no UI, maybe a toast.
  - `AMBIGUOUS` → save with `sideConfirmed = false` (same convention Bluetooth uses today) +
    a notification, same as the Bluetooth ambiguous case.
  - `NO_MATCH` → **falls back to `FORCE_PARKING_DIALOG`** (see below) rather than silently doing
    nothing — "silent" isn't achievable with no plausible location, so this branch must not be
    a silent no-op.
- `FORCE_PARKING_DIALOG` — clear old state, open directly into the existing "I'm Parked" flow
  (`ParkingFlowState.ChoosingCar`/`Confirming` entry point) with current GPS pre-filled.

### Files touched
- `SettingsRepository.kt` — new DataStore key + enum.
- `SettingsScreen.kt` — new picker under Parking & Notifications.
- `NotificationHelper.kt` — action's `PendingIntent` target/extras (still routes through a
  receiver; the receiver reads the setting to decide which branch to run).
- `DismissReminderReceiver.kt` (rename/repurpose, or add logic here) — becomes the dispatch
  point for all four behaviors instead of a pure dismiss.

---

## 2. Safe parking ("no street cleaning risk" / garage scenario)

### The gap this closes
Today, `classifyMatch` returns `NO_MATCH` for GPS points with no nearby segment within 30m, but
`proceedToMatching` (`ParkingMatcher.kt`) routes **both** `AMBIGUOUS` and `NO_MATCH` into
`ParkingFlowState.PickingManually`. When candidates is empty (true no-match — a garage,
driveway, private lot), `ManualSegmentPicker` (`ParkingFlowUI.kt`) shows "No nearby streets
found in our data" with no way to actually complete parking there — "Select from map instead"
still assumes you'll tap a *street*.

### Schema — no migration needed
`ParkedState.segmentBlockSweepId: String?` and `nextSweepAtMillis: Long?` are **already
nullable**. Every read site already guards with `?.let` (`CarActions.kt`, `MapUtils.kt`,
`NotificationScheduler.kt`, `ParkWidget.kt`) — safe to write a null-segment row with today's
schema.

### New flow state
Add to `ParkingFlowState` (`ParkingFlowUI.kt`):
```kotlin
data class NoStreetNearby(val carId: Long, val point: LatLng) : ParkingFlowState()
```
Change `proceedToMatching` (`ParkingMatcher.kt`) to route `NO_MATCH` here instead of
`PickingManually` when `matches.isEmpty()`; keep `AMBIGUOUS` (and a `NO_MATCH` with a distant-but-
present candidate, if you want a middle ground — TBD, default to strict "empty candidates only")
going to `PickingManually` as today.

`NoStreetNearby`'s dialog offers two options:
- **"Not a street cleaning risk spot"** → calls the new dedicated save function (below).
- **"Select from map instead"** → falls through to today's `PickingViaMap` path, for the case
  where the matcher just missed a real nearby street.

### New dedicated save function (do NOT extend `saveParkedState`)
`saveParkedState` (`AppDatabase.kt`) requires a non-null `segment: StreetSegment` and uses it
unconditionally: `loadCurbRows(context, segment)`, the RPP-match origin
(`segment.points`/`cnnRightLeft`), and `segment.corridor` for notification text. Most of its body
(curb-schedule lookup, RPP lookup, reminder scheduling) is irrelevant to this case. Add a
separate, much smaller function instead:

```kotlin
suspend fun saveUnmanagedParkedState(
    context: Context,
    carId: Long,
    point: LatLng,
    exactPinLat: Double? = null,
    exactPinLng: Double? = null
) {
    val db = AppDatabase.getInstance(context)
    db.parkedStateDao().upsert(
        ParkedState(
            carId = carId,
            segmentBlockSweepId = null,
            sideConfirmed = true,
            parkedLat = point.lat,
            parkedLng = point.lng,
            exactPinLat = exactPinLat,
            exactPinLng = exactPinLng,
            parkedAtMillis = System.currentTimeMillis(),
            nextSweepAtMillis = null,
            notificationScheduled = false,
            rppRegulationId = null // TBD — see open question below re: metered/RPP overlap
        )
    )
    cancelSweepReminder(context, carId) // clear any stale alarms from a previous real park
    // no scheduleParkingReminders call — this is the entire point
}
```

### Visual treatment
This is **not** a fifth point on the SAFE/SOON/IMMINENT/ACTIVE urgency palette — it needs its own
distinct, neutral color, since those four colors all encode "how soon is the sweep," and this
car has nothing being timed. Apply it:
- **Map screen's priority/status banner** (the dropdown-style banner showing the most-urgent
  car) — when `soonestDeadline()` is null for the topmost sorted car, render with the new
  neutral color/style instead of falling through to a default urgency color.
- **Widget border** (`ParkWidget.kt`) — same treatment; `mostUrgent.soonestDeadline()` null
  case gets the neutral border color instead of whatever the current fallback is.

Sorting is already correct for free: `CarActions.kt`'s `loadActiveParkedCars` sorts by
`soonestDeadline()?.millis ?: Long.MAX_VALUE`, so a null-deadline car already sorts last with no
changes needed.

### Files touched
`ParkingFlowUI.kt` (new state), `ParkingMatcher.kt` (`proceedToMatching` routing), `MapScreen.kt`
(new dialog branch + banner color logic), `AppDatabase.kt` (new function),
`ParkWidget.kt` (border color logic).

### Open question to resolve during implementation
Should a car saved via `saveUnmanagedParkedState` still get an RPP match check? A garage has no
RPP concern, but a legitimately-safe-from-sweeping curb (e.g. a spot with no sweep schedule at
all but still in an RPP zone) might. Current draft above sets `rppRegulationId = null`
unconditionally — confirm that's actually desired vs. running RPP matching independently of the
sweep-segment lookup.

---

## 3. Saved Location "safe from sweeping" flag

### Design decision (per discussion)
This is a **separate, deliberate Settings-side action** — not inferred automatically from a
parking event. A location must be explicitly configured as safe *before* it can auto-apply.

### Schema change
`SavedLocation.kt` — add a new column:
```kotlin
val isSafeFromSweeping: Boolean = false
```
(Needs a Room migration — see `DatabaseMigrations.kt`, following the existing v5→v10 pattern.)

### UI
- `LocationStyleDialog.kt` (or `SavedLocationsScreen.kt`, wherever per-location settings live
  today) — add a toggle: "This location is safe from street cleaning (e.g. garage, driveway)."

### Behavior once set
- **Manual "I'm Parked" at a Saved Location tagged safe** → when GPS/pin resolves to (or is
  within a tight radius of) a safe-tagged `SavedLocation`, skip the normal matching flow
  entirely and call `saveUnmanagedParkedState` directly — no `NoStreetNearby` prompt needed,
  since the location has already told the app what it is.
- **Bluetooth auto-park** (`BluetoothDisconnectReceiver.kt`) → before running its own confident/
  ambiguous GPS-to-segment classification, check proximity against safe-tagged Saved Locations
  first; if matched, call `saveUnmanagedParkedState` and skip sweep/RPP matching entirely. This
  is what makes the garage case fully hands-off — the stated goal.

### Files touched
`SavedLocation.kt`, `DatabaseMigrations.kt`, `LocationStyleDialog.kt` (or equivalent),
`BluetoothDisconnectReceiver.kt`, wherever manual "I'm Parked" GPS resolution happens
(`MapScreen.kt`'s `proceedToMatching` call site — needs a safe-location check inserted before
it).

---

## 4. Metered parking — detection badge + optional timer prompt

### Data source
SFMTA publishes a separate "Parking Meters" dataset on DataSF (meter locations + operating
hours) — distinct from the blocksweeping feed `StreetSegment` is built from, and distinct from
the RPP regulations feed. No real-time occupancy/payment state exists (same limitation as RPP) —
this is coordinates + hours-of-operation only.

### New sync pipeline (mirror `RppZoneRepository`/`RppZoneRegulation` exactly)
- `MeteredZone.kt` — new `@Entity`, modeled closely on `RppZoneRegulation.kt`: `objectId`,
  location/points or a single point (meters are point features, not blockface polylines, so this
  may be simpler — a lat/lng + a search radius rather than a polyline distance calc), operating
  hours (`hrsBegin`/`hrsEnd`-style fields, mirroring RPP's shorthand day-range parsing if the
  feed expresses days similarly), `lastSeenSyncId: Long?` for the same stale-row-pruning pattern
  (`StaleRowPruning.kt`).
- `MeteredZoneApi.kt` — mirrors `RppDataApi.kt`'s fetch/pagination.
- `MeteredZoneRepository.kt` — mirrors `RppZoneRepository.kt`'s mutex-guarded
  `refreshFromNetwork()`.
- `MeteredZoneMatcher.kt` — mirrors `RppMatcher.kt`'s `findNearbyMeteredMatches`/
  `findConfidentMeteredMatch`, same 30m confidence radius convention.

### Operating-hours gate
The badge/prompt must only fire when the meter's own hours say it's **currently enforced** —
same reasoning as the RPP hours-parsing work: SF meters generally aren't enforced overnight or
Sundays, and showing "metered!" for a spot that's free right now would be a false alarm. Reuse
whatever day/hour-range parsing logic RPP already has (`RppZoneRegulation.kt`'s day-shorthand
parser, `SfTime.kt`) rather than writing a second implementation.

### UI integration point
**Post-confirmation step, not a separate dialog** — same place the meter-timer question was
decided to live. This is `ParkingFlowState.AskingForPin` in `MapScreen.kt` (the "Add an exact
pin?" dialog with the three `TextButton`s that each call `saveParkedState` directly). When a
confident, currently-active `MeteredZone` match exists for `state.point`, extend this step with
an additional optional action: "Set a meter timer?" → opens a small time-input affordance,
schedules a manual `AlarmManager` reminder (new alarm type, separate from the sweep/RPP tiers —
per the meter's inherently manual, urgent-only nature discussed: no "early" tier makes sense for
a meter deadline the user typed in themselves).

### Map badge
Independent of the timer prompt — a metered/non-metered indicator shown on segments/curbs with a
confident, currently-active meter match, layered on top of (not replacing) the existing SAFE/
SOON/IMMINENT/ACTIVE sweep-status coloring, since a curb can be both metered and swept
simultaneously — these are separate axes, not a replacement status.

### Files touched
New: `MeteredZone.kt`, `MeteredZoneApi.kt`, `MeteredZoneRepository.kt`, `MeteredZoneMatcher.kt`.
Existing: `AppDatabase.kt` (register new DAO/entity, migration), `MapScreen.kt` (`AskingForPin`
extension, badge rendering), `AlarmManager` scheduling code (new manual-timer alarm type,
likely alongside `NotificationScheduler.kt`'s existing tiers), `SettingsScreen.kt` (if a
"show meter badges" toggle is wanted — TBD, not explicitly decided).

---

## Suggested build order
1. **Fix "I moved my car"** — smallest, standalone, fixes an existing bug regardless of the
   other three.
2. **Safe parking (#2)** — the `NoStreetNearby` state + `saveUnmanagedParkedState` are needed
   before #3 can call into them.
3. **Saved Location flag (#3)** — builds directly on #2's save function.
4. **Metered zones (#4)** — fully independent data pipeline; can be built in parallel with 1–3,
   but its `AskingForPin` UI hook should land after #2's changes to that same dialog to avoid
   merge conflicts in `MapScreen.kt`.
