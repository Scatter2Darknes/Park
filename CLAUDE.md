# Park — notes for Claude Code

Personal Android app (Kotlin, Jetpack Compose, Room, osmdroid) that maps SF street-sweeping and RPP rules so the owner avoids parking tickets. Reminder correctness is the core value: prefer conservative behavior when unsure.

- **Before starting any task, read `docs/ReliabilityPlan.md`** (and `docs/Followup-Plan.md`, `docs/Automation_plan.md`, `docs/Permissions-banners-plan.md` for later-stage work). Work one task at a time on its own branch, commit in small steps, then stop and report. Never merge to `main` or start the next task unprompted.
- Treat anything marked VERIFY in a plan as a hypothesis. Check the code first and say so if it disagrees.
- The owner is new to Kotlin. Explain Kotlin/Android concepts briefly as you introduce them, and explain why.
- You probably can't run the app. Run `./gradlew testDebugUnitTest` where possible and give the owner clear manual test steps for device-only checks. `scripts/` has Python tools (see `scripts/README.md`) for driving an emulator or a physical phone via adb — read that file before writing a new one.
- Room: real `Migration` objects only, never destructive fallback from v5 onward, commit the exported `app/schemas/*.json`.
- Every new `BroadcastReceiver` must be declared in `AndroidManifest.xml`.
- Never commit `app/src/main/assets/stadia_api_key.txt` or `datasf_app_token.txt` (both are gitignored).
- After large edits to `MapScreen.kt`, `SettingsScreen.kt` or `MapUtils.kt`, check brace balance.

## Safety rules for anything touching a device (scripts and manual adb alike)

1. **The default target is the emulator.** With no device specified, use the ONE running emulator. Never pick a physical phone on its own, not even if it's the only thing attached. Several emulators, or none, is refused with a list of what's attached. To use a phone, pass its serial explicitly.
2. **Never:** uninstall the app, clear its data, run `connectedAndroidTest` / `connectedDebugAndroidTest` (they uninstall the app afterwards, wiping a phone's data), or delete/write anything inside the app's `databases/` or `files/` folders. The only delete allowed is a script's own temp file in `/data/local/tmp/`. Logcat is only ever read, never cleared.
3. **A physical phone is read-only by default.** Reading is fine (backups, alarm dumps, log capture, state dumps); anything that changes the phone's state (a force-stop cancels the app's reminder alarms; a reboot) needs an explicit device serial **and** a typed confirmation. Debug-hook actions that park/unpark/re-arm refuse a physical phone outright.
4. **Privacy.** Backups and logs contain parking locations and car names. `backups/`, `logs/`, `*.db*` and `park_database*` are gitignored. Never paste their contents anywhere automatically, and don't share a backup or log folder.
5. **A guard test enforces rule 2.** `scripts/tests/test_guard.py` scans every script for the forbidden commands and fails if one appears, including inside comments. Keep it passing when adding new scripts.