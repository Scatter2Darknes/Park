"""Check that Park re-arms its reminder alarms after they are wiped - the two manual checks, automated.

    python scripts/rearm_check.py --mode foreground   # force-stop, then open the app: alarms should come back
    python scripts/rearm_check.py --mode boot         # reboot the device: alarms should come back after boot

Both need a parked car with a FUTURE sweep first (otherwise there is nothing to re-arm). If there are no live
alarms the script stops with "park a car with a future sweep first" - that is a precondition problem, not a test
failure - and exits with code 2. PASS exits 0, FAIL exits 1.

What each mode does:
  foreground  records the live alarms; force-stops the app (Android cancels a stopped app's alarms) and confirms
              they are gone; opens the app; polls every 2 s (up to 30 s) until the SAME alarms are back.
              This exercises the "app came to the foreground" re-arm in ParkApp.
  boot        records the alarms; reboots and waits for boot to finish (on a phone it asks you to unlock it - a lock
              screen can't be automated); polls (up to 90 s) until the same alarms are back. This exercises
              BootReceiver. It also reports whether Logcat shows "BootReceiver: BOOT_COMPLETED" (informational) and,
              where the alarm history has set-times, whether the alarms were set after the reboot.
"Same alarms" means the same receiver and due time; a reminder that fell back to an inexact alarm still counts.

On a PHYSICAL phone this changes the phone's state (a force-stop cancels alarms until reopened; boot mode reboots it),
so it needs --device <serial> AND typing a confirmation phrase, after printing exactly what it is about to do.
"""

from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass
from typing import Callable, List, Optional, Set, Tuple

from common import PACKAGE, Adb, ScriptError, confirm, connect, is_emulator_serial, setup_console
import alarms as alarm_tools

AlarmKey = Tuple[str, int]

POLL_INTERVAL = 2.0
FOREGROUND_TIMEOUT = 30.0
BOOT_ALARM_TIMEOUT = 90.0
BOOT_COMPLETED_TIMEOUT = 240.0
EXIT_PASS, EXIT_FAIL, EXIT_PRECONDITION = 0, 1, 2

# Replaced in tests so nothing really waits.
sleep: Callable[[float], None] = time.sleep
monotonic: Callable[[], float] = time.monotonic


class PreconditionFailed(ScriptError):
    """The check can't run yet (e.g. no car is parked). Reported as exit code 2, not as a test failure."""


@dataclass
class Outcome:
    passed: bool
    missing: List[AlarmKey]
    extra: List[AlarmKey]
    notes: List[str]


# ---------------------------------------------------------------------------------------------
# Pure helpers (unit-tested)
# ---------------------------------------------------------------------------------------------

def keys_of(result: alarm_tools.ParseResult) -> Set[AlarmKey]:
    return {alarm.key for alarm in result.alarms}


def diff_keys(before: Set[AlarmKey], after: Set[AlarmKey]) -> Tuple[List[AlarmKey], List[AlarmKey]]:
    """(missing, extra): alarms that were there before but aren't now, and ones that are new."""
    return sorted(before - after), sorted(after - before)


def describe_key(key: AlarmKey) -> str:
    receiver, when_ms = key
    return f"{receiver} due {alarm_tools.format_time(when_ms, alarm_tools.SF_TZ_NAME)} PT"


def poll_until(predicate: Callable[[], bool], timeout: float, interval: float = POLL_INTERVAL) -> bool:
    """Call `predicate` every `interval` seconds until it is true or `timeout` seconds have passed."""
    deadline = monotonic() + timeout
    while True:
        if predicate():
            return True
        if monotonic() >= deadline:
            return False
        sleep(interval)


def set_after(history: List[alarm_tools.HistoryEntry], reboot_marker: str) -> Optional[bool]:
    """Were the alarms SET after the reboot? Compares history `rtc=` times (device-local text such as
    '2026-09-20 18:11:18.936') with the device clock read just before rebooting. None when the dump has no such times."""
    times = [entry.rtc for entry in history if entry.rtc]
    if not times:
        return None
    return max(times) > reboot_marker


# ---------------------------------------------------------------------------------------------
# Talking to the device
# ---------------------------------------------------------------------------------------------

def live_keys(adb: Adb) -> Set[AlarmKey]:
    return keys_of(alarm_tools.fetch_alarms(adb))


def require_alarms(adb: Adb) -> Set[AlarmKey]:
    keys = live_keys(adb)
    if not keys:
        raise PreconditionFailed("No live alarms for the app: park a car with a future sweep first (nothing to re-arm).")
    print(f"Before: {len(keys)} live alarm(s):")
    for key in sorted(keys, key=lambda k: k[1]):
        print(f"   {describe_key(key)}")
    return keys


def force_stop_and_confirm_cleared(adb: Adb) -> None:
    adb.shell(f"am force-stop {PACKAGE}")
    if not poll_until(lambda: not live_keys(adb), timeout=10, interval=1):
        raise PreconditionFailed("The alarms were still there after a force-stop, so this check would prove nothing. "
                                 "(Is this a device that keeps alarms across a force-stop?)")
    print("Force-stopped: the alarms are gone, as expected.")


