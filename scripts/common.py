"""Shared helpers for the Park automation scripts (see scripts/README.md).

Everything here is standard-library only, so nothing needs installing beyond Python 3.

The important idea: every script talks to a device through the `Adb` class below, which
  * always passes `-s <serial>` (so a command can never land on "whichever device happens to be
    first"), and
  * is only ever created by `connect()`, which applies the safety rules: by default the target
    is the ONE running emulator, and a physical phone is only used if you name it explicitly.

These scripts may only READ the app's data on a device: they must never remove the app, wipe its data, or
write into its data folders. See the safety rules in scripts/README.md and the guard test that enforces them.
"""

from __future__ import annotations

import datetime as _dt
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional

PACKAGE = "com.example.park"
TOOL_VERSION = "park-scripts 1.0"

# Only files whose name starts with this prefix may ever be deleted on the device, and only under
# /data/local/tmp/. It is the one deletion the safety rules allow: the script's own temp files.
DEVICE_TMP_DIR = "/data/local/tmp"
DEVICE_TMP_PREFIX = "park-backup-"


class ScriptError(Exception):
    """A problem worth telling the user about (as opposed to a bug): printed without a traceback."""


def setup_console() -> None:
    """Make printing UTF-8 so log text such as an em dash isn't garbled on a Windows console."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except (AttributeError, ValueError):
            pass


def repo_root() -> Path:
    """The repository root (scripts/ lives directly inside it)."""
    return Path(__file__).resolve().parent.parent


def timestamp() -> str:
    """A sortable, filename-safe timestamp such as 2026-09-20_181500."""
    return _dt.datetime.now().strftime("%Y-%m-%d_%H%M%S")


def output_folder(kind: str, stamp: Optional[str] = None, base: Optional[Path] = None) -> Path:
    """Create and return <repo>/<kind>/<timestamp>/ (kind is "backups" or "logs"; both are gitignored)."""
    folder = (base or repo_root() / kind) / (stamp or timestamp())
    folder.mkdir(parents=True, exist_ok=False)
    return folder


# ---------------------------------------------------------------------------------------------
# Finding adb
# ---------------------------------------------------------------------------------------------

def resolve_adb(adb_arg: Optional[str] = None, env: Optional[dict] = None) -> str:
    """Find adb.exe: the --adb argument, then ANDROID_HOME, ANDROID_SDK_ROOT, PATH, then Android Studio's
    default install location. A path you pass explicitly must exist (it is never silently ignored)."""
    env = os.environ if env is None else env
    if adb_arg:
        if Path(adb_arg).is_file():
            return str(Path(adb_arg))
        raise ScriptError(f"--adb was given but that file doesn't exist: {adb_arg}")

    candidates: List[str] = []
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if env.get(var):
            candidates.append(str(Path(env[var]) / "platform-tools" / "adb.exe"))
            candidates.append(str(Path(env[var]) / "platform-tools" / "adb"))
    on_path = shutil.which("adb")
    if on_path:
        candidates.append(on_path)
    if env.get("LOCALAPPDATA"):
        candidates.append(str(Path(env["LOCALAPPDATA"]) / "Android" / "Sdk" / "platform-tools" / "adb.exe"))

    for candidate in candidates:
        if Path(candidate).is_file():
            return candidate
    raise ScriptError(
        "Couldn't find adb. Pass --adb <path to adb.exe>, set the ANDROID_HOME environment variable to your "
        "Android SDK folder, or add its platform-tools folder to PATH. "
        r"Typical location: C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )


# ---------------------------------------------------------------------------------------------
# Choosing the device (safety rule 1) - pure functions, unit-tested in tests/test_device_selection.py
# ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Device:
    serial: str
    state: str  # "device" (usable), "offline", "unauthorized", ...


def is_emulator_serial(serial: str) -> bool:
    """Emulators show up as emulator-5554, emulator-5556, ..."""
    return serial.startswith("emulator-")


def parse_adb_devices(text: str) -> List[Device]:
    """Parse the output of `adb devices`: a header line, then `<serial>\\t<state>` per device."""
    devices: List[Device] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue  # header, blank, or adb's "* daemon started" chatter
        parts = line.split()
        if len(parts) >= 2:
            devices.append(Device(parts[0], parts[1]))
    return devices


def _describe(devices: Iterable[Device]) -> str:
    rows = [f"    {d.serial}  ({'emulator' if is_emulator_serial(d.serial) else 'physical'}, {d.state})" for d in devices]
    return "\n".join(rows) if rows else "    (none)"


def select_target(devices: List[Device], requested: Optional[str] = None) -> str:
    """Decide which device a script may use, or raise ScriptError explaining why not.

    * A serial given explicitly (--device) must be attached and usable - then it is used, emulator or phone.
    * With no serial: use the one running emulator. A physical phone is NEVER picked implicitly, even if it is
      the only device attached, and several emulators (or none) is refused rather than guessed at.
    """
    usable = [d for d in devices if d.state == "device"]
    if requested:
        if any(d.serial == requested for d in usable):
            return requested
        raise ScriptError(
            f"Device '{requested}' isn't attached and ready. Attached devices:\n{_describe(devices)}"
        )
    if not usable:
        raise ScriptError(f"No usable device attached. Start an emulator, or plug in a phone and pass --device <serial>.\n"
                          f"Attached devices:\n{_describe(devices)}")
    emulators = [d for d in usable if is_emulator_serial(d.serial)]
    if len(emulators) == 1:
        return emulators[0].serial
    if len(emulators) > 1:
        raise ScriptError("More than one emulator is running - say which one with --device <serial>.\n"
                          f"Attached devices:\n{_describe(devices)}")
    raise ScriptError("Only physical devices are attached. Scripts never pick a phone on their own: "
                      "pass --device <serial> to use it deliberately.\n"
                      f"Attached devices:\n{_describe(devices)}")


# ---------------------------------------------------------------------------------------------
# Running adb
# ---------------------------------------------------------------------------------------------

@dataclass
class Result:
    returncode: int
    out: str
    err: str


def run_process(args: List[str], timeout: float = 120) -> Result:
    """Run a program and capture its text output as UTF-8 (never through a shell, so no quoting surprises)."""
    try:
        proc = subprocess.run(
            args, capture_output=True, timeout=timeout,
            encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired as exc:
        raise ScriptError(f"Timed out after {timeout:.0f}s: {' '.join(args[:4])} ...") from exc
    except FileNotFoundError as exc:
        raise ScriptError(f"Couldn't run {args[0]}: {exc}") from exc
    return Result(proc.returncode, proc.stdout or "", proc.stderr or "")


class Adb:
    """adb pinned to one device. `run(...)` always adds `-s <serial>`, and fails loudly on a non-zero exit."""

    def __init__(self, adb_path: str, serial: str):
        self.adb_path = adb_path
        self.serial = serial
        self._is_emulator: Optional[bool] = None

    def run(self, *args: str, allow_fail: bool = False, timeout: float = 120) -> Result:
        result = run_process([self.adb_path, "-s", self.serial, *args], timeout=timeout)
        if result.returncode != 0 and not allow_fail:
            detail = (result.err or result.out).strip()
            raise ScriptError(f"adb {' '.join(args[:3])} failed (exit {result.returncode}): {detail}")
        return result

    def shell(self, command: str, allow_fail: bool = False, timeout: float = 120) -> Result:
        """Run one command string on the device (the device's own shell interprets it)."""
        return self.run("shell", command, allow_fail=allow_fail, timeout=timeout)

    def getprop(self, name: str) -> str:
        return self.shell(f"getprop {name}", allow_fail=True).out.strip()

    @property
    def is_emulator(self) -> bool:
        if self._is_emulator is None:
            self._is_emulator = is_emulator_serial(self.serial) or self.getprop("ro.kernel.qemu") == "1"
        return self._is_emulator


def connect(adb_arg: Optional[str] = None, device_arg: Optional[str] = None) -> Adb:
    """Resolve adb and the target device under the safety rules and return an Adb bound to it."""
    adb_path = resolve_adb(adb_arg)
    listing = run_process([adb_path, "devices"], timeout=30)
    if listing.returncode != 0:
        raise ScriptError(f"`adb devices` failed: {(listing.err or listing.out).strip()}")
    serial = select_target(parse_adb_devices(listing.out), device_arg)
    adb = Adb(adb_path, serial)
    print(f"Target: {serial} ({'emulator' if adb.is_emulator else 'PHYSICAL PHONE'})")
    return adb


# ---------------------------------------------------------------------------------------------
# Small safety helpers
# ---------------------------------------------------------------------------------------------

def confirm(message: str, phrase: str) -> None:
    """Ask the person to type `phrase` exactly. Refuses outright when there is no one to ask (piped input, CI)."""
    if not sys.stdin or not sys.stdin.isatty():
        raise ScriptError(f"Confirmation needed but this isn't an interactive terminal: {message}")
    print(message)
    try:
        answer = input(f'Type "{phrase}" to continue (anything else cancels): ').strip()
    except EOFError:  # a "terminal" with nobody typing (some launchers do this): treat as a refusal, never as consent
        raise ScriptError(f"Confirmation needed but no one answered: {message}") from None
    if answer != phrase:
        raise ScriptError("Cancelled.")


_TMP_NAME = re.compile(rf"^{re.escape(DEVICE_TMP_PREFIX)}[A-Za-z0-9._-]+$")


def remove_device_temp(adb: Adb, name: str) -> None:
    """Delete one of the script's OWN temp files from /data/local/tmp/ on the device. Refuses anything else, so a
    coding slip can't turn into a delete somewhere that matters."""
    if not _TMP_NAME.match(name):
        raise ScriptError(f"Refusing to delete '{name}': not one of this tool's temp files.")
    adb.shell(f"rm -f /data/local/tmp/{name}", allow_fail=True)
