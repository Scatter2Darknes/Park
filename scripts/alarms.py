"""Turn `adb shell dumpsys alarm` into a readable table of Park's alarms - read-only.

    python scripts/alarms.py                       # the one running emulator
    python scripts/alarms.py --device R52WA025A5R  # a physical phone, named on purpose (reads only)
    python scripts/alarms.py --json                # machine-readable
    python scripts/alarms.py --raw                 # also dump the raw dumpsys lines it read
    python scripts/alarms.py --file saved.txt      # parse a saved dumpsys instead of asking a device

What it shows: each live alarm of com.example.park - what it is (reminder / roll-forward), when it fires in
San Francisco time and in the device's own time, how many minutes before the sweep a reminder is, and whether
Android will fire it EXACTLY or inexactly (an exact-alarm permission problem shows up here). It also reports the
exact-alarm permission and notification permission app-ops.

Android versions format `dumpsys alarm` differently, so parsing is tolerant: a line it can't make sense of never
crashes it. But it is never dropped quietly either: every line that mentions the app and wasn't understood is listed
under a WARNING, `alarms.py` then exits 1, and rearm_check.py stops with an error - a list that may be incomplete must
never be read as "no alarms". Three formats are understood (see tests/fixtures/):
  * Android 10 (API 29) has no per-alarm origWhen line and numbers every alarm "#0" inside its batch:
        RTC_WAKEUP #0: Alarm{c6c036b type 0 when 1790033893903 com.example.park}
          tag=*walarm*:com.example.park/.ParkingReminderReceiver
          type=0 expectedWhenElapsed=... when=2026-09-21 16:38:13.903
          window=0 repeatInterval=0 count=0 flags=0x5
    `when` in the header is the due time (epoch ms, for RTC alarms) and `window=0` means exact.
  * newer builds add a line per alarm:  type=RTC_WAKEUP origWhen=2026-10-01 05:00:00.000 window=0 exactAllowReason=permission
    -> `window=0` means exact, `window>0` means inexact.
  * older/Samsung builds have no such line, but their alarm HISTORY has entries like
    [tag=*walarm*:com.example.park/.X ... H=PI:2b6896f OW=... WL=3600000 ...] that share the PendingIntent id with the
    live alarm (PendingIntentRecord{2b6896f ...}); WL (window length) above 0 means inexact.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from common import PACKAGE, Adb, ScriptError, connect, setup_console

SF_TZ_NAME = "America/Los_Angeles"

# "    RTC_WAKEUP #80: Alarm{e685937 type 0 origWhen 1790082000000 whenElapsed 129897319 com.example.park}"
# API 29 (no origWhen / whenElapsed): "    RTC_WAKEUP #0: Alarm{c6c036b type 0 when 1790033893903 com.example.park}"
ALARM_HEADER = re.compile(
    r"^(?P<indent>\s*)(?P<kind>[A-Z_]+)\s+#(?P<num>\d+):\s+Alarm\{(?P<id>\w+)\s+type\s+(?P<type>\d+)\s+"
    r"(?:origWhen\s+(?P<orig>\d+)\s+whenElapsed\s+(?P<elapsed>\d+)|when\s+(?P<when>\d+))\s+(?P<pkg>[\w.]+)\}"
)
TAG_LINE = re.compile(r"\btag=\S*?:?(?P<pkg>[\w.]+)/\.?(?P<receiver>[\w.$]+)")
# The window is printed as a plain 0 for exact alarms but as a duration like "+1h0m0s0ms" for inexact ones
# (seen in the emulator's own dump, on another app's alarm), so it is captured as text and converted below.
NEW_STYLE_LINE = re.compile(r"\btype=\w+\s+origWhen=.*?\bwindow=(?P<window>\S+)(?:\s+exactAllowReason=(?P<reason>\S+))?")
_DURATION_PART = re.compile(r"(\d+)(ms|d|h|m|s)")
_UNIT_MS = {"d": 86_400_000, "h": 3_600_000, "m": 60_000, "s": 1_000, "ms": 1}
# API 29 prints the window on its own line: "      window=0 repeatInterval=0 count=0 flags=0x5"
OLD_STYLE_WINDOW_LINE = re.compile(r"^\s*window=(?P<window>\S+)\s+repeatInterval=")
# Bookkeeping alarms Android files under an app's name; they are not the app's reminders.
PLATFORM_TAGS = ("ACTION_FORCE_STOP_RESCHEDULE",)
OPERATION_LINE = re.compile(r"PendingIntentRecord\{(?P<pi>\w+)\s")
HISTORY_LINE = re.compile(r"\[tag=(?P<tag>\S+)\s.*?\bH=PI:(?P<pi>\w+)\b.*?\bWL=(?P<wl>\d+)\b(?P<rest>.*)\]")
HISTORY_RTC = re.compile(r"\brtc=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)")

RECEIVER_KINDS = {
    "ParkingReminderReceiver": "reminder",
    "ScheduleRollForwardReceiver": "roll-forward",
}


@dataclass
class Alarm:
    number: int
    alarm_type: str                    # RTC_WAKEUP ...
    when_ms: int                       # origWhen: when it is due, epoch milliseconds
    receiver: str                      # e.g. ParkingReminderReceiver
    pi_id: Optional[str] = None        # PendingIntentRecord id, the join key to the history
    window_ms: Optional[int] = None    # live window= value when the build prints it
    exact_reason: Optional[str] = None
    inexact: Optional[bool] = None     # True / False, or None when it can't be told
    inexact_source: str = "unknown"    # "live window", "history WL", "unknown"
    kind: str = ""                     # reminder / roll-forward / <receiver>
    minutes_before_roll: Optional[int] = None   # for reminders: how long before the (nearest) roll-forward alarm
    roll_ambiguous: bool = False                # True when several roll-forward alarms exist, so "nearest" may be the wrong family
    raw_lines: List[str] = field(default_factory=list)

    @property
    def key(self) -> Tuple[str, int]:
        """What identifies an alarm across two snapshots (used by rearm_check): its receiver and due time."""
        return (self.receiver, self.when_ms)


@dataclass
class HistoryEntry:
    pi_id: str
    window_ms: int
    rtc: str          # when it was SET, in device-local text, or "" if the line has no rtc=
    order: int        # position in the dump, to break ties


@dataclass
class ParseResult:
    alarms: List[Alarm]
    history: List[HistoryEntry]
    unparsed: List[str]           # lines that mention the package but couldn't be understood
    raw_lines: List[str]          # every line that was read as part of an alarm entry or history entry


# ---------------------------------------------------------------------------------------------
# Parsing (pure; tested against fixtures)
# ---------------------------------------------------------------------------------------------

def _indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def parse_window(text: str) -> Optional[int]:
    """Milliseconds from a `window=` value: '0', '3600000', or a duration such as '+1h0m0s0ms'. None if unreadable."""
    text = text.strip()
    if re.fullmatch(r"\d+", text):
        return int(text)
    parts = _DURATION_PART.findall(text)
    if parts and re.fullmatch(r"\+?(?:\d+(?:ms|d|h|m|s))+", text):
        return sum(int(number) * _UNIT_MS[unit] for number, unit in parts)
    return None


def parse_dumpsys_alarm(text: str, package: str = PACKAGE) -> ParseResult:
    lines = text.splitlines()
    alarms: List[Alarm] = []
    history: List[HistoryEntry] = []
    unparsed: List[str] = []
    raw: List[str] = []
    seen = set()

    i = 0
    while i < len(lines):
        line = lines[i]
        header = ALARM_HEADER.match(line)
        if header and header.group("pkg") == package:
            block = [line]
            j = i + 1
            while j < len(lines) and lines[j].strip() and _indent(lines[j]) > len(header.group("indent")):
                block.append(lines[j])
                j += 1
            if header.group("when") and header.group("kind").startswith("ELAPSED"):
                # API 29 prints `when` as an elapsed-clock reading for these, not a date; Park only uses RTC alarms.
                unparsed.extend(block)
                i = j
                continue
            if any(tag in body for body in block[1:] for tag in PLATFORM_TAGS):
                i = j  # e.g. the placeholder Android sets when the app is force-stopped: consumed, not one of ours
                continue
            alarm = Alarm(
                number=int(header.group("num")), alarm_type=header.group("kind"),
                when_ms=int(header.group("orig") or header.group("when")), receiver="unknown", raw_lines=block,
            )
            for body in block[1:]:
                tag = TAG_LINE.search(body)
                if tag and tag.group("pkg") == package:
                    alarm.receiver = tag.group("receiver")
                new_style = NEW_STYLE_LINE.search(body)
                if new_style:
                    alarm.window_ms = parse_window(new_style.group("window"))  # None if unreadable -> falls back to history
                    alarm.exact_reason = new_style.group("reason")
                old_window = OLD_STYLE_WINDOW_LINE.match(body)
                if old_window:
                    alarm.window_ms = parse_window(old_window.group("window"))
                operation = OPERATION_LINE.search(body)
                if operation:
                    alarm.pi_id = operation.group("pi")
            identity = (alarm.pi_id, alarm.when_ms, alarm.receiver)
            if identity not in seen:  # the same alarm can be listed in more than one section of the dump
                seen.add(identity)
                alarms.append(alarm)
                raw.extend(block)
            i = j
            continue
        if "Alarm{" in line and package in line:
            unparsed.append(line)  # looks like one of ours but the layout is unfamiliar: warn about it, don't crash
        elif re.match(rf"^\s*tag=\S*{re.escape(package)}/", line):
            unparsed.append(line)  # one of our alarms' tag lines that no header claimed
        hist = HISTORY_LINE.search(line)
        if hist and package in hist.group("tag"):
            rtc = HISTORY_RTC.search(hist.group("rest"))
            history.append(HistoryEntry(hist.group("pi"), int(hist.group("wl")), rtc.group(1) if rtc else "", len(history)))
            raw.append(line)
        i += 1

    _classify(alarms, history)
    return ParseResult(alarms, history, unparsed, raw)


def _latest_history_by_pi(history: List[HistoryEntry]) -> Dict[str, HistoryEntry]:
    """The most recent history entry for each PendingIntent id (latest rtc= time; later in the file wins ties)."""
    latest: Dict[str, HistoryEntry] = {}
    for entry in history:
        current = latest.get(entry.pi_id)
        if current is None or (entry.rtc, entry.order) >= (current.rtc, current.order):
            latest[entry.pi_id] = entry
    return latest


def _classify(alarms: List[Alarm], history: List[HistoryEntry]) -> None:
    latest = _latest_history_by_pi(history)
    for alarm in alarms:
        alarm.kind = RECEIVER_KINDS.get(alarm.receiver, alarm.receiver.lower())
        if alarm.window_ms is not None:                       # newer builds say it outright
            alarm.inexact, alarm.inexact_source = alarm.window_ms > 0, "live window"
        elif alarm.pi_id and alarm.pi_id in latest:           # older builds: look in the history
            alarm.inexact, alarm.inexact_source = latest[alarm.pi_id].window_ms > 0, "history WL"
    rolls = sorted(a.when_ms for a in alarms if a.kind == "roll-forward")
    for alarm in alarms:
        if alarm.kind == "reminder":
            after = [r for r in rolls if r >= alarm.when_ms]
            if after:
                alarm.minutes_before_roll = -round((after[0] - alarm.when_ms) / 60_000)
                # A car can have a sweep AND an RPP roll-forward alarm; a dump doesn't say which is which, so with more
                # than one the nearest may belong to the other family (RPP's fires at the window's close, not sweep start).
                alarm.roll_ambiguous = len(rolls) > 1


# ---------------------------------------------------------------------------------------------
# Times
# ---------------------------------------------------------------------------------------------

def _pacific_fallback(when_utc: dt.datetime) -> dt.datetime:
    """Pacific time without the tz database (US rules since 2007: DST from the 2nd Sunday in March at 2am to the 1st
    Sunday in November at 2am). Only used if Python has no tzdata."""
    def nth_sunday(year: int, month: int, n: int) -> dt.date:
        first = dt.date(year, month, 1)
        return first + dt.timedelta(days=(6 - first.weekday()) % 7 + 7 * (n - 1))
    year = when_utc.year
    dst_start = dt.datetime.combine(nth_sunday(year, 3, 2), dt.time(2)) + dt.timedelta(hours=8)   # 2am PST = 10:00 UTC
    dst_end = dt.datetime.combine(nth_sunday(year, 11, 1), dt.time(2)) + dt.timedelta(hours=7)    # 2am PDT =  9:00 UTC
    naive = when_utc.replace(tzinfo=None)
    return (naive - dt.timedelta(hours=7 if dst_start <= naive < dst_end else 8))


def format_time(ms: int, tz_name: str) -> str:
    """'Tue 2026-09-22 06:00' for the epoch-millis `ms` in the named zone (falls back to fixed rules for Pacific)."""
    utc = dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc)
    try:
        import zoneinfo
        local = utc.astimezone(zoneinfo.ZoneInfo(tz_name))
    except Exception:  # no tzdata on this machine
        if tz_name != SF_TZ_NAME:
            return utc.strftime("%a %Y-%m-%d %H:%M") + " UTC"
        local = _pacific_fallback(utc)
    return local.strftime("%a %Y-%m-%d %H:%M")


# ---------------------------------------------------------------------------------------------
# App-ops (exact-alarm and notification permission)
# ---------------------------------------------------------------------------------------------

def parse_appop(text: str, op: str) -> str:
    """'allow' / 'deny' / 'ignore' / 'default' ... from `appops get` output, or 'unknown'."""
    match = re.search(rf"\b{re.escape(op)}\s*:\s*(\w+)", text)
    return match.group(1).lower() if match else "unknown"


ANDROID_13_SDK = 33  # POST_NOTIFICATIONS became a runtime permission here; before, only the appop exists.

NOTIFICATIONS_BLOCKED_WARNING = "WARNING: notifications are BLOCKED — reminders will not be shown"


@dataclass
class Permissions:
    exact_alarm: str = "unknown"
    notifications: str = "unknown"
    # Where the notifications answer came from: "dumpsys package", "appops" or "unknown" (for the report only).
    notifications_source: str = "unknown"

    @property
    def notifications_blocked(self) -> bool:
        """True when Android will silently drop the app's notifications. Only ever True on a positive reading."""
        return self.notifications in ("deny", "ignore")


