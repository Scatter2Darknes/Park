"""Public Works permit investigation (docs/park-sources-refactor-spec.md, Part A).

Question: SFMTA's tow-zone feed (6r5h-j298) has had no new permits since 2026-07-20. Do Public Works datasets keep
getting the short-notice permits that post no-parking / tow-away signs after that date? If so, the permits exist,
just not where Park looks.

    python scripts/pw_permit_check.py                        # download, analyse, print the verdict
    python scripts/pw_permit_check.py --save-dir C:\\temp\\pw  # also keep the raw downloads
    python scripts/pw_permit_check.py --from-dir C:\\temp\\pw  # re-run on saved downloads (no network)
    python scripts/pw_permit_check.py --describe             # each dataset's columns and a sample row
    python scripts/pw_permit_check.py --survey               # permit types / statuses / entry-date span per dataset

No phone or emulator is involved: it only reads public DataSF datasets, citywide (no location is ever sent), so the
output contains no private information. Uses the app's DataSF token if present (never printed) and pauses between
requests. Exit codes: 0 = report printed, 1 = error.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Dict, FrozenSet, List, Optional, Sequence, Tuple

from common import repo_root, setup_console

try:
    from zoneinfo import ZoneInfo
    SF = ZoneInfo("America/Los_Angeles")
except Exception:  # pragma: no cover - tzdata missing: every feed here is SF-local anyway
    SF = None

TOW_ID = "6r5h-j298"
TOW_CUTOFF = date(2026, 7, 20)   # the tow feed's newest entry, per TowZoneApi.kt
HISTOGRAM_FROM = date(2026, 5, 1)
LEAD_FROM = date(2026, 6, 1)     # "recent rows" for the lead-time distribution
ROW_LIMIT = 50000
PACE_SECONDS = 1.0               # pause between requests, to stay well inside data.sf.gov's limits
# A dataset "keeps getting" permits when its sign-posting entries after the cutoff average at least this many a week.
ACTIVE_PER_WEEK = 5


# ---------------------------------------------------------------- dataset descriptions

@dataclass(frozen=True)
class Dataset:
    """Which columns of one dataset mean what (step 2 of the spec), and which permit types post signs."""
    key: str
    id: str
    name: str
    type_col: str
    entry_col: str
    start_col: str
    end_col: str
    status_col: Optional[str]
    number_col: str
    cnn_col: str
    sign_types: Optional[FrozenSet[str]]  # None = every row posts signs
    not_issued: FrozenSet[str]            # statuses that mean "no permit (yet)", excluded from the counts
    select: str
    where: Optional[str] = None


CLARITI = Dataset(
    key="clariti", id="fxfq-npa9", name="Public Works Permits issued by Clariti",
    type_col="permit_type", entry_col="issue_date", start_col="permit_start_date", end_col="permit_end_date",
    status_col="status", number_col="permit_number", cnn_col="cnn",
    sign_types=frozenset({"Street Space", "Temporary Occupancy"}),
    not_issued=frozenset({"Void", "Withdrawn", "Cancelled", "Pending", "Pending Review", "Awaiting Applicant Info", ""}),
    select="permit_number,cnn,permit_type,status,phase,issue_date,permit_start_date,permit_end_date",
)
SIGNS = Dataset(
    key="signs", id="sftu-nd43", name="Parking Signs / Street Space Permits",
    type_col="category", entry_col="datetimeentered", start_col="startdate", end_col="enddate",
    status_col=None, number_col="permitnumber", cnn_col="cnn",
    sign_types=None, not_issued=frozenset(),
    select="permitnumber,cnn,category,source,datetimeentered,startdate,enddate,starttime,endtime",
)
STREET_USE = Dataset(
    key="street_use", id="b6tj-gt35", name="Street-Use Permits (all statuses)",
    type_col="permit_type", entry_col="approved_date", start_col="permit_start_date", end_col="permit_end_date",
    status_col="status", number_col="permit_number", cnn_col="cnn",
    # Temporary occupancy, street space and excavation all post temporary tow-away signs (the tow feed's own
    # permits carry TOC and EXC numbers). Banners, tables/chairs, night noise etc. don't.
    sign_types=frozenset({"TempOccup", "StreetSpace", "AddlStSpac", "Excavation", "ExcStreet"}),
    not_issued=frozenset({"VOID", "WITHDRAW", "CANCELLED", "APPLCNT", "PLANCHK", "ONHOLD", ""}),
    select="permit_number,cnn,permit_type,status,approved_date,permit_start_date,permit_end_date",
    where=f"approved_date >= '{HISTOGRAM_FROM.isoformat()}T00:00:00'",
)
ACTIVE_STREET_USE = Dataset(
    key="active_street_use", id="x8nh-xzn6", name="Active Street-Use Permits",
    type_col=STREET_USE.type_col, entry_col=STREET_USE.entry_col, start_col=STREET_USE.start_col,
    end_col=STREET_USE.end_col, status_col=STREET_USE.status_col, number_col=STREET_USE.number_col,
    cnn_col=STREET_USE.cnn_col, sign_types=STREET_USE.sign_types, not_issued=STREET_USE.not_issued,
    select=STREET_USE.select,
)
PUBLIC_WORKS = [CLARITI, SIGNS, STREET_USE, ACTIVE_STREET_USE]
TOW = Dataset(
    key="tow", id=TOW_ID, name="SFMTA Enforced Temporary Tow Zones (reference)",
    type_col="source", entry_col="datetimeentered", start_col="startdate", end_col="enddate",
    status_col=None, number_col="permitnumber", cnn_col="cnn", sign_types=None, not_issued=frozenset(),
    select="permitnumber,cnn,source,datetimeentered,startdate,enddate",
    where=f"datetimeentered >= '{HISTOGRAM_FROM.isoformat()}T00:00:00'",
)


# ---------------------------------------------------------------- parsing

_FORMATS = ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%m/%d/%Y %H:%M:%S", "%m/%d/%Y")


def parse_when(text: Optional[str]) -> Optional[datetime]:
    """Every date shape these datasets use ('2026-09-14', '2026-09-21T08:50:25.000', '09/22/2026'), as SF local time."""
    if text is None:
        return None
    text = str(text).strip()
    if not text:
        return None
    text = text.split(".")[0] if "T" in text else text
    for fmt in _FORMATS:
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


def permit_numbers(text: Optional[str]) -> List[str]:
    """'26TOC-03680,' / 'TC#1616785' / 'A, B' -> normalized numbers (upper case, no 'TC#' prefix)."""
    if not text:
        return []
    out = []
    for part in re.split(r"[,;\s]+", str(text).upper()):
        part = part.strip().removeprefix("TC#")
        if part:
            out.append(part)
    return out


def cnns(text) -> List[str]:
    """A CNN column: a number ('11799000', '11799000.0') or a comma list (the tow feed)."""
    if text is None:
        return []
    out = []
    for part in str(text).split(","):
        part = part.strip()
        if part.endswith(".0"):
            part = part[:-2]
        if part:
            out.append(part)
    return out


@dataclass
class Permit:
    number: str
    type: str
    status: str
    entry: Optional[datetime]
    start: Optional[datetime]
    end: Optional[datetime]
    cnns: Tuple[str, ...]


def parse(ds: Dataset, rows: Sequence[dict]) -> Tuple[List[Permit], Counter]:
    """Rows -> permits, plus how many values of each date column could not be read (step 2: trustworthy columns)."""
    unreadable: Counter = Counter()
    out = []
    for r in rows:
        values = {}
        for col in (ds.entry_col, ds.start_col, ds.end_col):
            raw = r.get(col)
            values[col] = parse_when(raw)
            if raw not in (None, "") and values[col] is None:
                unreadable[col] += 1
        numbers = permit_numbers(r.get(ds.number_col))
        out.append(Permit(
            number=numbers[0] if numbers else "",
            type=str(r.get(ds.type_col) or "").strip(),
            status=str(r.get(ds.status_col) or "").strip() if ds.status_col else "",
            entry=values[ds.entry_col], start=values[ds.start_col], end=values[ds.end_col],
            cnns=tuple(cnns(r.get(ds.cnn_col))),
        ))
    return out, unreadable


def signposting(ds: Dataset, permits: Sequence[Permit]) -> List[Permit]:
    """The permits that plausibly post no-parking signs and were actually issued."""
    return [p for p in permits
            if (ds.sign_types is None or p.type in ds.sign_types) and (not ds.status_col or p.status not in ds.not_issued)]


# ---------------------------------------------------------------- analysis

def week_of(d: date) -> date:
    return d - timedelta(days=d.weekday())


def histogram(permits: Sequence[Permit], frm: date, to: date) -> Dict[date, int]:
    """Entries per Monday-starting week, frm..to inclusive, zero-filled."""
    weeks: Dict[date, int] = {}
    w = week_of(frm)
    while w <= to:
        weeks[w] = 0
        w += timedelta(days=7)
    for p in permits:
        if p.entry and frm <= p.entry.date() <= to:
            weeks[week_of(p.entry.date())] += 1
    return weeks


def after_cutoff(permits: Sequence[Permit], cutoff: date = TOW_CUTOFF) -> List[Permit]:
    return [p for p in permits if p.entry and p.entry.date() > cutoff]


@dataclass
class LeadTimes:
    n: int
    median_days: Optional[float]
    p10_days: Optional[float]
    p90_days: Optional[float]
    under_2_days: float  # share whose window starts less than 2 days (the app's default lead time) after entry
    negative: int        # window started before the entry (back-dated / renewals)


def lead_times(permits: Sequence[Permit], frm: date) -> LeadTimes:
    """(window start - entry) in days, for permits entered on or after [frm]. Date-only entries count from midnight."""
    days = sorted((p.start - p.entry).total_seconds() / 86400 for p in permits
                  if p.entry and p.start and p.entry.date() >= frm)
    if not days:
        return LeadTimes(0, None, None, None, 0.0, 0)
    positive = [d for d in days if d >= 0]

    def pct(q: float) -> Optional[float]:
        return positive[min(len(positive) - 1, int(q * len(positive)))] if positive else None
    return LeadTimes(
        n=len(days), median_days=statistics.median(positive) if positive else None,
        p10_days=pct(0.10), p90_days=pct(0.90),
        under_2_days=sum(1 for d in positive if d < 2) / len(positive) if positive else 0.0,
        negative=len(days) - len(positive),
    )


def covering(permits: Sequence[Permit], frm: datetime, to: datetime) -> List[Permit]:
    """Permits whose window [start, end] touches [frm, to). A date-only end means the whole of that day."""
    out = []
    for p in permits:
        if not p.start or not p.end:
            continue
        end = p.end + timedelta(days=1) if p.end.time() == datetime.min.time() else p.end
        if p.start < to and end > frm:
            out.append(p)
    return out


@dataclass
class CrossCheck:
    number: str
    found_in: List[str]  # dataset keys where the permit number matched
    cnn_window_in: List[str]  # dataset keys with a permit on the same CNN and an overlapping window (fallback)


def cross_check(live: Sequence[Permit], pools: Dict[str, List[Permit]]) -> List[CrossCheck]:
    """Step 6: each live tow zone by permit number, falling back to CNN + overlapping window."""
    by_number = {k: {p.number for p in ps if p.number} for k, ps in pools.items()}
    by_cnn: Dict[str, Dict[str, List[Permit]]] = {}
    for k, ps in pools.items():
        idx: Dict[str, List[Permit]] = {}
        for p in ps:
            for c in p.cnns:
                idx.setdefault(c, []).append(p)
        by_cnn[k] = idx
    out, seen = [], set()
    for z in live:
        key = (z.number, z.cnns)
        if key in seen:
            continue
        seen.add(key)
        found = [k for k in pools if z.number and z.number in by_number[k]]
        fallback = []
        if not found and z.start and z.end:
            for k in pools:
                candidates = [p for c in z.cnns for p in by_cnn[k].get(c, [])]
                if covering(candidates, z.start, z.end + timedelta(days=1)):
                    fallback.append(k)
        out.append(CrossCheck(z.number, found, fallback))
    return out


def number_kinds(numbers) -> Counter:
    """'26TOC-03680' -> 'TOC', 'TOC-26-01392' -> 'TOC-' (Clariti's shape), '1616951' -> '#' (sign-system id)."""
    kinds: Counter = Counter()
    for n in numbers:
        m = re.match(r"^\d{2}([A-Z]+)-\d+$", n)
        if m:
            kinds[m.group(1)] += 1
        elif re.match(r"^[A-Z]+-\d{2}-\d+$", n):
            kinds[n.split("-")[0] + "- (Clariti)"] += 1
        elif n.isdigit():
            kinds["#"] += 1
        else:
            kinds["other"] += 1
    return kinds


def verdict(after_by_dataset: Dict[str, int], weeks_after: float, unreadable_entry: Dict[str, bool]) -> Tuple[str, List[str]]:
    """CLARITI_HAS_NEW_PERMITS / NO_NEW_PERMITS_ANYWHERE / INCONCLUSIVE, plus the datasets behind it."""
    active = [k for k, n in after_by_dataset.items() if weeks_after > 0 and n / weeks_after >= ACTIVE_PER_WEEK]
    if active:
        return "CLARITI_HAS_NEW_PERMITS", active
    broken = [k for k, bad in unreadable_entry.items() if bad]
    if broken:
        return "INCONCLUSIVE", broken
    return "NO_NEW_PERMITS_ANYWHERE", []


# ---------------------------------------------------------------- network

_last_request = 0.0


def _token() -> str:
    path = repo_root() / "app" / "src" / "main" / "assets" / "datasf_app_token.txt"
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def get_json(url: str):
    global _last_request
    wait = PACE_SECONDS - (time.monotonic() - _last_request)
    if wait > 0:
        time.sleep(wait)
    request = urllib.request.Request(url)
    token = _token()
    if token:
        request.add_header("X-App-Token", token)  # never printed
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.loads(response.read().decode("utf-8"))
    finally:
        _last_request = time.monotonic()


def query(dataset: str, params: Dict[str, str]) -> list:
    return get_json(f"https://data.sf.gov/resource/{dataset}.json?" + urllib.parse.urlencode(params))


def fetch_rows(ds: Dataset, where: Optional[str] = None) -> list:
    params = {"$select": ds.select, "$limit": str(ROW_LIMIT)}
    clause = where or ds.where
    if clause:
        params["$where"] = clause
    rows = query(ds.id, params)
    if len(rows) >= ROW_LIMIT:
        raise RuntimeError(f"{ds.id}: hit the {ROW_LIMIT}-row limit, so the download may be incomplete")
    return rows


def download(today: date) -> Dict[str, list]:
    data: Dict[str, list] = {ds.key: fetch_rows(ds) for ds in PUBLIC_WORKS}
    data["tow"] = fetch_rows(TOW)
    data["tow_live"] = fetch_rows(TOW, where=f"enddate >= '{today.isoformat()}T00:00:00'")
    # Tow permits may have been approved long before May: look their numbers up in the full street-use history.
    numbers = sorted({n for key in ("tow", "tow_live") for r in data[key] for n in permit_numbers(r.get("permitnumber"))})
    extra: list = []
    for i in range(0, len(numbers), 50):
        chunk = ", ".join("'" + n.replace("'", "''") + "'" for n in numbers[i:i + 50])
        extra += fetch_rows(STREET_USE, where=f"permit_number in ({chunk})")
    data["street_use_tow_numbers"] = extra
    return data


def metadata(dataset: str) -> dict:
    return get_json(f"https://data.sf.gov/api/views/{dataset}.json")


# ---------------------------------------------------------------- report

def _fmt_days(value: Optional[float]) -> str:
    return "-" if value is None else f"{value:.1f}"


def report(data: Dict[str, list], today: date) -> int:
    parsed: Dict[str, List[Permit]] = {}
    signs: Dict[str, List[Permit]] = {}
    unreadable_entry: Dict[str, bool] = {}
    datasets = PUBLIC_WORKS + [TOW]
    for ds in datasets:
        permits, unreadable = parse(ds, data.get(ds.key, []))
        parsed[ds.key] = permits
        signs[ds.key] = signposting(ds, permits)
        unreadable_entry[ds.key] = bool(permits) and unreadable[ds.entry_col] > len(permits) // 2
        print(f"=== {ds.id}  {ds.name}: {len(permits)} row(s) downloaded" + (f" (where {ds.where})" if ds.where else ""))
        print(f"  columns: type={ds.type_col}  entry={ds.entry_col}  window={ds.start_col} -> {ds.end_col}  "
              f"location={ds.cnn_col}  status={ds.status_col or '(none)'}  number={ds.number_col}")
        if unreadable:
            print("  unreadable dates: " + ", ".join(f"{c} {n}" for c, n in unreadable.items()))
        types = Counter(p.type for p in permits)
        counted = ds.sign_types if ds.sign_types is not None else set(types)
        print("  permit types (* = counted as sign-posting): " +
              ", ".join(f"{'*' if t in counted else ''}{t or '?'} {n}" for t, n in types.most_common(12)))
        if ds.status_col:
            statuses = Counter(p.status for p in permits)
            print("  statuses (excluded as not issued: " + (", ".join(sorted(s or '(blank)' for s in ds.not_issued)) or "-") + "): " +
                  ", ".join(f"{s or '(blank)'} {n}" for s, n in statuses.most_common(12)))
        newest = max((p.entry for p in signs[ds.key] if p.entry), default=None)
        after = after_cutoff(signs[ds.key])
        print(f"  sign-posting, issued: {len(signs[ds.key])}; newest entry {newest:%Y-%m-%d %H:%M}" if newest else
              f"  sign-posting, issued: {len(signs[ds.key])}; no readable entry date")
        print(f"  entered after {TOW_CUTOFF}: {len(after)}" + (
            "  (" + ", ".join(f"{t or '?'} {n}" for t, n in Counter(p.type for p in after).most_common(6)) + ")" if after else ""))
        if ds.key == "clariti":
            phases = Counter(str(r.get("phase") or "") for r in data.get(ds.key, []))
            print(f"  rows in the 'Tow Sign Photo' phase (all types and dates): {phases.get('Tow Sign Photo', 0)}")
        print()

    # Step 3: weekly histogram, side by side.
    weeks = {k: histogram(signs[k], HISTOGRAM_FROM, today) for k in signs}
    keys = [ds.key for ds in datasets]
    print(f"Entry-date histogram of sign-posting permits, by week (Monday), {HISTOGRAM_FROM} -> {today}")
    print("  (street_use only holds permits approved since May; active_street_use is a current snapshot, so its old weeks"
          " are thin by construction)")
    print("  week        " + "".join(f"{k:>19}" for k in keys))
    for w in weeks[keys[0]]:
        mark = "  <- tow feed stops" if week_of(TOW_CUTOFF) == w else ""
        print(f"  {w}  " + "".join(f"{weeks[k][w]:>19}" for k in keys) + mark)
    print()

    # Step 4: lead times.
    print(f"Lead time (window start - entry), permits entered since {LEAD_FROM}, in days:")
    tow_lead = lead_times(signs["tow"], date(2026, 5, 1))
    for ds in datasets:
        lt = tow_lead if ds.key == "tow" else lead_times(signs[ds.key], LEAD_FROM)
        label = ds.key + (" (May-Jul)" if ds.key == "tow" else "")
        print(f"  {label:<22} n={lt.n:<6} median={_fmt_days(lt.median_days):<6} p10={_fmt_days(lt.p10_days):<6} "
              f"p90={_fmt_days(lt.p90_days):<7} <2 days: {lt.under_2_days:.0%}   window before entry: {lt.negative}")
    print("  (Clariti's issue_date is a date without a time, so its lead times are counted from midnight: up to a day long.)")
    print()

    # Step 5: next 7 days, citywide.
    now = datetime.combine(today, datetime.min.time())
    print("Sign-posting permits whose window touches the next 7 days, citywide:")
    for ds in PUBLIC_WORKS:
        print(f"  {ds.key:<22} {len(covering(signs[ds.key], now, now + timedelta(days=7)))}")
    print("  (street_use misses permits approved before May that are still running; active_street_use has them.)")
    print()

    # Step 6: live tow zones cross-checked.
    live, _ = parse(TOW, data.get("tow_live", []))
    pools = {k: parsed[k] for k in ("clariti", "signs", "street_use", "active_street_use")}
    extra, _ = parse(STREET_USE, data.get("street_use_tow_numbers", []))
    pools["street_use"] = pools["street_use"] + extra
    checks = cross_check(live, pools)
    by_number = sum(1 for c in checks if c.found_in)
    by_fallback = sum(1 for c in checks if not c.found_in and c.cnn_window_in)
    print(f"Live tow zones (end date today or later): {len(live)} row(s), {len(checks)} distinct permit/block(s)")
    print(f"  found by permit number: {by_number}; by CNN + overlapping window only: {by_fallback}; "
          f"not found: {len(checks) - by_number - by_fallback}")
    for k in pools:
        print(f"    {k:<22} by number {sum(1 for c in checks if k in c.found_in):>4}   "
              f"by CNN+window {sum(1 for c in checks if k in c.cnn_window_in):>4}")
    print(f"  live tow zones with no permit number: {sum(1 for c in checks if not c.number)} (a 'Verbal' entry has none)")
    print()

    # Which permit system fed the tow feed while it worked: its May-Jul permits, looked up by number.
    numbered = {}
    for p in parsed["tow"]:
        if p.number:
            numbered.setdefault(p.number, p)
    history = cross_check(list(numbered.values()), pools)
    print(f"Tow feed May-Jul: {len(parsed['tow'])} row(s), {len(numbered)} distinct permit number(s) "
          f"({sum(1 for p in parsed['tow'] if not p.number)} row(s) without one)")
    for k in pools:
        print(f"    {k:<22} found by number {sum(1 for c in history if k in c.found_in):>5}")
    print(f"    found nowhere: {sum(1 for c in history if not c.found_in)}")
    print("  tow permit-number kinds: " + ", ".join(f"{p} {n}" for p, n in number_kinds(numbered).most_common(8)))
    print()

    # Verdict.
    weeks_after = max((today - TOW_CUTOFF).days, 1) / 7
    after_counts = {ds.key: len(after_cutoff(signs[ds.key])) for ds in PUBLIC_WORKS if ds.key != "active_street_use"}
    word, which = verdict(after_counts, weeks_after, {k: v for k, v in unreadable_entry.items() if k != "tow"})
    print(f"VERDICT: {word}" + (f" ({', '.join(which)})" if which else ""))
    for k, n in after_counts.items():
        print(f"  {k:<22} {n} sign-posting permit(s) entered after {TOW_CUTOFF} ({n / weeks_after:.0f} a week)")
    return 0


# ---------------------------------------------------------------- exploratory modes

def describe(dataset: str) -> None:
    meta = metadata(dataset)
    updated = meta.get("rowsUpdatedAt")
    updated_text = datetime.fromtimestamp(updated, timezone.utc).strftime("%Y-%m-%d %H:%M UTC") if updated else "?"
    print(f"=== {dataset}  {meta.get('name')}  (rowsUpdatedAt {updated_text}, viewType {meta.get('viewType')})")
    for col in meta.get("columns", []):
        print(f"    {col.get('fieldName'):<40} {col.get('dataTypeName'):<16} {col.get('name')}")
    count = query(dataset, {"$select": "count(*) as n"})
    print(f"  rows: {count[0].get('n') if count else '?'}")
    print()


def survey(ds: Dataset, columns: Sequence[str]) -> None:
    print(f"=== {ds.id}  {ds.name}")
    span = {"$select": f"min({ds.entry_col}) as oldest, max({ds.entry_col}) as newest, count(*) as n"}
    if ds.where:
        span["$where"] = ds.where
    print(f"  {ds.entry_col}: {query(ds.id, span)}")
    for col in columns:
        params = {"$select": f"{col}, count(*) as n", "$group": col, "$order": "n DESC", "$limit": "25"}
        if ds.where:
            params["$where"] = ds.where
        print(f"  by {col}:")
        for r in query(ds.id, params):
            print(f"    {r.get('n'):>8}  {r.get(col)}")
    print()


SURVEY_COLUMNS = {"clariti": ["permit_type", "status", "phase"], "signs": ["category", "source", "signtype"],
                  "street_use": ["permit_type", "status"], "active_street_use": ["permit_type", "status"],
                  "tow": ["source"]}


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Do Public Works datasets carry the permits SFMTA's tow feed stopped getting?")
    parser.add_argument("--save-dir", help="also save the raw downloads here (public data)")
    parser.add_argument("--from-dir", help="read the saved downloads from here instead of downloading")
    parser.add_argument("--describe", action="store_true", help="print each dataset's columns")
    parser.add_argument("--survey", action="store_true", help="permit types, statuses and entry-date span per dataset")
    args = parser.parse_args(argv)
    today = datetime.now(SF).date() if SF else date.today()
    try:
        if args.describe:
            for ds in PUBLIC_WORKS + [TOW]:
                describe(ds.id)
            return 0
        if args.survey:
            for ds in PUBLIC_WORKS + [TOW]:
                survey(ds, SURVEY_COLUMNS[ds.key])
            return 0
        if args.from_dir:
            folder = Path(args.from_dir)
            data = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in folder.glob("*.json")}
        else:
            data = download(today)
            if args.save_dir:
                folder = Path(args.save_dir)
                folder.mkdir(parents=True, exist_ok=True)
                for k, v in data.items():
                    (folder / f"{k}.json").write_text(json.dumps(v), encoding="utf-8")
                print(f"Saved raw downloads to {folder}")
        return report(data, today)
    except Exception as exc:  # network, parsing, or a missing saved file
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