def check_foreground(adb: Adb, before: Set[AlarmKey]) -> Outcome:
    force_stop_and_confirm_cleared(adb)
    print("Opening the app...")
    adb.shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1", allow_fail=True)
    matched = poll_until(lambda: live_keys(adb) == before, FOREGROUND_TIMEOUT)
    after = live_keys(adb)
    missing, extra = diff_keys(before, after)
    return Outcome(matched, missing, extra, [])


def wait_for_boot(adb: Adb) -> None:
    adb.run("wait-for-device", timeout=BOOT_COMPLETED_TIMEOUT)

    def booted() -> bool:
        try:
            return adb.getprop("sys.boot_completed") == "1"
        except ScriptError:
            return False
    if not poll_until(booted, BOOT_COMPLETED_TIMEOUT, interval=3):
        raise ScriptError("The device didn't finish booting in time.")


def wait_for_enter(message: str) -> None:
    if not sys.stdin or not sys.stdin.isatty():
        raise ScriptError(f"Needs a person at the keyboard: {message}")
    try:
        input(message + " Press Enter when done...")
    except EOFError:
        raise ScriptError("No one answered; stopping.") from None


def check_boot(adb: Adb, before: Set[AlarmKey]) -> Outcome:
    marker = adb.shell("date '+%Y-%m-%d %H:%M:%S'", allow_fail=True).out.strip()
    print("Rebooting the device...")
    adb.run("reboot", allow_fail=True)
    sleep(5)
    wait_for_boot(adb)
    print("Boot finished.")
    if not adb.is_emulator:
        wait_for_enter("Unlock the phone.")
    matched = poll_until(lambda: live_keys(adb) == before, BOOT_ALARM_TIMEOUT)
    result = alarm_tools.fetch_alarms(adb)
    missing, extra = diff_keys(before, keys_of(result))

    notes: List[str] = []
    boot_log = adb.run("logcat", "-d", "-s", "Park:V", allow_fail=True).out
    if "BootReceiver: BOOT_COMPLETED" in boot_log:
        notes.append("Logcat shows 'BootReceiver: BOOT_COMPLETED' (the boot receiver ran).")
    else:
        notes.append("Logcat does NOT show 'BootReceiver: BOOT_COMPLETED' (the log buffer may have been cleared, "
                     "or another path re-armed the alarms).")
    after_boot = set_after(result.history, marker) if marker else None
    if after_boot is True:
        notes.append("The alarm history says the alarms were set AFTER the reboot.")
    elif after_boot is False:
        notes.append("The alarm history says the newest set-time is BEFORE the reboot - the alarms may have survived "
                     "rather than being re-armed.")
        matched = False
    return Outcome(matched, missing, extra, notes)


def report(outcome: Outcome, mode: str) -> int:
    for note in outcome.notes:
        print(f"  note: {note}")
    if outcome.passed:
        print(f"PASS: after the {mode}, the same alarms are back.")
        return EXIT_PASS
    parts = []
    if outcome.missing:
        parts.append("missing: " + "; ".join(describe_key(k) for k in outcome.missing))
    if outcome.extra:
        parts.append("unexpected extra: " + "; ".join(describe_key(k) for k in outcome.extra))
    print(f"FAIL: after the {mode} the alarms did not come back as before. " + (" | ".join(parts) or "(timing or history check failed)"))
    return EXIT_FAIL


def describe_plan(adb: Adb, mode: str) -> str:
    if mode == "foreground":
        return (f"This will FORCE-STOP {PACKAGE} on the phone {adb.serial} (which cancels its reminder alarms) and then "
                "open the app again to see whether they return.")
    return f"This will REBOOT the phone {adb.serial} and check the alarms after it starts."


def run_check(adb: Adb, mode: str) -> int:
    # Decided from the serial alone, so NOTHING (not even a read) is sent to a phone before it is confirmed.
    if not is_emulator_serial(adb.serial):
        confirm(describe_plan(adb, mode), "REARM-CHECK")
    before = require_alarms(adb)
    outcome = check_foreground(adb, before) if mode == "foreground" else check_boot(adb, before)
    return report(outcome, "force-stop and relaunch" if mode == "foreground" else "reboot")


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Check that Park re-arms its alarms (boot or foreground).")
    parser.add_argument("--mode", required=True, choices=["foreground", "boot"])
    parser.add_argument("--device", "-d", help="serial of the device (default: the one running emulator)")
    parser.add_argument("--adb", help="path to adb.exe")
    args = parser.parse_args(argv)
    try:
        adb = connect(args.adb, args.device)
        return run_check(adb, args.mode)
    except PreconditionFailed as exc:
        print(f"PRECONDITION: {exc}", file=sys.stderr)
        return EXIT_PRECONDITION
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
