# scripts/ - test automation helpers

Small Python 3 scripts (standard library only - nothing to install) that replace the "run an adb command, paste the output, decode it by hand" loop. They are described in `docs/Automation_plan.md`; this folder currently has **A1 (shared helpers and the guard test)** and **A2 (`backup_db.py`)**. The rest of the plan (A3 alarms decoder, A4 re-arm check, A5 log capture, A6 debug receiver, A7 CI) isn't built yet.

Run everything from the repository root, e.g. `python scripts\backup_db.py`.

## Safety rules (the scripts and their tests enforce these)

1. **The default target is the emulator.** With no `--device`, a script uses the ONE running emulator. It never picks a physical phone on its own - not even if the phone is the only thing plugged in. Several emulators, or none, is refused with a list of what's attached. To use a phone, pass its serial: `--device R52WA025A5R`.
2. **Never, in any script:** uninstalling the app, clearing its data, running `connectedAndroidTest` / `connectedDebugAndroidTest` (they uninstall the app afterwards, wiping its data on a phone), deleting or writing anything inside the app's `databases/` or `files/` folders. The only delete allowed is a script's **own temp file** in `/data/local/tmp/`.
3. **A physical phone is read-only by default.** Reading is fine (`backup_db.py`); anything that changes the phone's state (a force-stop cancels the app's reminder alarms; a reboot) needs an explicit `--device` **and** typing a confirmation phrase.
4. **Privacy.** Backups and logs contain parking locations and car names. `backups/`, `logs/`, `*.db*` and `park_database*` are in `.gitignore`. Never paste their contents anywhere automatically, and don't share a backup folder.
5. **A guard test enforces rule 2.** `scripts/tests/test_guard.py` scans every script for the forbidden commands and fails if one appears - including inside comments. It also tests itself: each rule is checked against a bad example it must catch and a legitimate command it must let through.

> These rules should also go in `CLAUDE.md` (`docs/Claude.md`). That file wasn't touched: it currently has uncommitted edits of yours stashed away, and editing it would make them conflict. Copy the five rules above into it when you're ready.

## The scripts

**`common.py`** (A1) - not run directly. Finds `adb` (`--adb`, then `ANDROID_HOME`, then `PATH`, then Android Studio's default folder `%LOCALAPPDATA%\Android\Sdk\platform-tools`), chooses the target device under the rules above, runs every `adb` command with `-s <serial>`, decodes output as UTF-8 (so em dashes aren't garbled), and fails loudly on a non-zero exit. Other helpers: timestamps, "is this an emulator?", the typed confirmation, the output folders, and the guarded temp-file delete.

**`backup_db.py`** (A2) - copies the app's database and settings files to `backups/<yyyy-MM-dd_HHmmss>/` and verifies them. Read-only on the device.

```
python scripts\backup_db.py                          # the one running emulator
python scripts\backup_db.py --no-stop                # don't force-stop the app first
python scripts\backup_db.py --device R52WA025A5R --no-stop   # a phone, deliberately, without stopping the app
```

- It force-stops the app first so the database isn't changing mid-copy. **On a phone that cancels the app's scheduled alarms until you open it again**, so it asks you to type `FORCE-STOP`; use `--no-stop` to skip that (the manifest then notes the copy may have caught a file mid-write).
- Files: `databases/park_database` (+ `-wal`, `-shm`) and `files/datastore/*.preferences_pb`, found by listing the folders on the device.
- Only a **debuggable** build works (the copy uses `run-as`). The shrunk release test APK is refused with a clear message: install the debug build.
- The copy is made on the device, not through the console: the device's own shell writes each file to a temp file in `/data/local/tmp/`, the script `adb pull`s it, then deletes only that temp file. (Sending binary data through a Windows console corrupts it.)
- Checks: every pulled file's size equals its size on the device, and the main database starts with `SQLite format 3`. Writes `manifest.txt` (device, app version, last update, sizes, SHA-256) and prints one `PASS:` / `FAIL:` line. The exit code is 0 for PASS.

## Tests

```
python -m unittest discover -s scripts/tests -v
```

They need no device: `test_guard.py` (the safety guard), `test_device_selection.py` (which device gets chosen, using fake `adb devices` output), and `test_backup_db.py` (the backup logic against a fake device, including a truncated copy, a missing database, a release build, and the phone confirmation).

## Restoring a backup (manual on purpose)

There is deliberately no restore script, because restoring **writes into the app's private data**. Do it on an **emulator** first. On a phone it replaces your real parked-car data, so take a fresh backup first and only do it if you accept that.

1. Force-stop the app: `adb -s emulator-5554 shell am force-stop com.example.park`
2. Push the files to the temp folder: `adb -s emulator-5554 push backups\<folder>\databases\park_database /data/local/tmp/`
3. Delete the old `-wal` / `-shm` files inside the app folder (a stale one corrupts the restored database), then copy the pushed file in with `run-as`, e.g. `adb -s emulator-5554 shell "run-as com.example.park sh -c 'rm -f databases/park_database-wal databases/park_database-shm; cat /data/local/tmp/park_database > databases/park_database'"` - and repeat for any settings file you want back.
4. Remove the temp copy, then open the app.

Restoring a `-wal` file on its own is not enough to reconstruct a database; back up (and restore) the main file together with its `-wal`.