def parse_notification_grant(package_dump: str) -> Optional[bool]:
    """The runtime grant from `dumpsys package <pkg>`: True / False, or None if there is no such line.

    The real line looks like
        android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|...]
    (the "requested permissions" list names the permission too, but with no `granted=`, so it never matches).
    """
    match = re.search(r"android\.permission\.POST_NOTIFICATIONS:\s*granted=(true|false)", package_dump)
    return None if match is None else match.group(1) == "true"


def resolve_notifications(sdk: Optional[int], grant: Optional[bool], appop: str) -> Tuple[str, str]:
    """(state, source). On Android 13+ the runtime grant is the real answer: `appops` says "ignore" (cryptic) for a
    denied app, and has no record at all until something has used the permission. Older versions have only the appop.
    An unknown SDK still trusts a grant line if one exists, since only Android 13+ prints it."""
    if grant is not None and (sdk is None or sdk >= ANDROID_13_SDK):
        return ("allow" if grant else "deny"), "dumpsys package"
    if appop != "unknown":
        return appop, "appops"
    return "unknown", "unknown"


def read_permissions(adb: Adb) -> Permissions:
    exact = adb.shell(f"appops get {PACKAGE} SCHEDULE_EXACT_ALARM", allow_fail=True).out
    notif = adb.shell(f"appops get {PACKAGE} POST_NOTIFICATION", allow_fail=True).out
    package_dump = adb.shell(f"dumpsys package {PACKAGE}", allow_fail=True, timeout=60).out
    sdk_text = (adb.getprop("ro.build.version.sdk") or "").strip()
    sdk = int(sdk_text) if sdk_text.isdigit() else None
    state, source = resolve_notifications(sdk, parse_notification_grant(package_dump), parse_appop(notif, "POST_NOTIFICATION"))
    return Permissions(parse_appop(exact, "SCHEDULE_EXACT_ALARM"), state, source)


