# Park — Status Update: "Reminder health" warnings (P1 + P2 done)

Handoff for the next session or another Claude chat. Builds on `docs/Permissions-banners-plan.md` (the plan), `docs/StatusUpdate-Automation.md` (A1–A7) and the reliability/follow-up docs. **Nothing is merged or pushed. Nothing has been run on a physical phone.**

## Where things stand

- **Branch:** `notif-blocked-banner`, created off `ci` (the tip of the stack; `main` lacks the stack). Two commits on top of `0aacf6b`:
  - `349d7ce` P1 — app changes.
  - `44b0d4d` P2 — scripts.
- **Uncommitted, deliberately left alone (the owner's):** `app/build.gradle.kts` (versionName 1.02), staged `docs/Automation_plan.md` and `docs/Permissions-banners-plan.md`, untracked `docs/StatusUpdate-Automation.md` and this file. Commit with explicit paths (`git commit --only -- <paths>`), never a bare `git commit`.
- **Tests:** 126 JVM unit tests pass (11 new in `ReminderHealthTest`); 153 Python tests pass (`python -m unittest discover -s scripts/tests`); `python scripts/check_release_manifest.py` passes on a fresh release build.

## What was built (P1)

- `ReminderHealth.kt` (new): pure `evaluateReminderHealth(...)` → `OK | NOTIFICATIONS_BLOCKED | REMINDER_CHANNEL_BLOCKED`. App-level block wins; then the normal reminders channel; then the urgent one (both reminder channels count). The quiet "Parking status" channel is reported (`statusChannelBlocked`) but is never the red banner. A missing channel is not "blocked". Also: `currentReminderHealth(context)`, banner text, `openReminderHealthSettings` (app page, or the blocked channel's own page).
- `MapScreen.kt`: red banner ABOVE the exact-alarm banner, shown while a car has reminders and health != OK; refreshed on `ON_RESUME` (no polling).
- `SettingsScreen.kt`: "Notifications: Allowed / Blocked" section above "Exact alarms".
- `NotificationHelper.showReminder`: when permission/app-level/channel is blocked, logs `notifications blocked — reminder not shown` (tag `Park`, warning) and returns **false** — so the delivery marker isn't recorded and a later re-arm retries. (Before, a channel-only block returned true and counted as delivered.) `ParkingReminderReceiver` and the immediate-fire path both go through this function.
- Debug `DUMP_STATE`: extra `notifications:` line (`notificationsEnabled`, channel importances, `reminderHealth`).

## What was built (P2)

- `scripts/alarms.py`: on Android 13+ notifications come from `dumpsys package` (`POST_NOTIFICATIONS granted=`); appops is the fallback. Prints the source and, when blocked, `WARNING: notifications are BLOCKED — reminders will not be shown`. `--json` has `notifications_blocked`. App-level only (can't see one channel).
- `scripts/capture_logs.py`: "Notifications blocked" digest category (count + quotes).
- `scripts/README.md` updated; new fixtures `dumpsys-package-notifications-{granted,denied}.txt`.

## Verified on the emulator (Medium_Phone, Android 17)

App-wide block → banner → Fix opens Park's notification page → allowing clears it on return. Channel-only block → channel banner → Fix opens that channel's page. No parked car → no banner. Both banners stack in order (notifications, exact alarm, car). Settings row shows the state. Log line appears on a test notification with the channel off. `alarms.py` and `capture_logs.py` run live against the emulator.

**Not verified:** the S25/other phones; a green check on GitHub (never pushed).

## Open items

1. **P1b (needs the owner's decision):** ask for the notification permission at the first park, if never requested, with a one-line explanation? The banner works either way. Not started.
2. **P1 on the S25 (owner, manual):** turn notifications off and on only through the app's system settings screen; check banner appears and clears.
3. **P3 (gated):** boot-missed detector (`Settings.Global.BOOT_COUNT` vs a count stored by `BootReceiver`) — only if the owner reports another intermittent boot failure. Owner should first run `python scripts\rearm_check.py --mode boot --device <serial>` 2–3 more times on the S25 (from the repo folder) and report each result plus anything changed between runs.
4. Status tables in `docs/Permissions-banners-plan.md` / `docs/Automation_plan.md` still say "not started" (staged, untouched).
5. To ship: the owner decides when to `git merge --ff-only` and push; CI has never run on GitHub.

## Gotchas learned

- **Emulator vs phone:** scripts default to the one running emulator; with the S25 attached, raw `adb` needs `-s emulator-5554`. `adb` isn't on PATH in Git Bash: use `$LOCALAPPDATA/Android/Sdk/platform-tools`. Start the emulator with `emulator -avd Medium_Phone`.
- **Testing exact-alarm denial on the emulator:** `appops set <pkg>` is overridden by a uid-level allow; use `appops set --uid com.example.park SCHEDULE_EXACT_ALARM deny` (and `allow` to restore).
- **App-wide block on the emulator:** `pm revoke/grant com.example.park android.permission.POST_NOTIFICATIONS`. A single channel is switched off through the system settings page (open it with the `CHANNEL_NOTIFICATION_SETTINGS` intent).
- **Debug unpark doesn't refresh an open map:** after `debug_hooks.py unpark` the DB is empty but the running UI keeps showing the car until the app is force-stopped and reopened. Pre-existing, not touched.
- **Shell tool strips one level of backslashes and rejects some quoting:** write patch scripts with the file-writing tool for anything containing backslashes or apostrophes. Files mix CRLF/LF; edit scripts preserve each file's line endings.
- **Standing rules:** never merge/push unless asked; don't touch a physical phone without explicit instruction; don't edit `docs/Claude.md` / `docs/Followup-Plan.md` (the owner's edits are stashed); safety rules for scripts (emulator default, never uninstall/clear/`connectedAndroidTest`, never clear logcat, the guard test).
