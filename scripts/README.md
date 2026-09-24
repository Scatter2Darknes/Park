# scripts/ - test automation helpers

Small Python 3 scripts (standard library only - nothing to install) that replace the "run an adb command, paste the output, decode it by hand" loop. They are described in `docs/Automation_plan.md`. Run everything from the repository root, e.g. `python scripts\backup_db.py`.

| Script | What it does | Changes the device? |
|---|---|---|
| `backup_db.py` (A2) | copies the app's database and settings to `backups/`, verified | no (reads; may force-stop the app) |
| `alarms.py` (A3) | decodes `dumpsys alarm` into a table: kind, due time, exact/inexact | no |
| `rearm_check.py` (A4) | checks the alarms come back after a force-stop or a reboot | yes: force-stop / reboot |
| `capture_logs.py` (A5) | saves Park's Logcat to `logs/` and prints a digest | no |
| `debug_hooks.py` (A6) | parks / unparks / re-arms / dumps state via the debug-only receiver | yes (emulator only), except `dump` |
| `check_release_manifest.py` (A6) | proves a release APK contains no debug receiver | no |
| `common.py` (A1) | shared helpers (not run directly) | - |

## Safety rules (the scripts and their tests enforce these)

1. **The default target is the emulator.** With no `--device`, a script uses the ONE running emulator. It never picks a physical phone on its own - not even if the phone is the only thing plugged in. Several emulators, or none, is refused with a list of what's attached. To use a phone, pass its serial: `--device R52WA025A5R`.
2. **Never, in any script:** uninstalling the app, clearing its data, running `connectedAndroidTest` / `connectedDebugAndroidTest` (they uninstall the app afterwards, wiping its data on a phone), deleting or writing anything inside the app's `databases/` or `files/` folders. The only delete allowed is a script's **own temp file** in `/data/local/tmp/`. Logcat is only ever read, never cleared.
3. **A physical phone is read-only by default.** Reading is fine (`backup_db.py`, `alarms.py`, `capture_logs.py`, `debug_hooks.py dump`); anything that changes the phone's state (a force-stop cancels the app's reminder alarms; a reboot) needs an explicit `--device` **and** typing a confirmation phrase, and `debug_hooks.py park/unpark/rearm` refuse a phone outright.
4. **Privacy.** Backups and logs contain parking locations and car names. `backups/`, `logs/`, `*.db*` and `park_database*` are in `.gitignore`. Never paste their contents anywhere automatically, and don't share a backup or log folder.
5. **A guard test enforces rule 2.** `scripts/tests/test_guard.py` scans every script for the forbidden commands and fails if one appears - including inside comments. It also tests itself: each rule is checked against a bad example it must catch and a legitimate command it must let through.

> These rules should also go in `CLAUDE.md` (`docs/Claude.md`). That file wasn't touched: it currently has uncommitted edits of yours stashed away, and editing it would make them conflict. Copy the five rules above into it when you're ready.

## The scripts

**`common.py`** (A1) - not run directly. Finds `adb` (`--adb`, then `ANDROID_HOME`, then `PATH`, then Android Studio's default folder `%LOCALAPPDATA%\Android\Sdk\platform-tools`), chooses the target device under the rules above, runs every `adb` command with `-s <serial>`, decodes output as UTF-8 (so em dashes aren't garbled), and fails loudly on a non-zero exit. The "Target: ..." notice goes to stderr so a script's real output stays clean for piping. Other helpers: the typed confirmation (no input counts as a refusal) and the guarded temp-file delete.

**`backup_db.py`** (A2) - copies `databases/park_database` (+ `-wal`, `-shm`) and `files/datastore/*.preferences_pb` to `backups/<yyyy-MM-dd_HHmmss>/` and verifies them.

```
python scripts\backup_db.py                          # the one running emulator
python scripts\backup_db.py --no-stop                # don't force-stop the app first
python scripts\backup_db.py --device R52WA025A5R --no-stop   # a phone, deliberately, without stopping the app
```