# ---------------------------------------------------------------------------------------------
# Reading from a device, and reporting
# ---------------------------------------------------------------------------------------------

def fetch_dumpsys(adb: Adb) -> str:
    return adb.shell("dumpsys alarm", timeout=60).out


def unparsed_warning(unparsed: List[str]) -> str:
    """The loud message for lines that mention the app but weren't understood."""
    shown = "\n".join("    " + line.strip() for line in unparsed[:10])
    more = f"\n    ... and {len(unparsed) - 10} more" if len(unparsed) > 10 else ""
    return (f"WARNING: {len(unparsed)} line(s) mentioning {PACKAGE} in `dumpsys alarm` were NOT understood, so the alarm list "
            f"may be incomplete - do not read it as \"no alarms\". Unread lines:\n{shown}{more}")


def fetch_alarms(adb: Adb) -> ParseResult:
    """The device's live alarms for the app right now (used by rearm_check.py). Refuses to answer when part of the dump
    wasn't understood: a false "no alarms" is the dangerous direction for this tool."""
    result = parse_dumpsys_alarm(fetch_dumpsys(adb))
    if result.unparsed:
        raise ScriptError(unparsed_warning(result.unparsed))
    return result


def device_timezone(adb: Optional[Adb]) -> str:
    if adb is None:
        return SF_TZ_NAME
    name = adb.getprop("persist.sys.timezone")
    return name or SF_TZ_NAME


