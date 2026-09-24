"""Overlap check between SFMTA's two temporary-restriction feeds (docs/park-closures-spec.md §4, "Overlap test first").

Question: how often does a street closure have a tow-away zone on the same block at the same time? The answer decides
whether the app needs the spec's merge rule (show ONE tow warning where both apply) or can keep sending both alerts.

    python scripts/overlap_check.py                     # download both feeds and report
    python scripts/overlap_check.py --save-dir C:\\temp\\feeds    # also keep the raw downloads
    python scripts/overlap_check.py --from-dir C:\\temp\\feeds    # re-run on saved downloads (no network)
    python scripts/overlap_check.py --force             # report even if the tow feed is stale

No phone or emulator is involved: it only reads the two public DataSF feeds, citywide, the same way the app does
(so no location is ever sent). Both are public data, so the output contains no private information.

Matching, the same way the app matches a parked car:
  - a closure and a tow zone overlap when they share a block (CNN; a tow row can list several) AND at least one of
    the tow zone's daily enforcement windows intersects the closure's start -> end time;
  - "covered" = the share of the closure's time that tow enforcement covers (exact when >= 95%).

Refuses to give a verdict while the tow feed is stale (its newest permit over 7 days old - in September 2026 it had
none since July 20), because comparing thousands of closures with a handful of tow zones says nothing about how
the feeds overlap. Exit codes: 0 = report printed, 2 = tow feed stale (no verdict), 1 = error.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from common import repo_root, setup_console

try:
    from zoneinfo import ZoneInfo
    SF = ZoneInfo("America/Los_Angeles")
except Exception:  # pragma: no cover - tzdata missing: fall back to naive local times (both feeds are SF-local anyway)
    SF = None

CLOSURES_ID = "8x25-yybr"
TOW_ID = "6r5h-j298"
ROW_LIMIT = 50000
STALE_AFTER = timedelta(days=7)
EXACT_COVERAGE = 0.95

DAY_NAMES = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
ALL_DAYS = frozenset(range(7))


# ---------------------------------------------------------------- data shapes

@dataclass
class Closure:
    object_id: str
    case: str
    name: str
    cnn: str
    street: str
    start: datetime
    end: datetime
    full: bool  # veh_imp is all-lanes-closed (or missing, read as full like the app does)


@dataclass
class TowZone:
    row_id: str
    case: str
    cnns: Tuple[str, ...]
    street: str
    start_date: date
    end_date: date
    start_minute: int
    end_minute: int
    all_day: bool
    days: frozenset = field(default=ALL_DAYS)
    days_text: str = ""


@dataclass
class Match:
    closure: Closure
    tows: List[TowZone]
    coverage: float  # 0..1 of the closure's time under tow enforcement


# ---------------------------------------------------------------- parsing (mirrors TowZone.kt / StreetClosureApi.kt)

def parse_days(text: Optional[str]) -> frozenset:
    """'Monday - Friday', 'Friday - Wednesday' (wraps), 'Monday,Tuesday', 'Saturday'. Blank/unreadable = every day."""
    if not text or not text.strip():
        return ALL_DAYS
    days = set()
    for part in (p.strip() for p in text.split(",")):
        if not part:
            continue
        ends = [e.strip() for e in part.replace("–", "-").split("-") if e.strip()]
        idx = [_day_index(e) for e in ends]
        if not ends or len(ends) > 2 or any(i is None for i in idx):
            return ALL_DAYS
        if len(idx) == 1:
            days.add(idx[0])
        else:
            d = idx[0]
            while True:
                days.add(d)
                if d == idx[1]:
                    break
                d = (d + 1) % 7
    return frozenset(days) if days else ALL_DAYS


def _day_index(text: str) -> Optional[int]:
    key = text.strip().lower()[:3]
    if len(key) < 3:
        return None
    for i, name in enumerate(DAY_NAMES):
        if name.startswith(key):
            return i
    return None


def parse_clock(text: Optional[str]) -> Optional[int]:
    """'7:00 AM' -> 420. None if unreadable."""
    if not text:
        return None
    try:
        t = datetime.strptime(text.strip().upper().replace(".", ""), "%I:%M %p")
    except ValueError:
        return None
    return t.hour * 60 + t.minute


def _floating(text: Optional[str]) -> Optional[datetime]:
    """A zone-less feed timestamp ('2026-09-15T08:00:00.000'), read as SF local time."""
    if not text:
        return None
    try:
        return datetime.strptime(text[:19], "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        return None


def _clean(row: dict, key: str) -> str:
    value = row.get(key)
    return "" if value is None else str(value).strip()


def parse_closures(rows: Sequence[dict]) -> List[Closure]:
    out = []
    for r in rows:
        start, end = _floating(r.get("start_dt")), _floating(r.get("end_dt"))
        cnn = _clean(r, "cnn")
        if not cnn or start is None or end is None or end <= start:
            continue
        impact = _clean(r, "veh_imp")
        out.append(Closure(
            object_id=_clean(r, "objectid"), case=_clean(r, "case_num"), name=_clean(r, "case_name"), cnn=cnn,
            street=_clean(r, "street"), start=start, end=end,
            full=impact not in ("some-lanes-closed", "all-lanes-open"),
        ))
    return out


def parse_tow_zones(rows: Sequence[dict]) -> List[TowZone]:
    out = []
    for r in rows:
        cnns = tuple(c.strip() for c in _clean(r, "cnn").split(",") if c.strip())
        start, end = _floating(r.get("startdate")), _floating(r.get("enddate"))
        if not cnns or start is None or end is None or end.date() < start.date():
            continue
        sm, em = parse_clock(r.get("starttime")), parse_clock(r.get("endtime"))
        out.append(TowZone(
            row_id=_clean(r, ":id"), case=_clean(r, "casenumber"), cnns=cnns, street=_clean(r, "streetfrontagename"),
            start_date=start.date(), end_date=end.date(), start_minute=sm or 0, end_minute=em or 0,
            all_day=_clean(r, "_24hourenforcement").lower() == "yes" or sm is None or em is None,
            days=parse_days(r.get("notes")), days_text=_clean(r, "notes"),
        ))
    return out


# ---------------------------------------------------------------- windows and overlap

def tow_windows(zone: TowZone, frm: datetime, to: datetime) -> List[Tuple[datetime, datetime]]:
    """Every enforcement window of [zone] that intersects [frm, to). Same rules as TowZone.windowOn in the app."""
    windows = []
    d = max(zone.start_date, (frm - timedelta(days=1)).date())
    last = min(zone.end_date, to.date())
    while d <= last:
        if d.weekday() in zone.days:
            if zone.all_day:
                s, e = datetime.combine(d, time()), datetime.combine(d + timedelta(days=1), time())
            else:
                s = datetime.combine(d, time()) + timedelta(minutes=zone.start_minute)
                if zone.end_minute >= 23 * 60 + 59:
                    e = datetime.combine(d + timedelta(days=1), time())
                elif zone.end_minute > zone.start_minute:
                    e = datetime.combine(d, time()) + timedelta(minutes=zone.end_minute)
                else:  # overnight, or equal = 24 h
                    e = datetime.combine(d + timedelta(days=1), time()) + timedelta(minutes=zone.end_minute)
            if s < to and e > frm:
                windows.append((max(s, frm), min(e, to)))
        d += timedelta(days=1)
    return windows


def _merged_length(spans: List[Tuple[datetime, datetime]]) -> timedelta:
    total, cur_s, cur_e = timedelta(), None, None
    for s, e in sorted(spans):
        if cur_e is None or s > cur_e:
            if cur_e is not None:
                total += cur_e - cur_s
            cur_s, cur_e = s, e
        else:
            cur_e = max(cur_e, e)
    if cur_e is not None:
        total += cur_e - cur_s
    return total


def find_overlaps(closures: List[Closure], zones: List[TowZone]) -> List[Match]:
    """For each closure, the tow zones on its block whose enforcement intersects it, and how much of it they cover."""
    by_cnn: Dict[str, List[TowZone]] = {}
    for z in zones:
        for cnn in z.cnns:
            by_cnn.setdefault(cnn, []).append(z)
    matches = []
    for c in closures:
        spans, hit = [], []
        for z in by_cnn.get(c.cnn, []):
            w = tow_windows(z, c.start, c.end)
            if w:
                spans.extend(w)
                hit.append(z)
        if hit:
            duration = (c.end - c.start).total_seconds()
            matches.append(Match(c, hit, _merged_length(spans).total_seconds() / duration if duration else 0.0))
    return matches


def summarize(closures: List[Closure], zones: List[TowZone], matches: List[Match]) -> Dict[str, object]:
    """The numbers the report prints. Distinct closure CASES count once (a recurring closure is a row per day)."""
    cases = {c.case or c.object_id for c in closures}
    matched_cases = {m.closure.case or m.closure.object_id for m in matches}
    matched_tows = {z.row_id for m in matches for z in m.tows}
    exact = [m for m in matches if m.coverage >= EXACT_COVERAGE]
    return {
        "closure_rows": len(closures),
        "closure_cases": len(cases),
        "tow_zones": len(zones),
        "closure_rows_with_tow": len(matches),
        "closure_cases_with_tow": len(matched_cases),
        "tow_zones_with_closure": len(matched_tows),
        "exact_rows": len(exact),
        "partial_rows": len(matches) - len(exact),
        "full_closure_rows_with_tow": sum(1 for m in matches if m.closure.full),
        "by_closure_type": Counter((m.closure.name or "?") for m in matches).most_common(5),
    }


def feed_is_stale(newest_entry: Optional[datetime], now: datetime) -> bool:
    return newest_entry is None or now - newest_entry > STALE_AFTER


# ---------------------------------------------------------------- network

def _token() -> str:
    path = repo_root() / "app" / "src" / "main" / "assets" / "datasf_app_token.txt"
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def fetch(dataset: str, params: Dict[str, str]) -> list:
    url = f"https://data.sf.gov/resource/{dataset}.json?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url)
    token = _token()
    if token:
        request.add_header("X-App-Token", token)  # never printed
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def download(now: datetime) -> Dict[str, list]:
    cutoff = now.strftime("%Y-%m-%dT%H:%M:%S")
    since = (now - timedelta(days=1)).strftime("%Y-%m-%dT00:00:00")
    data = {
        "closures": fetch(CLOSURES_ID, {
            "$select": "objectid,case_num,case_name,cnn,street,veh_imp,start_dt,end_dt",
            "$where": f"status = 'Permitted' AND end_dt > '{cutoff}'", "$limit": str(ROW_LIMIT)}),
        "tow": fetch(TOW_ID, {
            "$select": ":id,casenumber,cnn,streetfrontagename,startdate,enddate,starttime,endtime,notes,_24hourenforcement",
            "$where": f"enddate >= '{since}'", "$limit": str(ROW_LIMIT)}),
        "tow_newest": fetch(TOW_ID, {"$select": "max(datetimeentered) as newest"}),
    }
    for key in ("closures", "tow"):
        if len(data[key]) >= ROW_LIMIT:
            raise RuntimeError(f"{key}: hit the {ROW_LIMIT}-row limit, so the download may be incomplete")
    return data


# ---------------------------------------------------------------- report

def report(data: Dict[str, list], now: datetime, force: bool) -> int:
    newest = _floating((data.get("tow_newest") or [{}])[0].get("newest"))
    closures = parse_closures(data["closures"])
    zones = parse_tow_zones(data["tow"])
    print(f"Tow feed: {len(zones)} zone(s) ending yesterday or later; newest permit entered "
          f"{newest:%Y-%m-%d} " if newest else "Tow feed: newest permit date unknown ", end="")
    stale = feed_is_stale(newest, now)
    print("(STALE - over 7 days old)" if stale else "(current)")
    print(f"Closure feed: {len(closures)} permitted closure row(s) not over yet")

    if stale and not force:
        print("\nNo verdict: the tow feed has stopped getting new permits, so an overlap count now would only say the "
              "tow feed is broken, not how the feeds overlap. Re-run when the newest permit is recent, or pass --force.")
        return 2

    matches = find_overlaps(closures, zones)
    s = summarize(closures, zones, matches)
    print()
    print(f"Closure rows with a tow zone on the same block at the same time: {s['closure_rows_with_tow']} of {s['closure_rows']}"
          f"  ({s['closure_cases_with_tow']} of {s['closure_cases']} distinct closure cases)")
    print(f"Tow zones that overlap at least one closure: {s['tow_zones_with_closure']} of {s['tow_zones']}")
    print(f"  exact (tow enforcement covers >= {EXACT_COVERAGE:.0%} of the closure): {s['exact_rows']}")
    print(f"  partial: {s['partial_rows']}")
    print(f"  of which full closures (the app's 'blocked in' alert): {s['full_closure_rows_with_tow']}")
    if s["by_closure_type"]:
        print("  most common closure names among matches: " + ", ".join(f"{n} ({k})" for n, k in s["by_closure_type"]))
    for m in sorted(matches, key=lambda m: m.closure.start)[:10]:
        c = m.closure
        print(f"    {c.street or c.cnn}  {c.start:%a %b %d %H:%M} -> {c.end:%a %b %d %H:%M}  "
              f"covered {m.coverage:.0%} by tow case(s) {', '.join(z.case or z.row_id for z in m.tows)}")
    if stale:
        print("\n(--force: the tow feed is stale, so treat these numbers as a floor, not the real overlap.)")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="How often SFMTA street closures and tow zones overlap (no device needed).")
    parser.add_argument("--save-dir", help="also save the raw downloads here (public data)")
    parser.add_argument("--from-dir", help="read closures.json / tow.json / tow_newest.json from here instead of downloading")
    parser.add_argument("--force", action="store_true", help="report even if the tow feed is stale")
    args = parser.parse_args(argv)
    now = datetime.now(SF).replace(tzinfo=None) if SF else datetime.now()
    try:
        if args.from_dir:
            folder = Path(args.from_dir)
            data = {k: json.loads((folder / f"{k}.json").read_text(encoding="utf-8")) for k in ("closures", "tow", "tow_newest")}
        else:
            data = download(now)
            if args.save_dir:
                folder = Path(args.save_dir)
                folder.mkdir(parents=True, exist_ok=True)
                for k, v in data.items():
                    (folder / f"{k}.json").write_text(json.dumps(v), encoding="utf-8")
                print(f"Saved raw downloads to {folder}")
        return report(data, now, args.force)
    except Exception as exc:  # network, parsing, or a missing saved file
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
