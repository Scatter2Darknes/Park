"""Drive and inspect the app on a device WITHOUT tapping through the UI, using the debug-only control receiver
(app/src/debug/.../DebugControlReceiver.kt). Emulator scenarios can then run unattended: park -> check alarms ->
reboot -> check again.

    python scripts/debug_hooks.py dump                                   # log what the app thinks (read-only)
    python scripts/debug_hooks.py park --car-id 1 --lat 37.7802 --lng -122.4610
    python scripts/debug_hooks.py unpark --car-id 1
    python scripts/debug_hooks.py rearm                                  # the same re-arm the boot receiver runs
    python scripts/debug_hooks.py inject-closure --car-id 1 --kind blocked --start-in-minutes 2885
    python scripts/debug_hooks.py clear-closures
    python scripts/debug_hooks.py inject-tow --car-id 1 --start-in-minutes 2885 --duration-minutes 600 --days 1
    python scripts/debug_hooks.py inject-tow --car-id 1 --feed-age-days 60      # also pretend the city feed is stale
    python scripts/debug_hooks.py clear-tow

The receiver only exists in DEBUG builds (a release APK doesn't contain it - scripts/check_release_manifest.py proves
that). It answers by writing to Logcat under the tag ParkDebug, which this script reads back and prints.

Safety: `dump` only reads, so it may be used on a phone you name with --device. `park`, `unpark` and `rearm` CHANGE the
app's state, so they refuse to run on anything but an emulator.

Other emulator helpers: `adb emu geo fix <lon> <lat>` sets the emulator's GPS location.
"""

from __future__ import annotations

import argparse
import math
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

# INJECT_CLOSURE / INJECT_TOW extras: whole numbers go as --ei, the closure kind as a checked string.
INT_EXTRAS = ("startInMinutes", "durationMinutes", "days", "feedAgeDays")
CLOSURE_KINDS = ("blocked", "nearby")


def require_emulator(adb: Adb, what: str) -> None:
    if not is_emulator_serial(adb.serial):
        raise ScriptError(f"'{what}' changes the app's state, so it only runs on an emulator (this is {adb.serial}). "
                          "Use `dump` for a read-only look at a phone.")


def device_marker(adb: Adb) -> str:
    """The device's current time as logcat wants it ('MM-dd HH:mm:ss.000'), so replies can be read without clearing the log."""
    return adb.shell("date '+%m-%d %H:%M:%S.000'").out.strip()


def broadcast_command(name: str, **extras) -> str:
    """The `am broadcast` line for one action. carId is a long (--el). lat / lng go as STRINGS (--es): `am broadcast --ed`
    doesn't exist on Android 10 (API 29), and the receiver parses the text. A missing or non-numeric value is refused
    here, before anything is sent."""
    command = f"am broadcast -n {RECEIVER} -a {ACTION_PREFIX}{name}"
    for key, value in extras.items():
        if key == "carId":
            if isinstance(value, bool) or not isinstance(value, int):
                raise ScriptError(f"carId must be a whole number (got {value!r}).")
            command += f" --el carId {value}"
        elif key in INT_EXTRAS:
            if isinstance(value, bool) or not isinstance(value, int) or not -2**31 <= value < 2**31:
                raise ScriptError(f"{key} must be a whole number (got {value!r}).")
            command += f" --ei {key} {value}"
        elif key == "kind":
            if value not in CLOSURE_KINDS:
                raise ScriptError(f"kind must be one of {', '.join(CLOSURE_KINDS)} (got {value!r}).")
            command += f" --es kind {value}"
        else:
            try:
                degrees = float(value)
            except (TypeError, ValueError):
                degrees = math.nan
            if not math.isfinite(degrees):
                raise ScriptError(f"{key} must be a number (got {value!r}).")
            command += f" --es {key} {degrees!r}"
    return command


def send(adb: Adb, name: str, **extras) -> None:
    """Send one action to the receiver. extras: carId (long), lat / lng (degrees, sent as strings)."""
    result = adb.shell(broadcast_command(name, **extras))
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
        # The receiver answers "<ACTION> rejected: ..." when an extra is missing or isn't a number.
        rejected = [message_of(line) for line in lines if message_of(line).startswith(f"{name} rejected")]
        if rejected:
            raise ScriptError(rejected[0])
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


def inject_closure(adb: Adb, car_id: int, kind: str, start_in_minutes: int, duration_minutes: int) -> List[str]:
    """Put a fake street closure on a parked car's block (kind=blocked) or ~120 m away (kind=nearby), then re-arm it."""
    require_emulator(adb, "inject-closure")
    return call(adb, "INJECT_CLOSURE", "INJECT_CLOSURE", carId=car_id, kind=kind,
                startInMinutes=start_in_minutes, durationMinutes=duration_minutes)


