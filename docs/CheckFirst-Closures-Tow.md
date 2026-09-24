# Park — What to Check Before Merging the Closures & Tow Stack

Everything after v1.03 (`main`, commit `17112ed`) is on one straight line of branches, each on the previous, ending at
**`notif-permission-at-park`**. Test from that tip. Nothing is merged or pushed yet.

`main` ← `closures-spec-review` ← `closures-step0` ← `closures-data` ← `closures-park-check` ← `closures-emulator-test`
← `closures-settings` ← `parking-flow-back-cancel` ← `closures-map-layer` ← `closures-hidden-hint` ← `plan-status-tables`
← `tow-zones` ← `spot-type-choice` ← `settings-grey-out` ← `curb-detail-sheet` ← `overlap-check` ← **`notif-permission-at-park`**

Already checked (2026-09-24): all JVM unit tests and all 193 script tests pass; the release APK builds and
`check_release_manifest.py` passes (no debug receiver in it). Owner-tested on emulators: tow alerts and the stale-feed
banner, off-street skipping RPP and tow, the first-park notification ask (API 33+).

## 1. Back up first (each phone)
The database migrates **v19 → v22** (closures v20, closure marker v21, tow zones + off-street v22). Take a backup
before installing: `python scripts\backup_db.py --device <serial>` (it asks before force-stopping the app on a phone).

## 2. Migration test — emulator only, never a phone
`ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`
(it uninstalls the app afterwards, which is why it must never run on a phone). Covers 19→20, 20→21 and 21→22.

## 3. Install from the tip and open the app
Cars, saved locations, settings and any parked car should all still be there. Saved locations that were "safe from
street cleaning" should now show **"On the street, never swept"** in their edit dialog.

## 4. Street closures (most of the stack)
- Park manually. Within a few seconds the banner may show a closure line; Settings → Data & Sync shows "Closures
  last checked: just now".
- The one-time map card offering background checks appears after the first manual park.
- With background checking on, closures show on the map (orange-and-black dashes, 🚧 badge with times on tap).
- Emulator: `python scripts\debug_hooks.py inject-closure --car-id 1 --start-in-minutes 2885` → "your block will be
  closed" alert in about 5 minutes.

## 5. Tow zones
- Settings → Data & Sync shows "Tow zones last checked" and, while the city feed is stale, the red "tow-zone list
  looks out of date" note; the map banner says "City tow-zone data may be out of date … check signs". Expected today.
- Emulator: `inject-tow --car-id 1 --start-in-minutes 2885` → "tow-away zone posted" in about 5 minutes;
  `inject-tow --car-id 1 --start-in-minutes -30 --duration-minutes 120` → urgent "in effect now" at once;
  `clear-tow` afterwards.

## 6. Saved locations: "What kind of spot is this?"
- Set a location to **Off the street**, park there: no RPP (permit) and no tow reminders; closures still checked.
- Switch it to **On the street, never swept** with the car still parked: the RPP reminder comes back.

## 7. First-park notification ask (P1b) — needs an Android 13+ phone (S25, S20, Tab S9)
If notifications are off and Park never asked, the first manual park shows "Get parking reminders?". It asks once.
Not on the S9 (Android 10: no such permission).

## 8. Smaller UI changes
- Settings: dependent options are greyed out with "Needs: …" instead of hidden (urgent timing, Bluetooth options,
  Check Now); Bluetooth auto-detect warns right under its switch when Location isn't "Allow all the time".
- Scroll bars: edit dialogs (car, saved location, permit zones, fix schedule) show a bar on the right; colour/icon rows
  show one underneath.
- Tap a block on a 7-day nightly route (e.g. 3rd St between Howard and Clementina): the details sheet lists every
  schedule, "Next cleaning" is the soonest, and every day is red on the calendar.
- Parking flow: Back and Cancel on every step; a pin far from the chosen street asks before saving.

## 9. Reboot (S25)
`python scripts\rearm_check.py --mode boot --device <serial>` with a car parked: PASS, now including any tow alarms.

## If it all passes: merge
The stack is linear, so `main` fast-forwards to the tip:
```
git switch main
git merge --ff-only notif-permission-at-park
git push -u origin --all
```
Then bump the version (v1.04?) and tag it. If something fails, fix forward with a commit on the tip rather than
rebasing the stack.

## Waiting on the city
The tow overlap test (closures spec §4) needs a current tow feed. When the red Settings note disappears, run
`python scripts\overlap_check.py` and decide the closure/tow merge rule from its output.
