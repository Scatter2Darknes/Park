"""Drive and inspect the app on a device WITHOUT tapping through the UI, using the debug-only control receiver
(app/src/debug/.../DebugControlReceiver.kt). Emulator scenarios can then run unattended: park -> check alarms ->
reboot -> check again.

    python scripts/debug_hooks.py dump                                   # log what the app thinks (read-only)
    python scripts/debug_hooks.py park --car-id 1 --lat 37.7802 --lng -122.4610
    python scripts/debug_hooks.py unpark --car-id 1
    python scripts/debug_hooks.py rearm                                  # the same re-arm the boot receiver runs

The receiver only exists in DEBUG builds (a release APK doesn't contain it - scripts/check_release_manifest.py proves
that). It answers by writing to Logcat under the tag ParkDebug, which this script reads back and prints.

Safety: `dump` only reads, so it may be used on a phone you name with --device. `park`, `unpark` and `rearm` CHANGE the
app's state, so they refuse to run on anything but an emulator.

Other emulator helpers: `adb emu geo fix <lon> <lat>` sets the emulator's GPS location.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from typing import List, Optional

from common import PACKAGE, Adb, ScriptError, connect, is_emulator_serial, setup_console

RECEIVER = f"{PACKAGE}/.DebugControlReceiver"
ACTION_PREFIX = "com.example.park.debug."
TAG = "ParkDebug"
REPLY_TIMEOUT = 12.0

# A point on 3rd Avenue (Richmond District, San Francisco) that has a street-sweeping segment, taken from the DataSF data.
DEFAULT_LAT, DEFAULT_LNG = 37.7802, -122.4610


def require_emulator(adb: Adb, what: str) -> None:
    if not is_emulator_serial(adb.serial):
        raise ScriptError(f"'{what}' changes the app's state, so it only runs on an emulator (this is {adb.serial}). "
                          "Use `dump` for a read-only look at a phone.")


def device_marker(adb: Adb) -> str:
    """The device's current time as logcat wants it ('MM-dd HH:mm:ss.000'), so replies can be read without clearing the log."""
    return adb.shell("date '+%m-%d %H:%M:%S.000'").out.strip()


def send(adb: Adb, name: str, **extras) -> None:
    """Send one action to the receiver. extras: carId (long), lat / lng (double)."""
    command = f"am broadcast -n {RECEIVER} -a {ACTION_PREFIX}{name}"
    for key, value in extras.items():
        flag = "--el" if key == "carId" else "--ed"
        command += f" {flag} {key} {value}"
    result = adb.shell(command)
    if "Broadcast completed" not in result.out:
        raise ScriptError(f"The broadcast wasn't delivered: {(result.out or result.err).strip()}")


def read_replies(adb: Adb, since: str) -> List[str]:
    out = adb.run("logcat", "-d", "-v", "threadtime", "-t", since, "-s", f"{TAG}:V", allow_fail=True).out
    return [line for line in out.splitlines() if re.search(rf"\b{TAG}\s*:", line)]


def call(adb: Adb, name: str, done_prefix: str, **extras) -> List[str]:
    """Send an action and wait for its reply in Logcat (a ParkDebug message starting with `done_prefix`); return every
    ParkDebug line since the call. Matching the START of the message keeps 'PARK' from matching an 'UNPARK' line."""
    since = device_marker(adb)
    send(adb, name, **extras)
    deadline = time.monotonic() + REPLY_TIMEOUT
    while time.monotonic() < deadline:
        lines = read_replies(adb, since)
        if any(message_of(line).startswith(done_prefix) for line in lines):
            return lines
        time.sleep(0.5)
    raise ScriptError(f"No reply from the debug receiver within {REPLY_TIMEOUT:.0f}s. Is the DEBUG build installed "
                      "(a release build has no debug receiver) and has the app been opened once?")


def dump_state(adb: Adb) -> List[str]:
    return call(adb, "DUMP_STATE", "DUMP_STATE end")


def park(adb: Adb, car_id: int, lat: float, lng: float) -> List[str]:
    require_emulator(adb, "park")
    lines = call(adb, "PARK", "PARK", carId=car_id, lat=lat, lng=lng)
    if any("no street segment" in line for line in lines):
        raise ScriptError("PARK found no street segment near that point (nothing was saved).")
    return lines


def unpark(adb: Adb, car_id: int) -> List[str]:
    require_emulator(adb, "unpark")
    return call(adb, "UNPARK", "UNPARK", carId=car_id)


def rearm(adb: Adb) -> List[str]:
    require_emulator(adb, "rearm")
    return call(adb, "REARM", "REARM")


def message_of(line: str) -> str:
    """The text after 'ParkDebug:' in a threadtime line."""
    return line.split(f"{TAG}:", 1)[1].strip() if f"{TAG}:" in line else line


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Drive the debug-only control receiver (emulator).")
    parser.add_argument("--device", "-d", help="serial of the device (default: the one running emulator)")
    parser.add_argument("--adb", help="path to adb.exe")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("dump", help="log what the app thinks about its parked cars and alarms (read-only)")
    p = sub.add_parser("park", help="park a car at a point via the normal saveParkedState path (emulator only)")
    p.add_argument("--car-id", type=int, default=1)
    p.add_argument("--lat", type=float, default=DEFAULT_LAT)
    p.add_argument("--lng", type=float, default=DEFAULT_LNG)
    u = sub.add_parser("unpark", help="clear a car's parked state and reminders (emulator only)")
    u.add_argument("--car-id", type=int, default=1)
    sub.add_parser("rearm", help="run the re-arm the boot receiver runs (emulator only)")
    args = parser.parse_args(argv)

    try:
        adb = connect(args.adb, args.device)
        if args.command == "dump":
            lines = dump_state(adb)
        elif args.command == "park":
            lines = park(adb, args.car_id, args.lat, args.lng)
        elif args.command == "unpark":
            lines = unpark(adb, args.car_id)
        else:
            lines = rearm(adb)
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    for line in lines:
        print(message_of(line))
    return 0


if __name__ == "__main__":
    sys.exit(main())