def clear_closures(adb: Adb) -> List[str]:
    """Remove every fake closure INJECT_CLOSURE added, then re-arm."""
    require_emulator(adb, "clear-closures")
    return call(adb, "CLEAR_DEBUG_CLOSURES", "CLEAR_DEBUG_CLOSURES")


def inject_tow(adb: Adb, car_id: int, start_in_minutes: int, duration_minutes: int, days: int,
               feed_age_days: Optional[int] = None) -> List[str]:
    """Put a fake temporary tow zone on a parked car's block, then re-arm it. With feed_age_days, also pretend the
    city's tow feed was just synced and its newest permit is that many days old."""
    require_emulator(adb, "inject-tow")
    if not 1 <= duration_minutes < 24 * 60:
        raise ScriptError(f"duration-minutes must be 1..1439 (a daily window under 24 h), got {duration_minutes}.")
    if days < 1:
        raise ScriptError(f"days must be at least 1, got {days}.")
    extras = dict(carId=car_id, startInMinutes=start_in_minutes, durationMinutes=duration_minutes, days=days)
    if feed_age_days is not None:
        if feed_age_days < 0:
            raise ScriptError(f"feed-age-days must be 0 or more, got {feed_age_days}.")
        extras["feedAgeDays"] = feed_age_days
    return call(adb, "INJECT_TOW", "INJECT_TOW", **extras)


def clear_tow(adb: Adb) -> List[str]:
    """Remove every fake tow zone INJECT_TOW added, then re-arm."""
    require_emulator(adb, "clear-tow")
    return call(adb, "CLEAR_DEBUG_TOW", "CLEAR_DEBUG_TOW")


def reset_closure_offer(adb: Adb) -> List[str]:
    """Make the one-time 'keep checking for closures in the background?' offer show again after the next manual park."""
    require_emulator(adb, "reset-closure-offer")
    return call(adb, "RESET_CLOSURE_OFFER", "RESET_CLOSURE_OFFER")


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
    c = sub.add_parser("inject-closure", help="add a fake street closure for a parked car (emulator only)")
    c.add_argument("--car-id", type=int, default=1)
    c.add_argument("--kind", choices=CLOSURE_KINDS, default="blocked")
    c.add_argument("--start-in-minutes", type=int, default=3 * 24 * 60,
                   help="when it starts, from now (default 3 days; the alert goes out 2 days before the start)")
    c.add_argument("--duration-minutes", type=int, default=12 * 60)
    sub.add_parser("clear-closures", help="remove every fake closure and re-arm (emulator only)")
    t = sub.add_parser("inject-tow", help="add a fake temporary tow zone on a parked car's block (emulator only)")
    t.add_argument("--car-id", type=int, default=1)
    t.add_argument("--start-in-minutes", type=int, default=3 * 24 * 60,
                   help="when the first window starts, from now (default 3 days; the advance alert goes out 2 days before)")
    t.add_argument("--duration-minutes", type=int, default=10 * 60, help="length of each day's window (under 24 h)")
    t.add_argument("--days", type=int, default=1, help="how many days in a row the zone runs")
    t.add_argument("--feed-age-days", type=int, default=None,
                   help="also pretend the city's tow feed is this many days old (tests the out-of-date warning)")
    sub.add_parser("clear-tow", help="remove every fake tow zone and re-arm (emulator only)")
    sub.add_parser("reset-closure-offer", help="let the one-time background closure offer show again (emulator only)")
    args = parser.parse_args(argv)

    try:
        adb = connect(args.adb, args.device)
        if args.command == "dump":
            lines = dump_state(adb)
        elif args.command == "park":
            lines = park(adb, args.car_id, args.lat, args.lng)
        elif args.command == "unpark":
            lines = unpark(adb, args.car_id)
        elif args.command == "inject-closure":
            lines = inject_closure(adb, args.car_id, args.kind, args.start_in_minutes, args.duration_minutes)
        elif args.command == "clear-closures":
            lines = clear_closures(adb)
        elif args.command == "inject-tow":
            lines = inject_tow(adb, args.car_id, args.start_in_minutes, args.duration_minutes, args.days, args.feed_age_days)
        elif args.command == "clear-tow":
            lines = clear_tow(adb)
        elif args.command == "reset-closure-offer":
            lines = reset_closure_offer(adb)
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
