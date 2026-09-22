# Park — Status Update: the test-automation pass (A1–A7)

For updating another Claude conversation. It builds on what that chat already has: the reliability plan (T0–T9), the follow-up pass (F0–F6, see `docs/StatusUpdate-FollowUpPass.md`) and `docs/Automation_plan.md`. Everything is committed locally on stacked git branches. **Nothing is merged to `main` or pushed, and none of the app changes or scripts have been run on the owner's physical phones.**

## Where things stand

- **Branch stack** (each on the previous): `main` ← `rearm-reminders` (T1) ← `exact-alarm-flow` (T2) ← `schedule-recompute` (T3) ← `bt-notif-timeouts` (T4) ← `tunnel-detection` (T5) ← `sweep-tests` (T6) ← `rpp-fixes` (T7) ← `stale-rows` (T8) ← `build-hygiene` (T9) ← `location-permission-prompt` ← `notif-id-ranges` (F0+F2) ← `sweep-perf` (F3) ← `rpp-zero-limit` (F4) ← `curb-merge` (F5+F6) ← `automation-scripts` (A1–A5) ← `debug-hooks` (A6) ← **`ci` (A7, the tip)**.
- **App:** Room v15. The app code did not change in this pass except one new debug-only source set (see A6). 115 JVM unit tests and 7 emulator instrumented tests passed at the end of the follow-up pass.
- **Scripts:** 139 Python tests pass with no device (`python -m unittest discover -s scripts/tests -v`).
- **Language:** the plan said PowerShell unless Python 3 was installed, and asked to check with the owner. Python 3.12 is installed, so the owner was asked and chose **Python**. Standard library only, nothing to install. `scripts/README.md` documents everything.

## What was built

| Task | Script / file | What it does |
|---|---|---|
| A1 | `scripts/common.py`, `tests/test_guard.py`, `tests/test_device_selection.py`, `.gitignore` | Shared helpers and the safety rules (below). The guard test fails if any script contains a forbidden command, and tests itself. |
| A2 | `scripts/backup_db.py` | Read-only backup of the database (+ `-wal`, `-shm`) and settings files to `backups/<timestamp>/`, verified by size, SQLite header and a re-hash on the device, with a `manifest.txt`. Debug builds only (uses `run-as`). |
| A3 | `scripts/alarms.py` | Decodes `dumpsys alarm` into a table: reminder or roll-forward, due time in SF and device time, minutes before the sweep, exact or INEXACT, plus the exact-alarm and notification permissions. `--json`, `--raw`, `--file`. |
| A4 | `scripts/rearm_check.py` | `--mode foreground` (force-stop, reopen, alarms should return) and `--mode boot` (reboot). PASS = 0, FAIL = 1, precondition ("park a car with a future sweep first") = 2. |
| A5 | `scripts/capture_logs.py` | Saves the app's Logcat + crash buffer to `logs/`, prints a digest: parking saves, re-arm/recompute/roll-forward, `BootReceiver`, stale-row cleanup totals, exact-alarm warnings, Tunnel decisions, crashes with first stack lines. `--since`, `--follow`, `--from-file`. Reads only, never clears the log. |
| A6 | `app/src/debug/.../DebugControlReceiver.kt` + `app/src/debug/AndroidManifest.xml`, `scripts/debug_hooks.py`, `scripts/check_release_manifest.py` | A debug-only, exported receiver (DUMP_STATE, PARK via the normal `saveParkedState` path, UNPARK, REARM; replies in Logcat under `ParkDebug`). `rearm_check.py --scenario park|unpark` uses it so a whole park → check → reboot → check run is unattended on an emulator. `check_release_manifest.py` proves a release APK has no trace of it (manifest via `aapt2`, code via `classes*.dex`). |
| A7 | `.github/workflows/ci.yml`, `instrumented.yml`, `tests/test_ci_workflow.py` | CI on push and pull request: JVM unit tests + debug build (JDK 21), and the Python tests as a second job. No secrets, read-only permissions, only unit-test reports uploaded, only on failure. A separate manual-only workflow runs the Room migration tests on a throw-away emulator. |

