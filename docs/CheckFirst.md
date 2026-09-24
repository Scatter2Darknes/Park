# Park — What to Check First (before the next pass)

Do these in order; each one protects the next. The full test steps for every task are in `docs/TestingGuide.md`. Test from the tip of the stack, `curb-merge` (Room v15).

## 1. Back up first
Copy the app's database, **including the `-wal` file**, before installing over an old build. The new build migrates the database from v11 to v15 on real data.

## 2. T1 baseline — before installing the new build
With the **old** build still installed:
1. Park a car on a block with a sweep a few hours out.
2. Run `adb shell dumpsys alarm | grep -A3 com.example.park`.
3. Reboot, unlock, and run the command again. The alarms should be **gone**.

Skip this if you have already installed the new build (the comparison is spoiled).

## 3. Install from the tip and open the app
Your cars, saved locations and settings should all still be there. This one check covers all five migrations at once.

## 4. Turn notifications on, then Sync Now (streets and RPP)
- The first sync after the upgrade deletes rows the feed no longer returns, so it takes a while.
- Logcat should show `Stale-segment cleanup removed N rows` (tag `DataSF`) and `Stale-RPP cleanup removed N rows` (tag `RppSync`).
- The car permit-zone picker must **not** list a zone "0".

## 5. F6 curb test (most valuable)
1. Find a curb swept on **two different weekdays** — a 7-day nightly route such as 3rd St between Howard and Clementina, or a downtown commercial block — and park on it.
2. The parked-cars banner and the reminders should use the **soonest** of the days, not just one.
3. Use "Fix schedule" on any row of that curb and confirm the parked car's deadline moves.
4. A Bluetooth auto-park on such a curb should now be "confident", not "Did you just park?".

## 6. Reboot test (T1)
1. Park with a sweep a few hours out, reboot and unlock. `dumpsys alarm` should show the alarms **back**; Logcat (tag `Park`) shows `BootReceiver: BOOT_COMPLETED — re-arming reminders`.
2. Let an **urgent** reminder fire, tap it, kill and cold-start the app. It must **not** be re-posted.

## If steps 3–6 pass, the rest is lower risk
- Exact-alarm banner and Settings row (turn off **"Alarms & reminders"** itself, not notifications).
- F2: Settings → Test Bluetooth Hooks — fire a park and an unpark notice while an RPP or sweep reminder for the same car is showing; neither should replace the other. Also tap Snooze on a Settings test notification.
- Tunnel dimming on a real drive with long red lights (Logcat tag `Tunnel`).
- Release build (`app/build/outputs/apk/release/park-release-shrunk-test.apk`, debug-signed) on the S25 and the S9: map, sync, Bluetooth hooks, widget, notifications.
- Pan the map around downtown on the S9 and watch for lag.

## Small things to sort out
- Re-run the T0 query in a browser: `https://data.sf.gov/resource/yhqp-riqs.json?$select=holidays,fromhour,tohour,count(*)&$group=holidays,fromhour,tohour`, and check SFMTA's holiday page for whether nightly sweeping is also off on Thanksgiving Day. Both were read through a summarizing tool.
  - **Holiday page re-checked 2026-09-24** (https://www.sfmta.com/getting-around/drive-park/holiday-enforcement-schedule, asked for verbatim wording): nightly street sweeping (12am–6am) is "Not Enforced" on New Year's Day, Thanksgiving Day and Christmas Day and "Enforced" on every other listed holiday, including the Day After Thanksgiving; weekday daytime sweeping (6am–2pm) is not enforced on any listed holiday. That matches `SfHolidayCalendar` (`majorHolidaysOnly` / `fullSuspensionHolidays`) exactly. The T0 query itself was already re-run on raw data in F1.
- The F2 commit (`4d4ad91`) accidentally includes an empty `docs/Followup-Plan.md`; the real content is still an uncommitted change. Ask to have it removed if you want it gone.

## Warnings
- Do **not** run `./gradlew connectedDebugAndroidTest` on a physical phone: it uninstalls the app afterwards and wipes its data. Use an emulator (`ANDROID_SERIAL=emulator-5554`).
- If something misbehaves, fix forward with a commit on top rather than rebasing the stack.
