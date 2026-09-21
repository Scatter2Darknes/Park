"""Back up Park's database and settings files from a device to this computer - READ ONLY on the device.

    python scripts/backup_db.py                       # the one running emulator
    python scripts/backup_db.py --device R52WA025A5R  # a physical phone, named on purpose
    python scripts/backup_db.py --no-stop             # don't force-stop the app first

What it copies (all inside the app's private folder, reached with `run-as`, which only works for a
DEBUGGABLE build - the shrunk release test APK will be refused with a clear message):
    databases/park_database  (+ -wal and -shm)      your cars, parked state, street data
    files/datastore/*.preferences_pb                your settings and the widget's state

How the copy works, and why: `adb shell run-as <app> cat <file>` would send the file through the
Windows console and corrupt binary data, so the copy is done ON the device instead: the device's
own shell writes the file into /data/local/tmp/ (a temp folder anyone may write), we `adb pull` it
from there, and then delete only that temp file. Nothing inside the app's folder is ever changed.

Each pulled file is checked: its size must equal the size on the device, and the main database must
start with the SQLite header. A manifest.txt is written next to the files. There is deliberately no
restore script - restoring writes into app data, so it stays a manual, documented procedure (README).

The backup folder (backups/) is gitignored: it contains your parking locations and car names.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

from common import (
    DEVICE_TMP_DIR, DEVICE_TMP_PREFIX, PACKAGE, TOOL_VERSION, Adb, ScriptError, confirm, connect,
    output_folder, remove_device_temp, repo_root, setup_console, timestamp,
)

MAIN_DATABASE = "databases/park_database"
BACKUP_ROOTS = ("databases", "files/datastore")
SQLITE_MAGIC = b"SQLite format 3\x00"
# Seconds to wait after a force-stop before checking the app is really down. Android can restart the app
# almost at once (WorkManager's job service did, 0.2 s later, in testing), so this is checked, not assumed.
# Tests set it to 0.
SETTLE_SECONDS = 0.6
STOP_ATTEMPTS = 4
_SAFE_PATH = re.compile(r"^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")


# ---------------------------------------------------------------------------------------------
# Pure helpers (unit-tested in tests/test_backup_db.py)
# ---------------------------------------------------------------------------------------------

def parse_file_list(text: str) -> List[str]:
    """Turn `find` output into app-relative file paths, keeping only plain, safe-looking ones.

    The paths are later placed into a command line for the device's shell, so anything with spaces,
    quotes, `..`, or a leading slash is dropped rather than trusted."""
    files: List[str] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("find:") or ".." in line.split("/"):
            continue
        if _SAFE_PATH.match(line) and any(line.startswith(root + "/") for root in BACKUP_ROOTS):
            files.append(line)
    return sorted(set(files))


def parse_size(text: str) -> Optional[int]:
    """Read a byte count from `stat -c %s` output (just a number) or from an `ls -l` line (5th column)."""
    text = text.strip()
    if re.fullmatch(r"\d+", text):
        return int(text)
    parts = text.split()
    if len(parts) >= 5 and parts[0][:1] in "-l" and parts[4].isdigit():
        return int(parts[4])
    return None


def has_sqlite_header(path: Path) -> bool:
    """True if the file starts with 'SQLite format 3' - the first 16 bytes of every SQLite database."""
    try:
        with open(path, "rb") as handle:
            return handle.read(len(SQLITE_MAGIC)) == SQLITE_MAGIC
    except OSError:
        return False


def parse_package_info(dumpsys_text: str) -> Dict[str, str]:
    """Pull versionName / versionCode / lastUpdateTime out of `dumpsys package <name>` output."""
    info: Dict[str, str] = {}
    for key in ("versionName", "versionCode", "lastUpdateTime", "firstInstallTime"):
        match = re.search(rf"\b{key}=(\S+(?: \S+)?)", dumpsys_text)
        if match:
            value = match.group(1)
            if key == "versionCode":
                value = value.split()[0]
            info[key] = value
    if re.search(r"\bDEBUGGABLE\b", dumpsys_text):
        info["debuggable"] = "yes"
    return info


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass
class FileRecord:
    path: str                       # e.g. databases/park_database
    device_size: Optional[int] = None
    local_size: Optional[int] = None
    sha256: str = ""
    verified_unchanged: Optional[bool] = None   # True/False after re-hashing on the device; None = couldn't check
    problems: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems


def _verification_summary(records: List[FileRecord]) -> str:
    if any(r.verified_unchanged is False for r in records):
        return "NO - at least one file changed during the copy"
    if records and all(r.verified_unchanged is True for r in records):
        return "yes, every file's SHA-256 on the device still matches the copy"
    return "not possible on this device (no sha256sum) - sizes and header only"


def build_manifest(*, serial: str, is_emulator: bool, stamp: str, package_info: Dict[str, str],
                   records: List[FileRecord], app_was_stopped: bool, overall_ok: bool) -> str:
    lines = [
        "Park database backup",
        f"result:           {'PASS' if overall_ok else 'FAIL'}",
        f"created:          {stamp}",
        f"tool:             {TOOL_VERSION}",
        f"device serial:    {serial} ({'emulator' if is_emulator else 'physical phone'})",
        f"package:          {PACKAGE}",
        f"versionName:      {package_info.get('versionName', 'unknown')}",
        f"versionCode:      {package_info.get('versionCode', 'unknown')}",
        f"lastUpdateTime:   {package_info.get('lastUpdateTime', 'unknown')}",
        f"app stopped first: {'yes (confirmed not running)' if app_was_stopped else 'NO (--no-stop, or it kept restarting): files may have been changing while copied'}",
        "content re-checked on the device after copying: " + _verification_summary(records),
        "",
        "files (device size / local size / sha256):",
    ]
    for record in records:
        status = "ok" if record.ok else "PROBLEM: " + "; ".join(record.problems)
        lines.append(f"  {record.path}: {record.device_size} / {record.local_size} bytes  {record.sha256[:16]}  [{status}]")
    lines += [
        "",
        "Contains parking locations and car names. Kept out of git (backups/ is gitignored) - don't share it.",
        "To restore, see 'Restoring a backup' in scripts/README.md (a manual, emulator-first procedure).",
    ]
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------
# The backup itself
# ---------------------------------------------------------------------------------------------

def _run_as(adb: Adb, command: str, allow_fail: bool = False):
    return adb.shell(f"run-as {PACKAGE} {command}", allow_fail=allow_fail)


def check_app_is_debuggable(adb: Adb) -> None:
    listed = adb.shell(f"pm list packages {PACKAGE}", allow_fail=True).out
    if f"package:{PACKAGE}" not in listed.split():
        raise ScriptError(f"{PACKAGE} isn't installed on {adb.serial}.")
    probe = _run_as(adb, "id", allow_fail=True)
    if probe.returncode != 0:
        detail = (probe.err or probe.out).strip()
        raise ScriptError(
            "Can't read the app's private files: `run-as` was refused, which is what happens with a release "
            "(non-debuggable) build such as the shrunk test APK. Install the debug build and try again.\n"
            f"    adb said: {detail}"
        )


def device_file_size(adb: Adb, rel_path: str) -> Optional[int]:
    result = _run_as(adb, f"stat -c %s {rel_path}", allow_fail=True)
    size = parse_size(result.out) if result.returncode == 0 else None
    if size is None:  # older/other toolboxes without `stat -c`: fall back to reading the `ls -l` line
        result = _run_as(adb, f"ls -l {rel_path}", allow_fail=True)
        size = parse_size(result.out) if result.returncode == 0 else None
    return size


def backup_one_file(adb: Adb, rel_path: str, dest_root: Path, index: int) -> FileRecord:
    record = FileRecord(rel_path)
    record.device_size = device_file_size(adb, rel_path)
    if record.device_size is None:
        record.problems.append("couldn't read its size on the device")
        return record

    temp_name = f"{DEVICE_TMP_PREFIX}{uuid.uuid4().hex[:8]}-{index}.bin"
    local = dest_root / rel_path
    local.parent.mkdir(parents=True, exist_ok=True)
    try:
        # The `>` below is interpreted by the DEVICE's shell, so the bytes go straight from the app's
        # file into a temp file on the device without ever passing through this computer's console.
        adb.shell(f"run-as {PACKAGE} cat {rel_path} > {DEVICE_TMP_DIR}/{temp_name}")
        adb.run("pull", f"{DEVICE_TMP_DIR}/{temp_name}", str(local))
    except ScriptError as exc:
        record.problems.append(str(exc))
        return record
    finally:
        remove_device_temp(adb, temp_name)  # the one deletion allowed: our own temp file

    record.local_size = local.stat().st_size
    record.sha256 = sha256_of(local)
    if record.local_size != record.device_size:
        record.problems.append(f"size mismatch (device {record.device_size} vs pulled {record.local_size})")
    if rel_path == MAIN_DATABASE and not has_sqlite_header(local):
        record.problems.append("doesn't start with the 'SQLite format 3' header")
    return record


def stop_app(adb: Adb) -> bool:
    """Force-stop the app and confirm it is actually down, retrying: Android may restart it straight away."""
    for attempt in range(STOP_ATTEMPTS):
        adb.shell(f"am force-stop {PACKAGE}")
        if SETTLE_SECONDS:
            time.sleep(SETTLE_SECONDS)
        if not adb.shell(f"pidof {PACKAGE}", allow_fail=True).out.strip():
            return True
    return False


def device_sha256(adb: Adb, rel_path: str) -> Optional[str]:
    """SHA-256 of the app's file as it is on the device RIGHT NOW (None if the device has no sha256sum)."""
    result = _run_as(adb, f"sha256sum {rel_path}", allow_fail=True)
    match = re.match(r"^([0-9a-f]{64})", result.out.strip()) if result.returncode == 0 else None
    return match.group(1) if match else None