### Safety rules (enforced by the scripts and their tests)
1. The default target is the ONE running emulator. A physical phone is never picked implicitly, even if it is the only device attached; several emulators or none is refused. To use a phone, pass `--device <serial>`.
2. Never in any script: uninstall the app, clear its data, run `connectedAndroidTest`/`connectedDebugAndroidTest` (they uninstall the app afterwards, wiping a phone's data), or delete/write inside the app's `databases/` or `files/`. The only delete allowed is a script's own temp file in `/data/local/tmp/`. Logcat is never cleared.
3. A physical phone is read-only by default. Anything that changes its state (a force-stop cancels the app's alarms; a reboot) needs `--device` and a typed confirmation phrase, decided from the serial alone so nothing is sent first. `debug_hooks.py park/unpark/rearm` refuse a phone outright.
4. Backups and logs contain parking locations and car names: `backups/`, `logs/`, `*.db*`, `park_database*` are gitignored.
5. The guard test scans every script (comments included) for the forbidden commands.

## What was verified, and how

**On the real emulator (Android 17), unattended:**
- The app's own `DUMP_STATE` expected 6 alarms and `dumpsys alarm` showed the same 6 at the same times, all exact.
- `rearm_check --mode foreground --scenario park`: PASS (all 6 alarms cleared by the force-stop and returned when the app opened).
- `rearm_check --mode boot --scenario park`: PASS across a real reboot; Logcat confirmed `BootReceiver: BOOT_COMPLETED`.
- `--scenario unpark` then a check: exit 2 with the precondition message.
- `backup_db.py`: PASS on 5 files; the backup opens as a valid Room v15 database with 37,878 street segments; temp files cleaned up. `--no-stop` works. A release build is refused with a clear message; an unknown serial, a bad `--adb` path, and phone-only with no `--device` are all refused (the phone was sent nothing but `adb devices`).
- `check_release_manifest.py`: the release APK and its merged manifest are clean; the same checker FINDS the receiver in the debug APK and fails a debug APK presented as release.
- CI: the workflow's exact commands in a fresh clone with no `local.properties` and no API-key files gave BUILD SUCCESSFUL (48 tasks), and BUILD FAILED (exit 1) when a unit test was deliberately broken.

**Not verified:**
- Anything on the owner's phones (the S25, S20, S9, Tab S9).
- A green check on GitHub itself (needs a push, which was never done). Whether the runner has the compileSdk platform available is untested; the workflow installs the SDK through `android-actions/setup-android`.
- The phone dump format is RECONSTRUCTED from the plan's appendix (real lines from the owner's S25) plus a third alarm and a history block; the emulator fixtures are real captures.

## Bugs and surprises found along the way
- **Android restarts a force-stopped app almost immediately.** Logcat showed WorkManager's job service restarting it 0.2 s after a force-stop, which could change the database mid-backup. Sizes alone can't catch a same-size change, so `backup_db.py` now confirms the app stayed down (re-stopping if needed) and re-hashes every file on the device after copying, marking the backup FAIL if anything changed.
- **The inexact-alarm window is printed as a duration** (`window=+1h0m0s0ms`), not a number, in the newer dump format (seen on another app's alarm in the real dump). The parser understands both; otherwise it would misread exactly the case that matters.
- **Ambiguous "minutes before the sweep".** With a sweep AND an RPP roll-forward alarm the dump can't say which belongs to which reminder, so `alarms.py` marks those minutes with `*` as approximate.
- **`gradlew` was committed non-executable** (mode 100644), which fails on a Linux CI runner. Fixed in its own commit (`0aacf6b`); the workflow also `chmod`s it.
- **The shell tool used for the work strips one level of backslash escaping** from command text, which corrupted several patch scripts (`\n`, `\b`). Worth knowing if scripts or tests are edited through it: use a file-editing tool for anything containing backslashes.

## Decisions and judgment calls
- Python instead of PowerShell (owner's choice).
- With an emulator AND a phone attached and no `--device`, the script uses the emulator (the plan's two rules conflicted; the safer reading was chosen). The owner can ask for "refuse whenever two devices are attached" instead.
- On a phone `backup_db.py` asks for a typed `FORCE-STOP` before stopping the app, because a force-stop cancels its reminder alarms; `--no-stop` skips it.
- The `capture_logs.py` tag list adds `ParkBluetooth` and `ParkDebug` to the plan's five.

## Housekeeping and open items
- **The safety rules were NOT added to `docs/Claude.md`** as the plan asked: that file has the owner's uncommitted edits in a stash ("docs before tip"), and editing it would make them conflict. The rules are in `scripts/README.md` for copying.
- **`docs/Automation_plan.md`** is staged in the owner's index and was left untouched; its Status table still says "not started". (Several other docs, including earlier guides, are no longer in `docs/`; the F1 investigation summary is `docs/investigations/F1-raw-data-findings.md`.)
- **Two commit accidents from earlier:** the F2 commit (`4d4ad91`) includes an empty `docs/Followup-Plan.md` the owner had staged (real content still uncommitted), and commit `a1fbe45` swept in a `versionName` bump. The owner has since changed `versionName` to 1.02 in `app/build.gradle.kts`; that is uncommitted and was left alone.
- **Still to do by the owner** (none started, all need real devices): back up the phone with `python scripts\backup_db.py --device <serial> --no-stop`; record a baseline with `alarms.py`; install the tip build; the F6 curb test (park on a curb swept on two different weekdays); the reboot check on the phone with `rearm_check.py --mode boot --device <serial>` (asks for `REARM-CHECK` and an unlock); re-run the T0 query in a browser and check SFMTA's page for nightly sweeping on Thanksgiving Day; then push for backup (`git push -u origin --all`, which would also give CI its first real run), `git merge --ff-only` the tip and tag it.
- **Possible next work (not started):** show all of a multi-row curb's sweep days on the parking confirm screen (reminders already cover all rows); update the Status tables in the docs.
