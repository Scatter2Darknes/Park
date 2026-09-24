# Park — "Reminder health" warnings (notifications blocked, boot missed)

Small follow-up to `docs/ReliabilityPlan.md` (T2's exact-alarm banner is the pattern to copy) and `docs/automation-plan.md`. Read the reliability plan's "How to work" and "Invariants" first; they still apply.

## Why

On Z's S25, `POST_NOTIFICATIONS` was **denied** (`granted=false, flags=[USER_SET…]`) while everything else looked healthy. Alarms were scheduled, but Android silently drops notifications, so a parked car got **no visible reminders at all**. The app gave no sign. That is the same failure class T2 fixed for exact alarms, and it is worse (total silence instead of late reminders). Separately, one boot re-arm run failed on the S25 with no Park log lines and a later run passed; the cause is unconfirmed, so a cheap detector may be worthwhile.

## 0. How to work

- One task at a time, own branch off `main` (or the tip, `ci`, if `main` doesn't contain the stack yet), small commits, stop and report after each. Never merge or push unless Z asks.
- Z is new to Kotlin: explain concepts briefly and say why.
- You probably can't run the app on a phone. Test on the emulator with the scripts (`scripts/README.md`); the safety rules there still apply. Anything that changes a physical phone's state stays manual.
- The T2 exact-alarm banner and Settings row are the model: reuse its structure and its place in the map's top-center Column (most persistent element first, transient ones last).

### Status

| Task | Branch | State |
|---|---|---|
| P1 Notifications-blocked banner + Settings row | `notif-blocked-banner` | code done; verified on emulator, awaiting S25 check |
| P1b Contextual permission request at first park (optional) | `notif-permission-at-park` | done (Z: yes, 2026-09-24); verified by Z on an API 33+ emulator (asks once, "Not now" isn't repeated, reset-notification-ask brings it back) and on API 29 (never asks); phones not yet |
| P2 Script/digest updates | same | done; verified on emulator |
| P3 Boot-missed detector | `boot-missed-detector` | **not built**: Z's further `rearm_check --mode boot` runs pass (2026-09-24). Revisit only if a boot failure comes back. |

## P1. Notifications-blocked banner

**Detection (a small pure function so it can be unit tested):**
- App-level: `NotificationManagerCompat.from(context).areNotificationsEnabled()`. This covers the Android 13+ runtime permission and the older app-level toggle.
- Channel-level: the reminders channel (`parking_reminders`) can be blocked on its own. Check `NotificationManager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE`. Also check the "Parking status" channel, but treat it as lower severity because it carries only informational notices.
- Result type, e.g. `ReminderHealth { OK, NOTIFICATIONS_BLOCKED, REMINDER_CHANNEL_BLOCKED }`.

**Banner:**
- Persistent, red, shown while at least one car is parked with reminders enabled and health is not OK. It sits **above** the exact-alarm banner (total silence is more severe than late reminders); both can show at once.
- Text: "Notifications are off — Park can't show sweep reminders" / "The Park reminders channel is off — …". One button:
    - app-level: `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `Settings.EXTRA_APP_PACKAGE`.
    - channel-level: `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` with the channel id.
- Re-check on resume (lifecycle `ON_RESUME`) so the banner clears right after the user returns from settings. Don't poll.

**Settings row:** under Settings → Parking & Notifications, next to the exact-alarm row: "Notifications: Allowed / Blocked" with the same button. Mirror T2's layout.

**Delivery-side log:** when `ParkingReminderReceiver` (and the immediate-fire path) is about to post while notifications are disabled, log `Park: notifications blocked — reminder not shown` at warning level. `notify()` silently no-ops otherwise, and this line lets `capture_logs.py` surface it.

**Debug hook:** extend the debug `DUMP_STATE` action to log `notificationsEnabled` and the reminders channel importance.

**Tests:** a JVM unit test for the pure health function (enabled, disabled, channel blocked, channel missing). Manual emulator check: `adb shell pm revoke com.example.park android.permission.POST_NOTIFICATIONS` (revoking kills the app process but doesn't touch its data) or block the app in system settings; then confirm the banner. On Z's phone this check is manual, through the settings UI only.

**Acceptance:**
- Block notifications while a car is parked: banner appears on return to the app; the button opens the right settings screen; allowing them clears the banner on return.
- Block only the reminders channel: the channel-specific banner shows.
- With no car parked, no banner.
- The exact-alarm banner still works, and both stack in the right order.

## P1b. Contextual permission request at first park (optional; needs Z's decision)

The project deliberately deferred the notification-permission prompt (Android's guidance is to ask when the user has context). The banner is the safety net either way. A middle path: the first time the user parks a car and the permission has **never been requested**, ask right then, with a one-line explanation of why. After a denial, Android stops showing the dialog (two denials), so the banner's settings button is the only path from then on. Skip this if Z prefers to keep the prompt deferred.

**Built (2026-09-24):** after the first MANUAL park (`finishManualPark`) on Android 13+ with notifications not granted and
`notificationPermissionAsked` false, a short "Get parking reminders?" dialog explains why, then "Allow reminders" opens
Android's dialog. Any way out (Allow, Not now, back) sets the flag, so Park never asks again; the Settings button sets it
too. On grant, reminders are re-armed so one that fell due while blocked is posted. Bluetooth auto-parks don't ask (no
screen). Rule unit-tested (`shouldAskNotificationPermissionAtPark`); `debug_hooks.py reset-notification-ask` resets the flag.

## P2. Script and digest updates

- `scripts/alarms.py`: today it prints `notifications permission: ignore` from `appops`, which is a correct but cryptic reading. Make `dumpsys package com.example.park` `POST_NOTIFICATIONS granted=` the primary source on Android 13+ (fall back to appops on older versions). When blocked, print a loud line: `WARNING: notifications are BLOCKED — reminders will not be shown`. Add fixture tests using the real sample line: `android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`, plus a `granted=true` sample.
- `scripts/capture_logs.py`: add the `notifications blocked — reminder not shown` line to the digest with a count.
- Update `scripts/README.md`.

## P3. Boot-missed detector (gated: only build if Z reports another intermittent boot failure)

**Evidence so far (S25):** one boot check failed (no Park log lines afterward; the alarms only came back when the app was opened) and the next passed with `BootReceiver: BOOT_COMPLETED` logged. `RUN_ANY_IN_BACKGROUND` is `allow`; standby bucket 30 (`FREQUENT`). Z hasn't confirmed whether anything changed between the two runs. **Ask Z for a verdict after two or three more `rearm_check.py --mode boot` runs on the phone before starting P3.**

**Design if needed:**
- `Settings.Global.BOOT_COUNT` (API 24+, readable without a permission; confirm on the API 29 device) increments per boot.
- `BootReceiver` stores the boot count it handled. At each app foreground, if the current boot count differs from the stored one, then the phone rebooted and the boot receiver didn't run for that boot.
- Response: log `Park: boot receiver did not run for boot N — re-armed on app open` (visible to `capture_logs.py`) and show a one-time dismissible notice ("Your phone restarted and Park's reminders weren't restored until you opened the app. Check battery settings for Park."), with a button to the app's battery settings. The foreground re-arm has already restored the alarms by then, so this is informational and diagnostic, not a persistent banner.
- Unit test the comparison logic as a pure function.

## Acceptance for the whole doc

- JVM tests pass; the release manifest checker still passes (`scripts/check_release_manifest.py`); the guard test still passes.
- The debug `DUMP_STATE` output shows the new fields.
- Z verifies P1 on the S25 through the settings UI (block, banner, allow, cleared).

## Not for Claude Code (Z's checklist)

- Keep notifications allowed on the S25 for all the other checks; when testing P1, turn them off only through the app's system settings screen, then back on.
- Run the boot check two or three more times (from the repo folder, not `platform-tools`) and report each result and anything you changed in between, so P3 can be decided.