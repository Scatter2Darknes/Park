# Park — Test Automation Plan

Goal: replace the manual "run an adb command, paste the output, decode it by hand" loop with small, safe scripts, plus a debug-only hook and CI. Companion to `docs/ReliabilityPlan.md`, `docs/followup-plan.md` and `docs/TestingGuide.md`. Read the "How to work" and "Invariants" sections of the reliability plan first; they still apply.

## 0. How to work

- One task at a time, on its own branch, small commits, then stop and report. Start from `main` if it already contains the T0–T9/F-series stack; otherwise from `curb-merge` (the tip). These tasks add scripts and a debug-only source set, so they shouldn't conflict with feature work. Never merge or push unless Z asks.
- Z is on **Windows** and runs commands from Command Prompt / PowerShell. Write the scripts in **PowerShell 5.1-compatible** `.ps1` (it's on every Windows machine) unless `python --version` shows Python 3 is already installed. In that case ask Z whether they'd rather have Python. Don't install anything without asking.
- Z is new to Kotlin and to scripting. Explain non-obvious choices briefly.
- Android package: `com.example.park`. `adb` lives at `C:\Users\chank\AppData\Local\Android\Sdk\platform-tools\adb.exe` and is **not** on PATH. Scripts resolve it from a `-Adb` parameter, then `$env:ANDROID_HOME\platform-tools`, then PATH.
- Files the app owns: `databases/park_database` (+ `-wal`, `-shm`) and `files/datastore/settings.preferences_pb`, `files/datastore/GlanceAppWidgetManager-com.example.park.preferences_pb`. Only debuggable builds allow `run-as`; the shrunk release test APK does not.

### Safety rules (also add them to `CLAUDE.md`)

1. **Default target is the emulator.** No device given and exactly one emulator running → use it. If several devices are attached and none is specified, refuse and list them. Pass a serial explicitly for a physical phone.
2. **Never, in any script:** `adb uninstall`, `pm clear`, `pm uninstall`, `connectedAndroidTest`/`connectedDebugAndroidTest`, `run-as … rm` on app data, or any write into `databases/` or `files/`. On a phone these wipe the app and its data. The only deletion allowed is removing the script's own temp files under `/data/local/tmp/`.
3. **Physical phone: read-only by default.** `alarms`, `capture-logs` and `backup-db` are fine on a phone (backup only reads via `run-as`). `rearm-check` reboots or force-stops the phone, so it requires an explicit serial **and** an interactive confirmation.
4. **Privacy:** backups and logs contain parking locations and car names. Gitignore `backups/`, `logs/` and `*.db*`; make sure none of them can be committed or pushed. Never paste their contents anywhere automatically.
5. Add a guard test (a script that greps `scripts/` for the forbidden commands and fails if found), so a future edit can't add one silently.

### Status

| Task | Branch | State |
|---|---|---|
| A1 Shared helpers + guard test | `automation-scripts` | done (Python, not PowerShell: Z's choice) |
| A2 `backup-db` | same | done; verified on emulator |
| A3 `alarms` (decoder) | same | done; verified on emulator |
| A4 `rearm-check` (boot and foreground modes) | same | done; both modes PASS on emulator; boot mode passes on the S25 (2026-09-24) |
| A5 `capture-logs` | same | done; verified on emulator |
| A6 Debug-only control receiver (optional) | `debug-hooks` | done; release APK checked clean |
| A7 CI on push | `ci` | done; merged in v1.03 and pushed. Whether the GitHub run is green hasn't been checked here: see the Actions tab |

All A tasks shipped in v1.03 (`main`).

None of it has run on a physical phone yet. Details: `docs/StatusUpdate-Automation.md`.

## A1. Shared helpers and guard

`scripts/common.ps1`, dot-sourced by the others:
- Resolve `adb` (parameter, `ANDROID_HOME`, PATH; clear error message otherwise).
- Resolve the target device per the safety rules; expose `Invoke-Adb` (always passes `-s <serial>`, sets `[Console]::OutputEncoding` to UTF-8 so log text like em dashes isn't garbled, fails loudly on non-zero exit).
- Helpers: timestamps, "is this an emulator?" (`ro.kernel.qemu` or serial prefix `emulator-`), confirmation prompt, output folders.
- `.gitignore` entries from safety rule 4.
- `scripts/tests/test-guard.ps1`: fails if any script contains the forbidden commands.
- `scripts/README.md`: one paragraph per script with an example command.

**Acceptance:** guard test passes and fails when a forbidden string is temporarily added; running any script with two devices attached and no `-Device` refuses.

## A2. `backup-db.ps1`

- Force-stop the app (skip with `-NoStop`), list the files with sizes via `run-as ls -l`.
- Copy each file **without** PowerShell `>` redirection (it corrupts binary output). Use `run-as com.example.park cat <file> > /data/local/tmp/<name>` executed inside the device shell, then `adb pull`, then delete only that temp file.
- Save to `backups/<yyyy-MM-dd_HHmmss>/`. Verify each pulled file's byte size equals the on-device size, and that the main database begins with `SQLite format 3`.
- Write a small `manifest.txt` in the folder: device serial, app `versionName` and `lastUpdateTime`, file sizes, and the tool version. Print a one-line PASS/FAIL summary.
- No restore script. Restoring stays a manual, documented procedure because it writes into app data.

**Acceptance:** on the emulator and (read-only) on Z's phone, sizes match, header check passes, temp files removed, backup folder is gitignored.

## A3. `alarms.ps1` (decoder)

Turn `adb shell dumpsys alarm` output into a readable table. Parse tolerantly. Android versions format this differently, so if a line doesn't parse, print it raw instead of failing.

- **Live alarms:** entries like `RTC_WAKEUP #86: Alarm{… type 0 origWhen 1790096400000 whenElapsed … com.example.park}` followed by a `tag=*walarm*:com.example.park/.<Receiver>` line and an `operation=PendingIntent{… PendingIntentRecord{<id> …}}` line. Convert `origWhen` (epoch ms) to America/Los_Angeles and to device-local time.
- **Kind label:** by receiver (`ParkingReminderReceiver` = reminder, `ScheduleRollForwardReceiver` = roll-forward, plus any others). Because a `dumpsys` line can't tell normal from urgent, show the minutes before the nearest roll-forward alarm (which fires at the sweep start), e.g. `-120 min`, `-15 min`.
- **Inexact detection:** the history lines (`[tag=… H=PI:<id> OW=… WL=3600000 …]`) share the PendingIntent id with the live alarm (`H=PI:2b6896f` ↔ `PendingIntentRecord{2b6896f`). Use the most recent history entry per id: `WL` above 0 means inexact, and `WL=0` means exact. Print "window unknown" when there's no history entry.
- Also report `appops get com.example.park SCHEDULE_EXACT_ALARM` and `POST_NOTIFICATIONS`.
- Output a table plus a summary ("3 live alarms; next: Tue 2026-09-22 06:00 PT; inexact: yes; exact permission: deny"). Add `-Json` for machine use and `-Raw` to dump the filtered raw lines.
- **Tests:** commit a fixture file (`scripts/tests/fixtures/dumpsys-alarm-sample.txt`) captured from the emulator, and a self-check script that parses it and asserts the expected count, kinds and times. The Appendix shows the real format from Z's S25 to pin the parser to.

**Acceptance:** the parser test passes; on Z's phone it reproduces the three alarms and the inexact flag Z saw by hand.

## A4. `rearm-check.ps1`

Automates the two re-arm checks Z has been doing by hand. `-Mode boot|foreground`.

- **Preconditions:** run `alarms`. If there are no live alarms, abort with "park a car with a future sweep first" (that's a precondition failure, not a test failure). Record the pre-state (set of receiver + `origWhen`).
- **`foreground` mode:** `am force-stop` → confirm no live alarms → launch the app (`monkey -p com.example.park -c android.intent.category.LAUNCHER 1`) → poll `alarms` every 2 s up to 30 s → PASS if the same set is back.
- **`boot` mode:** `adb reboot` → `adb wait-for-device` → poll `sys.boot_completed`. On a physical phone print "Unlock the phone, then press Enter" (a lock screen can't be automated). Then poll up to 90 s → PASS if the same set is back with `rtc=` history times after the reboot. Also grep the log for `BootReceiver: BOOT_COMPLETED` and report whether it's present.
- **Physical phone:** requires an explicit serial and a typed confirmation, and prints exactly what it will do first.
- Print PASS/FAIL with a short diff (missing/extra alarms).

**Acceptance:** both modes PASS on the emulator with a parked car; deliberately breaking the re-arm (or running with no parked car) yields FAIL or the precondition message.

## A5. `capture-logs.ps1`

- `adb logcat -d -s Park RppSync DataSF Tunnel AndroidRuntime` plus the crash buffer (`-b crash`), saved UTF-8 to `logs/<timestamp>.txt`. `-Since "MM-dd HH:mm:ss"` maps to `logcat -t`. `-Follow` streams live.
- Print a short digest of the lines Z usually needs: `saveParkedState`, re-arm / recompute lines, `BootReceiver`, `Stale-… cleanup removed N rows` (both tags), `Exact alarm permission not granted`, `Tunnel` decisions, and any `AndroidRuntime` exception with the first stack lines.
- Note the buffer is cleared by a reboot and is small, so capture soon after the event.

**Acceptance:** digest matches what's in the raw file; the em dash prints correctly.

## A6. Debug-only control receiver (optional; do after A1–A5 work)

**Purpose:** drive and inspect the app without tapping through the UI, so emulator scenarios can run unattended (park → check alarms → reboot → check again).

- A `BroadcastReceiver` in `app/src/debug/` (its manifest entry only in the debug source set), so it **cannot** exist in release. Actions (all logged under tag `ParkDebug`):
    - `DUMP_STATE`: log every parked state (car, segment id, curb key, `nextSweepAtMillis`, delivery markers, `limitAssumed`), its computed next deadlines, the exact-alarm permission state, and the count of scheduled alarms it *thinks* it has.
    - `PARK` with car id and lat/lng: run the normal `saveParkedState` path.
    - `UNPARK` with car id.
    - `REARM`: call the same re-arm function the boot receiver calls.
- Emulator location: `adb emu geo fix <lon> <lat>`.
- **Verify** the release manifest contains no debug receiver (`apkanalyzer manifest print` or `aapt dump xmltree` on the release APK) and add that as a script or test.
- Extend `rearm-check` with `-Scenario` options that use these hooks, once they exist.

**Acceptance:** `DUMP_STATE` output matches what the UI and `alarms` show; the release APK manifest doesn't contain the receiver.

## A7. CI on push

`.github/workflows/ci.yml`:
- On push and pull request: check out, set up the JDK the project requires (check `gradle.properties` / toolchain settings; **VERIFY**), cache Gradle, run `./gradlew testDebugUnitTest assembleDebug`, and upload test reports as an artifact on failure.
- The API-key asset files are gitignored and `ApiKeys` falls back to blank, so CI builds without secrets. **Do not add any secrets to the repo or workflow.** Confirm the build passes with those files absent.
- Optional (manual `workflow_dispatch`, since it's slow): run the instrumented Room migration tests on an emulator with `reactivecircus/android-emulator-runner`.
- The repo is private. Don't enable Actions artifacts or logs that could expose backups.

**Acceptance:** a push shows a green check; deliberately breaking a unit test turns it red.

## Not for Claude Code (Z's checklist)

- Put `platform-tools` on PATH or set `ANDROID_HOME`.
- Start an emulator (`emulator-5554`) for scenario work; keep the phone for read-only checks and the reboot check.
- Unlock the phone when `rearm-check -Mode boot` asks.
- Review Claude Code's permission prompts. Allow read-only `adb` commands (`dumpsys`, `logcat`, `getprop`, `appops get`) if you want fewer prompts, and keep prompts on for anything else.
- Manual only: real tunnel drives, real Bluetooth, Samsung battery/Doze behavior, notification and widget appearance.

## Appendix: real `dumpsys alarm` format (from Z's phone)

Live entries (after `adb shell dumpsys alarm | findstr com.example.park`):

```
    RTC_WAKEUP #80: Alarm{e685937 type 0 origWhen 1790082000000 whenElapsed 129897319 com.example.park}
      tag=*walarm*:com.example.park/.ParkingReminderReceiver
      operation=PendingIntent{526eea4: PendingIntentRecord{2b6896f com.example.park broadcastIntent}}
    RTC_WAKEUP #82: Alarm{8df51d3 type 0 origWhen 1790089200000 whenElapsed 137097319 com.example.park}
      tag=*walarm*:com.example.park/.ScheduleRollForwardReceiver
      operation=PendingIntent{e365110: PendingIntentRecord{5a53b05 com.example.park broadcastIntent}}
```

History entries (a log of past sets, not live alarms):

```
      [tag=*walarm*:com.example.park/.ParkingReminderReceiver T=0 F=32 AC=false H=PI:2b6896f OW=2026-09-22 06:00:00.000 WL=3600000 elapsed=976255 rtc=2026-09-20 18:11:18.936]
```

`origWhen 1790089200000` = 2026-09-22 08:00 PDT. `WL=3600000` means an inexact one-hour window (exact alarms denied). The findstr filter drops lines that don't contain the package name, so the real script should filter differently (e.g. keep the `tag=`/`operation=` lines that follow a matching `Alarm{…}` line).