def yes_no(value: Optional[bool]) -> str:
    return "unknown" if value is None else ("yes" if value else "no")


def summarize(alarms: List[Alarm], permissions: Permissions) -> str:
    if not alarms:
        return f"0 live alarms; exact permission: {permissions.exact_alarm}"
    nxt = min(alarms, key=lambda a: a.when_ms)
    inexact_values = {a.inexact for a in alarms}
    inexact = "yes" if True in inexact_values else ("unknown" if None in inexact_values else "no")
    return (f"{len(alarms)} live alarm{'s' if len(alarms) != 1 else ''}; next: {format_time(nxt.when_ms, SF_TZ_NAME)} PT; "
            f"inexact: {inexact}; exact permission: {permissions.exact_alarm}")


def render_table(alarms: List[Alarm], local_tz: str) -> str:
    same_zone = local_tz == SF_TZ_NAME
    rows = [("#", "kind", "receiver", "due (San Francisco)", "due (device time)" + (" = SF" if same_zone else f" [{local_tz}]"),
             "vs sweep", "timing")]
    for a in sorted(alarms, key=lambda x: (x.when_ms, x.number)):
        vs = (f"{a.minutes_before_roll} min" + ("*" if a.roll_ambiguous else "")) if a.minutes_before_roll is not None \
            else ("sweep start" if a.kind == "roll-forward" else "")
        timing = {True: "INEXACT", False: "exact", None: "window unknown"}[a.inexact]
        rows.append((str(a.number), a.kind, a.receiver, format_time(a.when_ms, SF_TZ_NAME), format_time(a.when_ms, local_tz), vs, timing))
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    table = "\n".join("  ".join(cell.ljust(widths[i]) for i, cell in enumerate(r)).rstrip() for r in rows)
    if any(a.roll_ambiguous and a.minutes_before_roll is not None for a in alarms):
        table += ("\n* More than one roll-forward alarm exists (a sweep one, at the sweep's start, and an RPP one, at the end of the "
                  "RPP window), and the dump can't say which is which. These minutes are measured to the NEAREST one, so they may "
                  "refer to the other family.")
    return table