def verify_unchanged(adb: Adb, records: List[FileRecord]) -> None:
    """After copying, re-hash every file on the device and compare with what was pulled.

    Sizes alone can't catch a file that changed in place, and a restarted app can rewrite the database
    (or its -wal file) while the copy is running. If the device's hash now differs from the pulled copy's,
    the copy is not a faithful snapshot, so that file (and therefore the backup) is marked as a problem."""
    for record in records:
        if not record.sha256:
            continue
        now = device_sha256(adb, record.path)
        if now is None:
            record.verified_unchanged = None
        elif now == record.sha256:
            record.verified_unchanged = True
        else:
            record.verified_unchanged = False
            record.problems.append("changed on the device while it was being copied (is the app running? try again)")


def run_backup(adb: Adb, out_base: Optional[Path], no_stop: bool) -> bool:
    check_app_is_debuggable(adb)
    package_info = parse_package_info(adb.shell(f"dumpsys package {PACKAGE}", allow_fail=True, timeout=60).out)

    stopped = False
    if not no_stop:
        if not adb.is_emulator:
            confirm(
                f"Force-stopping {PACKAGE} on the PHONE {adb.serial} also cancels the app's scheduled reminder alarms "
                "until you open the app again (Android does that on a force-stop).\n"
                "Use --no-stop to copy without stopping it (the copy may then catch a file mid-write).",
                "FORCE-STOP",
            )
        stopped = stop_app(adb)
        if not stopped:
            print(f"  warning: {PACKAGE} kept restarting after force-stop; the copy is verified afterwards, "
                  "and marked FAIL if anything changed underneath it")

    listing = _run_as(adb, "find " + " ".join(BACKUP_ROOTS) + " -type f", allow_fail=True)
    files = parse_file_list(listing.out)
    if MAIN_DATABASE not in files:
        raise ScriptError(f"{MAIN_DATABASE} not found on the device (has the app been opened at least once?). "
                          f"Found: {files or 'nothing'}")

    stamp = timestamp()
    folder = output_folder("backups", stamp, out_base)
    print(f"Backing up {len(files)} file(s) to {folder}")
    records = []
    for index, rel_path in enumerate(files):
        record = backup_one_file(adb, rel_path, folder, index)
        print(f"  {'ok  ' if record.ok else 'FAIL'} {rel_path}  ({record.device_size} bytes)"
              + ("" if record.ok else "  <- " + "; ".join(record.problems)))
        records.append(record)

    verify_unchanged(adb, records)
    for record in records:
        if record.problems and record.local_size is not None:
            print(f"  FAIL {record.path}  <- " + "; ".join(record.problems))

    overall_ok = all(r.ok for r in records)
    (folder / "manifest.txt").write_text(
        build_manifest(serial=adb.serial, is_emulator=adb.is_emulator, stamp=stamp, package_info=package_info,
                       records=records, app_was_stopped=stopped, overall_ok=overall_ok),
        encoding="utf-8",
    )
    total = sum(r.local_size or 0 for r in records)
    if overall_ok:
        print(f"PASS: backed up {len(records)} file(s), {total} bytes, to {folder}")
    else:
        bad = [r.path for r in records if not r.ok]
        print(f"FAIL: {len(bad)} of {len(records)} file(s) had problems ({', '.join(bad)}); see {folder / 'manifest.txt'}")
    return overall_ok


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Back up Park's database and settings (read-only on the device).")
    parser.add_argument("--device", "-d", help="serial of the device to use (default: the one running emulator)")
    parser.add_argument("--adb", help="path to adb.exe (default: ANDROID_HOME, PATH, then Android Studio's location)")
    parser.add_argument("--no-stop", action="store_true", help="don't force-stop the app before copying")
    parser.add_argument("--out-dir", type=Path, help="folder to create the timestamped backup in (default: <repo>/backups)")
    args = parser.parse_args(argv)
    try:
        adb = connect(args.adb, args.device)
        return 0 if run_backup(adb, args.out_dir, args.no_stop) else 1
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
