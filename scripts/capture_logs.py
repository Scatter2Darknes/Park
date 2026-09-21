"""Capture Park's Logcat output to a file and print a short digest of the lines you usually need - read-only.

    python scripts/capture_logs.py                              # the one running emulator
    python scripts/capture_logs.py --device R52WA025A5R         # a physical phone, named on purpose
    python scripts/capture_logs.py --since "09-20 18:30:00"     # only lines from that time on (device clock)
    python scripts/capture_logs.py --follow                     # stream live until Ctrl+C
    python scripts/capture_logs.py --from-file logs\\old.txt     # digest a saved file instead of asking a device

The full capture is saved as UTF-8 to logs/<timestamp>.txt (logs/ is gitignored: it can contain parking locations and
car names - don't share it). It contains the app's own tags - Park, RppSync, DataSF, Tunnel, ParkBluetooth, ParkDebug -
plus AndroidRuntime, followed by Android's crash buffer.

The digest pulls out, with counts: parking saves, re-arm / recompute / roll-forward lines, BootReceiver, the
stale-row cleanup results ("... removed N rows", both tags), exact-alarm-permission warnings, Tunnel decisions, and any
AndroidRuntime exception with its first stack lines. It is computed from the same text that is saved, so it always
matches the file.

Logcat's buffer is small and a reboot clears it, so capture soon after whatever you are investigating.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

from common import Adb, ScriptError, connect, repo_root, setup_console, timestamp

TAGS = ["Park", "RppSync", "DataSF", "Tunnel", "AndroidRuntime", "ParkBluetooth", "ParkDebug"]
SINCE_FORMAT = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d(\.\d{1,3})?$")

# `logcat -v threadtime` lines:  09-20 19:36:31.762  4494  4530 I ParkDebug: message
LINE = re.compile(r"^(?P<ts>\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(?P<pid>\d+)\s+(?P<tid>\d+)\s+(?P<level>[VDIWEF])\s+(?P<tag>[^:]+?)\s*:\s?(?P<msg>.*)$")


@dataclass
class Category:
    name: str
    matches: "callable"        # (tag, message) -> bool


CATEGORIES = [
    Category("Parking saves", lambda tag, msg: "saveParkedState" in msg or msg.startswith("PARK:")),
    Category("Re-arm / recompute / roll-forward", lambda tag, msg: bool(re.search(
        r"re-arming|Recomputed sweep deadline|recomputeParkedSchedule|rolling forward|REARM:|Foreground re-arm|re-armed", msg, re.I))),
    Category("BootReceiver", lambda tag, msg: "BootReceiver" in msg),
    Category("Stale-row cleanup", lambda tag, msg: bool(re.search(r"Stale-(segment|RPP) cleanup|Skipping stale-", msg, re.I))),
    Category("Exact-alarm permission", lambda tag, msg: "Exact alarm permission not granted" in msg or "inexact fallback" in msg),
    Category("Tunnel decisions", lambda tag, msg: tag == "Tunnel"),
]


@dataclass
class Digest:
    counts: Dict[str, int] = field(default_factory=dict)
    lines: Dict[str, List[str]] = field(default_factory=dict)
    cleanup_totals: Dict[str, int] = field(default_factory=dict)
    exceptions: List[List[str]] = field(default_factory=list)
    total_lines: int = 0


def parse_line(text: str) -> Optional[dict]:
    match = LINE.match(text)
    return match.groupdict() if match else None


def build_digest(text: str, per_category: int = 5, stack_lines: int = 6) -> Digest:
    digest = Digest()
    for category in CATEGORIES:
        digest.counts[category.name] = 0
        digest.lines[category.name] = []
    current: Optional[List[str]] = None

    for raw in text.splitlines():
        parsed = parse_line(raw)
        if not parsed:
            current = None if not raw.startswith(("\t", " ")) else current  # continuation lines keep an open exception
            continue
        digest.total_lines += 1
        tag, msg = parsed["tag"].strip(), parsed["msg"]

        for category in CATEGORIES:
            if category.matches(tag, msg):
                digest.counts[category.name] += 1
                digest.lines[category.name].append(raw)
        cleanup = re.search(r"Stale-(segment|RPP) cleanup removed (\d+) rows", msg)
        if cleanup:
            digest.cleanup_totals[cleanup.group(1)] = digest.cleanup_totals.get(cleanup.group(1), 0) + int(cleanup.group(2))

        if tag == "AndroidRuntime":
            if "FATAL EXCEPTION" in msg:
                current = [raw]
                digest.exceptions.append(current)
            elif current is not None and len(current) < stack_lines + 1:
                current.append(raw)
        else:
            current = None
    for name in digest.lines:
        digest.lines[name] = digest.lines[name][-per_category:]   # the most recent few
    return digest


def render_digest(digest: Digest, per_category: int = 5) -> str:
    out = [f"Digest of {digest.total_lines} log lines"]
    for category in CATEGORIES:
        count = digest.counts[category.name]
        out.append(f"\n{category.name}: {count}")
        for line in digest.lines[category.name][-per_category:]:
            out.append("   " + line.strip())
        if category.name == "Stale-row cleanup" and digest.cleanup_totals:
            out.append("   removed in total: " + ", ".join(f"{k} {v} rows" for k, v in sorted(digest.cleanup_totals.items())))
    out.append(f"\nCrashes (AndroidRuntime FATAL EXCEPTION): {len(digest.exceptions)}")
    for block in digest.exceptions:
        for line in block:
            out.append("   " + line.strip())
        out.append("")
    return "\n".join(out).rstrip() + "\n"


# ---------------------------------------------------------------------------------------------
# Capturing
# ---------------------------------------------------------------------------------------------

def logcat_args(since: Optional[str], follow: bool) -> List[str]:
    args = ["logcat", "-v", "threadtime"]
    if not follow:
        args.append("-d")
    if since:
        if not SINCE_FORMAT.match(since):
            raise ScriptError(f'--since must look like "MM-dd HH:mm:ss" (e.g. "09-20 18:30:00"), not "{since}"')
        args += ["-t", since if "." in since else since + ".000"]
    args.append("-s")
    args += [f"{tag}:V" for tag in TAGS]
    return args


def capture(adb: Adb, since: Optional[str]) -> str:
    """The app-tagged log plus the crash buffer, as one text."""
    main = adb.run(*logcat_args(since, follow=False), allow_fail=True, timeout=120).out
    crash_args = ["logcat", "-v", "threadtime", "-d", "-b", "crash"]
    if since:
        crash_args += ["-t", since if "." in since else since + ".000"]
    crash = adb.run(*crash_args, allow_fail=True, timeout=60).out
    parts = [main.rstrip("\n")]
    if crash.strip():
        parts += ["", "----- crash buffer -----", crash.rstrip("\n")]
    return "\n".join(parts) + "\n"


def follow(adb: Adb, out_path: Path) -> str:
    """Stream the log live, printing and saving each line, until Ctrl+C."""
    command = [adb.adb_path, "-s", adb.serial, *logcat_args(None, follow=True)]
    collected: List[str] = []
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    print(f"Streaming (Ctrl+C to stop); saving to {out_path}", file=sys.stderr)
    try:
        assert proc.stdout is not None
        with open(out_path, "w", encoding="utf-8") as handle:
            for line in proc.stdout:
                print(line, end="")
                handle.write(line)
                handle.flush()
                collected.append(line)
    except KeyboardInterrupt:
        pass
    finally:
        proc.terminate()
    return "".join(collected)


def logs_folder() -> Path:
    folder = repo_root() / "logs"
    folder.mkdir(exist_ok=True)
    return folder


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Capture Park's Logcat and print a digest (read-only).")
    parser.add_argument("--device", "-d", help="serial of the device (default: the one running emulator)")
    parser.add_argument("--adb", help="path to adb.exe")
    parser.add_argument("--since", help='only lines from this device time on, "MM-dd HH:mm:ss"')
    parser.add_argument("--follow", action="store_true", help="stream live until Ctrl+C")
    parser.add_argument("--from-file", type=Path, help="digest this saved log instead of asking a device")
    parser.add_argument("--out", type=Path, help="where to save the capture (default: logs/<timestamp>.txt)")
    args = parser.parse_args(argv)

    try:
        if args.from_file:
            text = args.from_file.read_text(encoding="utf-8", errors="replace")
            print(render_digest(build_digest(text)), end="")
            return 0
        adb = connect(args.adb, args.device)
        out_path = args.out or logs_folder() / f"{timestamp()}.txt"
        if args.follow:
            text = follow(adb, out_path)
        else:
            text = capture(adb, args.since)
            out_path.write_text(text, encoding="utf-8")
            print(f"Saved {len(text.splitlines())} lines to {out_path}")
        print()
        print(render_digest(build_digest(text)), end="")
        print("\nNote: Logcat's buffer is small and a reboot clears it - capture soon after the event you care about.")
        return 0
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