def to_json(result: ParseResult, permissions: Permissions, local_tz: str) -> dict:
    return {
        "package": PACKAGE,
        "summary": summarize(result.alarms, permissions),
        "permissions": {
            "exact_alarm": permissions.exact_alarm,
            "notifications": permissions.notifications,
            "notifications_blocked": permissions.notifications_blocked,
        },
        "alarms": [
            {
                "number": a.number, "kind": a.kind, "receiver": a.receiver, "due_epoch_ms": a.when_ms,
                "due_sf": format_time(a.when_ms, SF_TZ_NAME), "due_local": format_time(a.when_ms, local_tz),
                "minutes_before_sweep": a.minutes_before_roll, "minutes_approximate": a.roll_ambiguous, "inexact": a.inexact, "inexact_source": a.inexact_source,
                "exact_allow_reason": a.exact_reason, "pending_intent_id": a.pi_id,
            }
            for a in sorted(result.alarms, key=lambda x: (x.when_ms, x.number))
        ],
        "unparsed_lines": result.unparsed,
    }


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Decode `dumpsys alarm` for Park (read-only).")
    parser.add_argument("--device", "-d", help="serial of the device (default: the one running emulator)")
    parser.add_argument("--adb", help="path to adb.exe")
    parser.add_argument("--json", action="store_true", help="print JSON instead of a table")
    parser.add_argument("--raw", action="store_true", help="also print the raw dumpsys lines that were read")
    parser.add_argument("--file", type=Path, help="parse this saved `dumpsys alarm` output instead of asking a device")
    args = parser.parse_args(argv)

    try:
        if args.file:
            adb = None
            text = args.file.read_text(encoding="utf-8", errors="replace")
            permissions = Permissions()
        else:
            adb = connect(args.adb, args.device)
            text = fetch_dumpsys(adb)
            permissions = read_permissions(adb)
        result = parse_dumpsys_alarm(text)
        local_tz = device_timezone(adb)
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    exit_code = 1 if result.unparsed else 0   # an incomplete list is a failure, not a quiet success
    if args.json:
        print(json.dumps(to_json(result, permissions, local_tz), indent=2))
        if result.unparsed:
            print(unparsed_warning(result.unparsed), file=sys.stderr)
        return exit_code

    if result.alarms:
        print(render_table(result.alarms, local_tz))
    print()
    print(summarize(result.alarms, permissions))
    source = "" if permissions.notifications_source == "unknown" else f" (from {permissions.notifications_source})"
    print(f"notifications permission: {permissions.notifications}{source}")
    if permissions.notifications_blocked:
        print(NOTIFICATIONS_BLOCKED_WARNING)
    if result.unparsed:
        print("\n" + unparsed_warning(result.unparsed))
    if args.raw:
        print("\nRaw lines read:")
        for line in result.raw_lines:
            print(line)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