- It force-stops the app first so the database isn't changing mid-copy, and **confirms the app really stayed down**: Android can restart it within a fraction of a second, so it re-stops it if needed. **On a phone a force-stop cancels the app's scheduled alarms until you open it again**, so it asks you to type `FORCE-STOP`; use `--no-stop` to skip that.
- Only a **debuggable** build works (the copy uses `run-as`). The shrunk release test APK is refused with a clear message: install the debug build.
- The copy is made on the device (the device's own shell writes each file to a temp file in `/data/local/tmp/`, the script `adb pull`s it, then deletes only that temp file), because sending binary data through a Windows console corrupts it.
- Checks: every pulled file's size equals its size on the device, the main database starts with `SQLite format 3`, and every file is re-hashed on the device after the copy so a change made while copying is caught. Writes `manifest.txt` and prints one `PASS:` / `FAIL:` line (exit code 0 for PASS).

**`alarms.py`** (A3) - the alarm table.

```
python scripts\alarms.py                    # table + summary
python scripts\alarms.py --json             # machine-readable
python scripts\alarms.py --raw              # also the raw dumpsys lines it read
python scripts\alarms.py --file saved.txt   # parse a saved dumpsys instead of asking a device
```

Shows each live alarm of the app: `reminder` or `roll-forward` (by receiver), when it is due in San Francisco time and device time, the minutes before the sweep for reminders, and whether Android will fire it **exactly or INEXACTLY** (the sign that exact alarms are denied). Also the exact-alarm and notification permissions. **Notifications** are read from `dumpsys package com.example.park` (`POST_NOTIFICATIONS: granted=`) on Android 13+, because `appops` only says a cryptic `ignore` (or nothing at all) for a denied app; older Android falls back to `appops`. The output says which source it used, e.g. `notifications permission: deny (from dumpsys package)`, and when they are blocked it prints `WARNING: notifications are BLOCKED — reminders will not be shown` (the alarms still fire, but Android drops every notification, so a parked car gets no reminder at all); `--json` carries `notifications_blocked`. This reads the app-level permission only: if just one notification channel is switched off, `alarms.py` can't see it, but the app's own map banner, the debug `dump` line (`notifications: ... remindersChannelImportance=0`) and the digest below do. It understands two dump formats: newer builds print a `window=` line per alarm (a plain `0` for exact, a duration like `+1h0m0s0ms` for inexact); the phone's format has none, so it reads the alarm history (`WL=` on the entry with the same PendingIntent id, taking the latest by `rtc=` time). Anything it can't parse is listed raw instead of failing. When a car has both a sweep and an RPP roll-forward alarm, the "minutes before" figures carry a `*`: the dump can't say which roll-forward belongs to which reminder, so they are measured to the nearest one and may refer to the other.

**`rearm_check.py`** (A4) - does the app re-arm its alarms?

```
python scripts\rearm_check.py --mode foreground     # force-stop, open the app, alarms should return
python scripts\rearm_check.py --mode boot           # reboot, alarms should return
python scripts\rearm_check.py --mode boot --scenario park     # first park a car via the debug receiver (emulator)
```

It records the live alarms, wipes them (force-stop or reboot), waits (30 s / 90 s) for the SAME alarms (receiver + due time) to return, and prints PASS or FAIL with what is missing or extra. Boot mode also reports whether Logcat shows `BootReceiver: BOOT_COMPLETED`. No live alarms to begin with is a **precondition** problem (exit code 2, "park a car with a future sweep first"), not a failure. Exit codes: 0 PASS, 1 FAIL, 2 precondition. On a phone it needs `--device` plus typing `REARM-CHECK` after it prints exactly what it will do, and a boot check asks you to unlock the phone. `--scenario park|unpark` uses the debug receiver to set the scene, so the whole check runs unattended on an emulator.

**`capture_logs.py`** (A5) - Logcat capture and digest.

```
python scripts\capture_logs.py                        # save logs/<timestamp>.txt and print the digest
python scripts\capture_logs.py --since "09-20 18:30:00"   # only lines from then on (device clock)
python scripts\capture_logs.py --follow               # stream live until Ctrl+C
python scripts\capture_logs.py --from-file logs\old.txt   # digest a saved file
```

Captures the app's tags (Park, RppSync, DataSF, Tunnel, AndroidRuntime, plus ParkBluetooth and ParkDebug) and Android's crash buffer, as UTF-8. The digest counts and quotes: parking saves, re-arm / recompute / roll-forward lines, `BootReceiver`, the stale-row cleanup results (with per-tag totals), exact-alarm warnings, `Notifications blocked` (a count of the app's `notifications blocked — reminder not shown` warning: a reminder went off but Android would not show it, whether the whole app or just the reminders channel is blocked), `Tunnel` decisions, and any crash with its first stack lines. Logcat's buffer is small and a reboot clears it, so capture soon after the event.

**`debug_hooks.py`** (A6) - drive the app without the UI, through `DebugControlReceiver` (`app/src/debug/`, which only exists in **debug** builds).

```
python scripts\debug_hooks.py dump                                        # what the app thinks: cars, parked states, expected alarms
python scripts\debug_hooks.py park --car-id 1 --lat 37.7802 --lng -122.4610
python scripts\debug_hooks.py unpark --car-id 1
python scripts\debug_hooks.py rearm                                       # the re-arm BootReceiver runs
python scripts\debug_hooks.py inject-closure --car-id 1 --kind blocked --start-in-minutes 2885   # fake street closure; alert in ~5 min
python scripts\debug_hooks.py clear-closures                              # remove every fake closure, re-arm
```

`inject-closure` puts a fake street closure on a parked car's block (`--kind blocked`) or about 120 m away (`--kind nearby`), starting `--start-in-minutes` from now (default 3 days) and lasting `--duration-minutes` (default 12 h). The "blocked in" alert goes out 2 days before the start, or at once if that's already past; `nearby` never notifies, it only shows in the banner. `dump` prints each car's closure marker and banner line. Both closure commands are emulator-only.

`park` goes through the normal `saveParkedState` path. `dump` lists the alarms the app expects, which should match `alarms.py`'s live list (checked on the emulator: 6 expected, 6 live, same times). Replies come back through Logcat (tag `ParkDebug`). `dump` also prints a `notifications:` line (`notificationsEnabled`, each channel's importance, and `reminderHealth`: `OK`, `NOTIFICATIONS_BLOCKED` or `REMINDER_CHANNEL_BLOCKED`; a channel importance of 0 means it is switched off). On an emulator, `adb shell pm revoke com.example.park android.permission.POST_NOTIFICATIONS` (kills the app, keeps its data) and `pm grant` toggle the app-level block; a single channel is switched off in the system settings page. `dump` only reads; `park`, `unpark` and `rearm` change state, so they run on an emulator only. `adb emu geo fix <lon> <lat>` sets the emulator's GPS location if you want to test that too.

**`check_release_manifest.py`** (A6) - proves a release APK has no debug receiver.

```
python scripts\check_release_manifest.py                                   # the release APKs Gradle built
python scripts\check_release_manifest.py --expect-debug app\build\outputs\apk\debug\app-debug.apk   # sanity check of the checker itself
```

Reads the compiled manifest with the SDK's `aapt2` and searches the compiled code (`classes*.dex`) for `DebugControlReceiver`. Build a release APK first (`gradlew assembleRelease`).

## Tests

```
python -m unittest discover -s scripts/tests -v
```

They need no device: `test_guard.py` (the safety guard), `test_device_selection.py` (which device gets chosen), `test_backup_db.py`, `test_alarms.py` (against a real emulator capture and an S25-format fixture in `tests/fixtures/`, plus `dumpsys package` samples with `granted=true` / `granted=false` for the notification check), `test_rearm_check.py`, `test_capture_logs.py`, `test_debug_hooks.py` and `test_check_release_manifest.py`. Each script's logic is tested against a fake device, including the failure paths.

## Restoring a backup (manual on purpose)

There is deliberately no restore script, because restoring **writes into the app's private data**. Do it on an **emulator** first. On a phone it replaces your real parked-car data, so take a fresh backup first and only do it if you accept that.

1. Force-stop the app: `adb -s emulator-5554 shell am force-stop com.example.park`
2. Push the files to the temp folder: `adb -s emulator-5554 push backups\<folder>\databases\park_database /data/local/tmp/`
3. Delete the old `-wal` / `-shm` files inside the app folder (a stale one corrupts the restored database), then copy the pushed file in with `run-as`, e.g. `adb -s emulator-5554 shell "run-as com.example.park sh -c 'rm -f databases/park_database-wal databases/park_database-shm; cat /data/local/tmp/park_database > databases/park_database'"` - and repeat for any settings file you want back.
4. Remove the temp copy, then open the app.

Restoring a `-wal` file on its own is not enough to reconstruct a database; back up (and restore) the main file together with its `-wal`.

## CI (A7)

`.github/workflows/ci.yml` runs on every push and pull request: the JVM unit tests and the debug build (`./gradlew testDebugUnitTest assembleDebug`, JDK 21), and these Python tests as a second job. It uses **no secrets** (the two API-key files are gitignored and the app builds without them), has read-only permissions, and uploads only the unit-test reports, and only when a run fails. `.github/workflows/instrumented.yml` runs the Room migration tests on a throw-away emulator, **manually only** (Actions tab -> Instrumented tests -> Run workflow). `scripts/tests/test_ci_workflow.py` fails if a workflow ever references a secret or key, gains write permissions, or uploads anything but test reports.

Checked locally: the workflow's commands pass in a fresh clone with no `local.properties` and no key files, and go red when a unit test is deliberately broken. A green check on GitHub itself can only be confirmed by pushing.
