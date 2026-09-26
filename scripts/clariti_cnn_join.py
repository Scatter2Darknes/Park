"""Clariti permit -> CNN join, prototype (docs/investigations/park-clariti-cnn-join-spec.md).

Clariti (fxfq-npa9) permits carry only a free-text address. This joins it to the city's address registry (EAS,
ramy-di5m) to get the block's CNN, cross-checks against the street centerline's address ranges (3psu-pn9h), works
out side of street, and tests the address -> block idea on old-system permits (b6tj-gt35) whose blocks are known.

    python scripts/clariti_cnn_join.py --save-dir C:\\temp\\clariti   # download (EAS is ~390k rows), then report
    python scripts/clariti_cnn_join.py --from-dir C:\\temp\\clariti   # re-run on the saved downloads
    python scripts/clariti_cnn_join.py --describe [--dataset ID]     # columns and a sample row
    python scripts/clariti_cnn_join.py --from-dir DIR --survey        # Clariti address shapes

No phone or emulator: public, citywide DataSF data only (no location is sent). Uses the app's DataSF token if
present (never printed). Report only; nothing in the app changes.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

from common import repo_root, setup_console

PACE_SECONDS = 1.0
_last_request = 0.0

CLARITI = "fxfq-npa9"
EAS = "ramy-di5m"
CENTERLINE = "3psu-pn9h"
SWEEP = "yhqp-riqs"


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
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read().decode("utf-8"))
    finally:
        _last_request = time.monotonic()


def query(dataset: str, params: Dict[str, str]) -> list:
    return get_json(f"https://data.sf.gov/resource/{dataset}.json?" + urllib.parse.urlencode(params))


def describe(dataset: str) -> None:
    meta = get_json(f"https://data.sf.gov/api/views/{dataset}.json")
    print(f"=== {dataset}: {meta.get('name')}  (rowsUpdatedAt={meta.get('rowsUpdatedAt')})")
    for col in meta.get("columns", []):
        print(f"   {col.get('fieldName'):32} {col.get('dataTypeName')}")
    count = query(dataset, {"$select": "count(*)"})
    print(f"   rows: {count}")
    sample = query(dataset, {"$limit": "1"})
    if sample:
        row = {k: v for k, v in sample[0].items() if not k.startswith(":@")}
        text = json.dumps(row)
        print("   sample:", text[:900])


PAGE = 50000
SELECTS = {
    "clariti": (CLARITI, "permit_number,permit_type,status,phase,street_name,permit_address,permit_zipcode,"
                         "permit_start_date,permit_end_date,cnn,location", "permit_number"),
    "eas": (EAS, "eas_baseid,address_number,address_number_suffix,street_name,street_type,cnn,unit_number,"
                 "zip_code,longitude,latitude,complete_landmark_name", "eas_fullid"),
    "centerline": (CENTERLINE, "cnn,street,st_type,streetname,f_st,t_st,lf_fadd,lf_toadd,rt_fadd,rt_toadd,"
                               "f_node_cnn,t_node_cnn,active,oneway,line", "cnn"),
    "sweep": (SWEEP, "cnn,corridor,limits,cnnrightleft,blockside,line", "blocksweepid"),
    "signs": ("sftu-nd43", "permitnumber,category,cnn,sideofstreet,location_1,address,startdate,enddate", "signid"),
    # Old street-use system: one row per permit per block, with the block's official CNN AND the permit's address.
    "street_use": ("b6tj-gt35", "permit_number,cnn,streetname,permit_type,permit_address,permit_start_date",
                   "permit_number,cnn"),
}
WHERES = {"street_use": "permit_type = 'TempOccup' AND permit_start_date >= '2026-01-01T00:00:00'"}


def fetch_all(key: str) -> list:
    dataset, select, order = SELECTS[key]
    rows: list = []
    while True:
        params = {"$select": select, "$order": order, "$limit": str(PAGE), "$offset": str(len(rows))}
        if key in WHERES:
            params["$where"] = WHERES[key]
        page = query(dataset, params)
        rows += page
        print(f"  {key}: {len(rows)} rows", file=sys.stderr)
        if len(page) < PAGE:
            return rows


def load(save_dir: Optional[str], from_dir: Optional[str]) -> Dict[str, list]:
    if from_dir:
        data = {}
        for k in SELECTS:
            path = Path(from_dir) / f"{k}.json"
            rows = json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
            # A centerline file cached before the node columns were added is fetched again.
            if rows is None or (k == "centerline" and not any("f_node_cnn" in r for r in rows[:50])):
                rows = fetch_all(k)
                path.write_text(json.dumps(rows), encoding="utf-8")
            data[k] = rows
        return data
    data = {k: fetch_all(k) for k in SELECTS}
    if save_dir:
        folder = Path(save_dir)
        folder.mkdir(parents=True, exist_ok=True)
        for k, v in data.items():
            (folder / f"{k}.json").write_text(json.dumps(v), encoding="utf-8")
        print(f"Saved raw downloads to {folder}", file=sys.stderr)
    return data


def shape(address: str) -> str:
    """'1020 UNION  ST' -> '9 A A'; shows the address patterns without listing every address."""
    out = []
    for tok in address.split():
        if tok.isdigit():
            out.append("9")
        elif re.fullmatch(r"\d+[A-Z]", tok):
            out.append("9A")
        elif re.fullmatch(r"\d+(ST|ND|RD|TH)", tok):
            out.append("9TH")
        elif re.fullmatch(r"\d+-\d+", tok):
            out.append("9-9")
        elif tok in ("&", "-", "/", "AND", "@"):
            out.append(tok)
        else:
            out.append("A")
    return " ".join(out)


def survey(data: Dict[str, list]) -> None:
    clariti = data["clariti"]
    print("Clariti types:", Counter(r.get("permit_type") for r in clariti).most_common())
    print("cnn non-null:", sum(1 for r in clariti if r.get("cnn")), " location non-null:",
          sum(1 for r in clariti if r.get("location")))
    shapes = Counter(shape(r.get("permit_address") or "") for r in clariti)
    print("Address shapes:")
    for s, n in shapes.most_common(40):
        ex = next(r.get("permit_address") for r in clariti if shape(r.get("permit_address") or "") == s)
        print(f"  {n:6}  {s:24} e.g. {ex!r}")
    last = Counter((r.get("permit_address") or "").split()[-1] if (r.get("permit_address") or "").split() else ""
                   for r in clariti)
    print("Clariti last token:", last.most_common(40))
    print("EAS street_type:", Counter(r.get("street_type") for r in data["eas"]).most_common(40))
    print("EAS suffix non-null:", sum(1 for r in data["eas"] if r.get("address_number_suffix")),
          " landmark:", sum(1 for r in data["eas"] if r.get("complete_landmark_name")),
          " cnn null:", sum(1 for r in data["eas"] if not r.get("cnn")))


# ---------------------------------------------------------------- normalizing

TYPE_ALIASES = {"STREET": "ST", "AVENUE": "AVE", "AV": "AVE", "BOULEVARD": "BLVD", "DRIVE": "DR", "COURT": "CT",
                "TERRACE": "TER", "PLACE": "PL", "LANE": "LN", "ROAD": "RD", "HIGHWAY": "HWY", "CIRCLE": "CIR",
                "ALLEY": "ALY", "PLAZA": "PLZ", "STAIRWAY": "STWY"}
KNOWN_TYPES = {"ST", "AVE", "BLVD", "DR", "WAY", "CT", "TER", "PL", "LN", "RD", "HWY", "CIR", "ALY", "PARK", "PLZ",
               "LOOP", "STWY", "HL", "ROW", "WALK", "XING", "TUNL", "PSGE"}


def norm_name(name: Optional[str]) -> str:
    """Upper case, single spaces, ordinals without leading zeros ('07TH' -> '7TH')."""
    words = (name or "").upper().replace(".", "").split()
    return " ".join(re.sub(r"^0+(\d)", r"\1", w) if re.fullmatch(r"\d+(ST|ND|RD|TH)", w) else w for w in words)


def norm_type(t: Optional[str]) -> Optional[str]:
    if not t:
        return None
    t = t.upper().strip().rstrip(".")
    return TYPE_ALIASES.get(t, t)


@dataclass(frozen=True)
class Parsed:
    kind: str                 # "address", or why it isn't one: "blank", "intersection", "range", "no_number"
    number: Optional[int] = None
    suffix: str = ""
    name: str = ""
    type: Optional[str] = None


def parse_address(raw: Optional[str]) -> Parsed:
    text = (raw or "").strip().upper()
    if not text:
        return Parsed("blank")
    if "&" in text or " AND " in text or "@" in text:
        return Parsed("intersection")
    if re.match(r"^\d+\s*-\s*\d+\s", text):
        return Parsed("range")
    m = re.match(r"^(\d+)([A-Z]?)\s+(.*)$", text)
    if not m:
        return Parsed("no_number")
    number, suffix, rest = int(m.group(1)), m.group(2), m.group(3).strip()
    # Clariti writes "NAME  TYPE" with two spaces; a single-space last word is a type only if it is a known one.
    parts = re.split(r"\s{2,}", rest)
    if len(parts) == 2:
        name, typ = parts
    else:
        words = rest.split()
        if len(words) > 1 and norm_type(words[-1]) in KNOWN_TYPES:
            name, typ = " ".join(words[:-1]), words[-1]
        else:
            name, typ = rest, None
    return Parsed("address", number, suffix, norm_name(name), norm_type(typ))


# ---------------------------------------------------------------- geometry

def _xy(lon: float, lat: float, lat0: float) -> Tuple[float, float]:
    return (lon * 111320.0 * math.cos(math.radians(lat0)), lat * 110540.0)


def side_of_line(coords: Sequence[Sequence[float]], lon: float, lat: float) -> Tuple[float, str]:
    """Distance (m) from the point to the polyline, and which side of the line's drawn direction it is on."""
    lat0 = lat
    p = _xy(lon, lat, lat0)
    best = (float("inf"), "?")
    for a, b in zip(coords, coords[1:]):
        ax, ay = _xy(a[0], a[1], lat0)
        bx, by = _xy(b[0], b[1], lat0)
        dx, dy = bx - ax, by - ay
        seg2 = dx * dx + dy * dy
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((p[0] - ax) * dx + (p[1] - ay) * dy) / seg2))
        cx, cy = ax + t * dx, ay + t * dy
        dist = math.hypot(p[0] - cx, p[1] - cy)
        if dist < best[0]:
            cross = dx * (p[1] - ay) - dy * (p[0] - ax)
            best = (dist, "L" if cross > 0 else "R")
    return best


def bearing(coords: Sequence[Sequence[float]]) -> float:
    """Compass bearing (deg) from the line's first point to its last."""
    (x0, y0), (x1, y1) = _xy(*coords[0], coords[0][1]), _xy(*coords[-1], coords[0][1])
    return (math.degrees(math.atan2(x1 - x0, y1 - y0)) + 360) % 360


def left_compass(b: float) -> str:
    """Which compass side is on the left of a line heading b degrees."""
    left = (b - 90) % 360
    return ["North", "East", "South", "West"][int(((left + 45) % 360) // 90)]


# ---------------------------------------------------------------- indexes

def _f(v) -> Optional[float]:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _cnn(v) -> str:
    return str(v or "").removesuffix(".0").strip()


@dataclass
class Segment:
    cnn: str
    name: str
    type: Optional[str]
    ranges: Dict[str, Tuple[int, int]]   # "L"/"R" -> (from, to)
    coords: list
    active: bool
    nodes: Tuple[str, ...] = ()


def index_centerline(rows: list) -> Dict[str, Segment]:
    out: Dict[str, Segment] = {}
    for r in rows:
        cnn = _cnn(r.get("cnn"))
        line = (r.get("line") or {}).get("coordinates") or []
        ranges = {}
        for side, a, b in (("L", "lf_fadd", "lf_toadd"), ("R", "rt_fadd", "rt_toadd")):
            lo, hi = _f(r.get(a)) or 0, _f(r.get(b)) or 0
            if lo or hi:
                ranges[side] = (int(min(lo, hi)), int(max(lo, hi)))
        active = r.get("active") in (True, "true", "1")
        nodes = tuple(n for n in (_cnn(r.get("f_node_cnn")), _cnn(r.get("t_node_cnn"))) if n)
        seg = Segment(cnn, norm_name(r.get("street")), norm_type(r.get("st_type")), ranges, line, active, nodes)
        if cnn not in out or (active and not out[cnn].active):
            out[cnn] = seg
    return out


def side_by_range(seg: Segment, n: int) -> Optional[str]:
    """Side whose address range holds n with the same odd/even as its ends; None if no side or both."""
    hits = [s for s, (lo, hi) in seg.ranges.items() if lo <= n <= hi and (lo % 2 == n % 2 or hi % 2 == n % 2)]
    return hits[0] if len(hits) == 1 else None


@dataclass
class EasAddr:
    cnns: set
    points: list
    zips: set


def index_eas(rows: list) -> Tuple[Dict[tuple, EasAddr], Dict[tuple, EasAddr], Dict[tuple, EasAddr]]:
    """Base addresses (units collapsed) keyed (number, suffix, name, type), (number, name, type), (number, name)."""
    full: Dict[tuple, EasAddr] = {}
    nosuf: Dict[tuple, EasAddr] = {}
    notype: Dict[tuple, EasAddr] = {}
    for r in rows:
        num = _f(r.get("address_number"))
        if num is None:
            continue
        n, name, typ = int(num), norm_name(r.get("street_name")), norm_type(r.get("street_type"))
        suf = (r.get("address_number_suffix") or "").strip().upper()
        cnn = _cnn(r.get("cnn"))
        lon, lat = _f(r.get("longitude")), _f(r.get("latitude"))
        for idx, key in ((full, (n, suf, name, typ)), (nosuf, (n, name, typ)), (notype, (n, name))):
            a = idx.setdefault(key, EasAddr(set(), [], set()))
            a.cnns.add(cnn)
            a.zips.add(_cnn(r.get("zip_code")))
            if lon is not None and lat is not None and len(a.points) < 5:
                a.points.append((lon, lat, cnn))
    return full, nosuf, notype


def index_ranges(segs: Dict[str, Segment]) -> Dict[Tuple[str, Optional[str]], List[Segment]]:
    by: Dict[Tuple[str, Optional[str]], List[Segment]] = defaultdict(list)
    for s in segs.values():
        if s.active and s.ranges:
            by[(s.name, s.type)].append(s)
            by[(s.name, None)].append(s)
    return by


# ---------------------------------------------------------------- the join

@dataclass
class Result:
    row: dict
    parsed: Parsed
    outcome: str                 # "single", "multi", "none", or the parse failure kind
    tier: str = ""               # which key matched
    cnns: Tuple[str, ...] = ()
    point: Optional[Tuple[float, float]] = None
    range_cnns: Tuple[str, ...] = ()


def join(permit: dict, full, nosuf, notype, by_range) -> Result:
    p = parse_address(permit.get("permit_address"))
    if p.kind != "address":
        return Result(permit, p, p.kind)
    hit, tier = None, ""
    if p.type:
        hit, tier = full.get((p.number, p.suffix, p.name, p.type)), "exact"
        if hit is None and p.suffix:
            hit, tier = nosuf.get((p.number, p.name, p.type)), "suffix dropped"
    if hit is None:
        hit, tier = notype.get((p.number, p.name)), ("type missing" if not p.type else "type ignored")
    # Independent second route: which centerline blocks' address ranges hold this number on this street?
    segs = by_range.get((p.name, p.type), []) if p.type else by_range.get((p.name, None), [])
    rc = tuple(sorted({s.cnn for s in segs if side_by_range(s, p.number)}))
    if hit is None:
        return Result(permit, p, "none", "", (), None, rc)
    cnns = tuple(sorted(hit.cnns))
    point = hit.points[0][:2] if hit.points else None
    return Result(permit, p, "single" if len(cnns) == 1 else "multi", tier, cnns, point, rc)


def pct(n: int, d: int) -> str:
    return f"{n:,} ({100.0 * n / d:.1f}%)" if d else f"{n:,}"


def report(data: Dict[str, list], today: date, samples: int) -> int:
    segs = index_centerline(data["centerline"])
    full, nosuf, notype = index_eas(data["eas"])
    by_range = index_ranges(segs)
    sweep_cnns = {_cnn(r.get("cnn")) for r in data["sweep"]}
    permits = [r for r in data["clariti"] if r.get("permit_number")]
    results = [join(r, full, nosuf, notype, by_range) for r in permits]
    rnd = random.Random(20260926)

    def section(title: str) -> None:
        print(f"\n## {title}")

    print(f"# Clariti -> CNN join ({today.isoformat()})")
    print(f"Clariti rows {len(permits):,}; EAS base addresses {len(nosuf):,} (from {len(data['eas']):,} rows); "
          f"centerline segments {len(segs):,}; sweeping CNNs {len(sweep_cnns):,}")

    groups = {"all": results,
              "live (ends today or later)": [r for r in results if (r.row.get("permit_end_date") or "") >= today.isoformat()]}
    for t in ("Temporary Occupancy", "Street Space", "Sidewalk Repair", "Inspection ROW conformity"):
        groups[t] = [r for r in results if r.row.get("permit_type") == t]
    section("Outcome by group")
    print("| group | rows | single CNN | several CNNs | no match | not an address |")
    print("|---|---|---|---|---|---|")
    for g, rs in groups.items():
        c = Counter(r.outcome for r in rs)
        bad = len(rs) - c["single"] - c["multi"] - c["none"]
        print(f"| {g} | {len(rs):,} | {pct(c['single'], len(rs))} | {pct(c['multi'], len(rs))} | "
              f"{pct(c['none'], len(rs))} | {pct(bad, len(rs))} |")

    section("How single matches were found (tier)")
    for tier, n in Counter(r.tier for r in results if r.outcome == "single").most_common():
        print(f"  {tier:16} {n:,}")
    section("Not an address")
    for k, n in Counter(r.outcome for r in results if r.parsed.kind != "address").most_common():
        ex = [r.row.get("permit_address") for r in results if r.outcome == k][:4]
        print(f"  {k:14} {n:,}  e.g. {ex}")

    section("Blank-address rows")
    blanks = [r.row for r in results if r.outcome == "blank"]
    live_blanks = [b for b in blanks if (b.get("permit_end_date") or "") >= today.isoformat()]
    print(f"  {len(blanks)} rows, {len(live_blanks)} live; with street_name: {sum(1 for b in blanks if b.get('street_name'))}")
    print(f"  by type: {Counter(b.get('permit_type') for b in blanks).most_common()}")
    print(f"  by status: {Counter(b.get('status') for b in blanks).most_common(8)}")
    print(f"  by phase: {Counter(b.get('phase') for b in blanks).most_common(8)}")
    print(f"  issue/start years: {Counter((b.get('permit_start_date') or '')[:7] for b in blanks).most_common(8)}")

    section("Cross-check of single matches")
    singles = [r for r in results if r.outcome == "single"]
    name_ok = in_range = range_agree = in_sweep = 0
    far: List[Tuple[float, Result]] = []
    name_bad: List[Result] = []
    for r in singles:
        cnn = r.cnns[0]
        seg = segs.get(cnn)
        if seg and seg.name == r.parsed.name:
            name_ok += 1
        else:
            name_bad.append(r)
        if seg and side_by_range(seg, r.parsed.number):
            in_range += 1
        if cnn in r.range_cnns:
            range_agree += 1
        if cnn in sweep_cnns:
            in_sweep += 1
        if seg and seg.coords and r.point:
            d, _ = side_of_line(seg.coords, *r.point)
            far.append((d, r))
    print(f"  CNN's centerline street = permit's street: {pct(name_ok, len(singles))}")
    print(f"  house number inside that CNN's address range (right parity): {pct(in_range, len(singles))}")
    print(f"  centerline-range join finds the same CNN: {pct(range_agree, len(singles))}")
    print(f"  CNN is one the app has (sweeping data): {pct(in_sweep, len(singles))}")
    ds = sorted(d for d, _ in far)
    if ds:
        print(f"  EAS point -> CNN centerline distance: median {ds[len(ds)//2]:.0f} m, p90 {ds[int(len(ds)*.9)]:.0f} m, "
              f"max {ds[-1]:.0f} m, over 60 m: {sum(1 for d in ds if d > 60)}")
    for r in name_bad[:8]:
        seg = segs.get(r.cnns[0])
        print(f"    street differs: {r.row.get('permit_address')!r} -> cnn {r.cnns[0]} "
              f"({seg.name + ' ' + (seg.type or '') if seg else 'not in centerline'})")
    for d, r in sorted(far, key=lambda x: -x[0])[:5]:
        seg = segs[r.cnns[0]]
        print(f"    far: {r.row.get('permit_address')!r} -> cnn {r.cnns[0]} {seg.name} {seg.type} "
              f"({seg.ranges}) {d:.0f} m")

    section("No-match rows, by likely reason")
    nones = [r for r in results if r.outcome == "none"]
    reasons: Dict[str, List[Result]] = defaultdict(list)
    names_in_eas = {k[1] for k in notype}
    for r in nones:
        if r.parsed.name not in names_in_eas:
            reasons["street name not in EAS"].append(r)
        elif r.range_cnns:
            reasons["number not in EAS, but a centerline range holds it"].append(r)
        else:
            reasons["number not in EAS nor any range"].append(r)
    for k, rs in sorted(reasons.items(), key=lambda kv: -len(kv[1])):
        ex = [r.row.get("permit_address") for r in rnd.sample(rs, min(6, len(rs)))]
        print(f"  {k}: {len(rs):,}  e.g. {ex}")
    rescued = sum(1 for r in nones if len(r.range_cnns) == 1)
    print(f"  of which the centerline ranges give exactly one CNN: {pct(rescued, len(nones))}")

    section("Several-CNN rows")
    multis = [r for r in results if r.outcome == "multi"]
    for r in multis[:10]:
        names = [f"{c}:{segs[c].name} {segs[c].type}" if c in segs else c for c in r.cnns]
        print(f"  {r.row.get('permit_address')!r} [{r.tier}] -> {names}")

    section(f"Random single matches ({samples}) for hand-checking")
    for r in rnd.sample(singles, min(samples, len(singles))):
        seg = segs.get(r.cnns[0])
        where = f"{seg.name} {seg.type}, {seg.ranges}" if seg else "?"
        print(f"  {r.row.get('permit_type', '')[:10]:10} {r.row.get('permit_address')!r:34} -> cnn {r.cnns[0]:9} {where}")

    side_report(results, segs, data["sweep"], data["eas"], rnd)

    section("Ground truth: Clariti permits that also appear in the parking-signs dataset (sftu-nd43)")
    def key(number: str) -> str:
        """'TOC-26-01234' and '26TOC-01234' -> 'TOC 26 1234'."""
        n = (number or "").strip().upper().rstrip(",")
        m = re.fullmatch(r"([A-Z]+)-(\d\d)-0*(\d+)", n) or None
        if m:
            return f"{m.group(1)} {m.group(2)} {m.group(3)}"
        m = re.fullmatch(r"(\d\d)([A-Z]+)-0*(\d+)", n)
        return f"{m.group(2)} {m.group(1)} {m.group(3)}" if m else n

    by_number = {key(r.row.get("permit_number", "")): r for r in results}
    signs = data.get("signs") or []
    print(f"  signs rows {len(signs)}; permit number shapes: "
          f"{Counter(re.sub(r'[0-9]', '9', (s.get('permitnumber') or '')) for s in signs).most_common(8)}")
    c = Counter()
    for s in signs:
        r = by_number.get(key(s.get("permitnumber") or ""))
        if not r:
            c["not in Clariti"] += 1
            continue
        if r.outcome != "single":
            c[f"in Clariti, join {r.outcome}"] += 1
            continue
        same = _cnn(s.get("cnn")) == r.cnns[0]
        c["same CNN" if same else "different CNN"] += 1
        print(f"    {s.get('permitnumber')}: {r.row.get('permit_address')!r} -> {r.cnns[0]}; signs on "
              f"{_cnn(s.get('cnn'))} side {s.get('sideofstreet')} ({s.get('location_1')})")
    print(f"  {dict(c)}")

    section("Method check on the old street-use system (b6tj-gt35 TempOccup since 2026-01-01): address -> CNN "
            "vs the blocks the permit was issued for")
    blocks: Dict[str, set] = defaultdict(set)
    addr: Dict[str, str] = {}
    for r in data.get("street_use") or []:
        n = r.get("permit_number")
        if n and r.get("cnn"):
            blocks[n].add(_cnn(r.get("cnn")))
            if r.get("permit_address"):
                addr[n] = r["permit_address"]
    # Block adjacency through shared intersection nodes, to measure how far off a wrong-street address is.
    by_node: Dict[str, set] = defaultdict(set)
    for s in segs.values():
        for nd in s.nodes:
            by_node[nd].add(s.cnn)

    def neighbours(cnn: str) -> set:
        s = segs.get(cnn)
        return set().union(*(by_node[nd] for nd in s.nodes)) - {cnn} if s and s.nodes else set()

    def hops(a: str, targets: set) -> str:
        ring1 = neighbours(a)
        if ring1 & targets:
            return "adjacent (shares a corner)"
        ring2 = set().union(*(neighbours(x) for x in ring1)) if ring1 else set()
        return "two blocks away" if ring2 & targets else "further"

    distance = Counter()
    covered_by_ring = Counter()
    c = Counter()
    nblocks = Counter()
    misses: List[str] = []
    for n, a in addr.items():
        res = join({"permit_address": a}, full, nosuf, notype, by_range)
        if res.outcome != "single":
            c[f"join {res.outcome}"] += 1
            continue
        k = len(blocks[n])
        nblocks["1 block" if k == 1 else "2 blocks" if k == 2 else "3+ blocks"] += 1
        ring = {res.cnns[0]} | neighbours(res.cnns[0])
        covered_by_ring["all permit blocks" if blocks[n] <= ring else
                        "some" if blocks[n] & ring else "none"] += 1
        if res.cnns[0] not in blocks[n]:
            distance[hops(res.cnns[0], blocks[n])] += 1
        if res.cnns[0] in blocks[n]:
            c["address block is one of the permit's blocks"] += 1
            if k == 1:
                c["  ...and the permit covers only that block"] += 1
        else:
            c["address block NOT among the permit's blocks"] += 1
            if len(misses) < 8:
                names = [f"{b}:{segs[b].name}" if b in segs else b for b in sorted(blocks[n])]
                misses.append(f"{n} {a!r} -> {res.cnns[0]}:{segs[res.cnns[0]].name if res.cnns[0] in segs else '?'}"
                              f"; permit blocks {names}")
    print(f"  permits with an address: {len(addr):,} (of {len(blocks):,}); address formats: "
          f"{Counter(shape(a) for a in addr.values()).most_common(5)}")
    for k, v in c.items():
        print(f"  {k}: {v:,}")
    print(f"  blocks per matched permit: {dict(nblocks)}")
    print(f"  when the address block is wrong, the nearest real block is: {dict(distance)}")
    print(f"  address block + every block sharing a corner with it covers: {dict(covered_by_ring)}")
    for m in misses:
        print(f"    {m}")
    return 0


def side_report(results: List[Result], segs: Dict[str, Segment], sweep: list, eas: list, rnd: random.Random) -> None:
    print("\n## Side of street")
    # 1. Do the centerline's L/R address ranges follow one odd/even convention?
    conv = Counter()
    for s in segs.values():
        if s.active and "L" in s.ranges and "R" in s.ranges:
            lp, rp = s.ranges["L"][0] % 2, s.ranges["R"][0] % 2
            conv["L odd, R even" if (lp, rp) == (1, 0) else "L even, R odd" if (lp, rp) == (0, 1) else "same parity"] += 1
    print(f"  centerline segments with both ranges: {dict(conv)}")

    # 2. Range side vs geometry, on EAS points (every base address, not just permits).
    agree = total = 0
    for r in rnd.sample(eas, min(60000, len(eas))):
        seg = segs.get(_cnn(r.get("cnn")))
        num, lon, lat = _f(r.get("address_number")), _f(r.get("longitude")), _f(r.get("latitude"))
        if not seg or not seg.coords or num is None or lon is None:
            continue
        rs = side_by_range(seg, int(num))
        if rs is None:
            continue
        d, geo = side_of_line(seg.coords, lon, lat)
        if d > 80:
            continue
        total += 1
        agree += rs == geo
    print(f"  EAS sample: range side (from odd/even) = side of the address point from the centerline geometry: "
          f"{pct(agree, total)}")

    # 3. Sweeping data: does its cnnrightleft mean 'left/right of its own drawn line'? (blockside vs bearing)
    ok = n = 0
    for r in sweep:
        coords = (r.get("line") or {}).get("coordinates") or []
        side, bs = r.get("cnnrightleft"), (r.get("blockside") or "")
        if len(coords) < 2 or side not in ("L", "R") or bs not in ("North", "South", "East", "West"):
            continue
        b = bearing(coords)
        want = left_compass(b) if side == "L" else left_compass((b + 180) % 360)
        n += 1
        ok += want == bs
    print(f"  sweeping rows with a plain compass blockside: cnnrightleft = side of its own line: {pct(ok, n)}")

    # 4. Is the sweeping line drawn in the same direction as the centerline for the same CNN?
    same = opp = 0
    for r in sweep:
        seg = segs.get(_cnn(r.get("cnn")))
        coords = (r.get("line") or {}).get("coordinates") or []
        if not seg or len(seg.coords) < 2 or len(coords) < 2:
            continue
        diff = abs((bearing(coords) - bearing(seg.coords) + 180) % 360 - 180)
        same += diff < 45
        opp += diff > 135
    print(f"  sweeping line vs centerline direction, same CNN: same {same:,}, opposite {opp:,}")

    # 5. End to end on permits: range side vs geometric side of the permit's EAS point vs the app's sweep line.
    sweep_lines: Dict[str, list] = {}
    for r in sweep:
        coords = (r.get("line") or {}).get("coordinates") or []
        if len(coords) >= 2:
            sweep_lines.setdefault(_cnn(r.get("cnn")), coords)
    c = Counter()
    for r in results:
        if r.outcome != "single" or not r.point:
            continue
        seg = segs.get(r.cnns[0])
        rs = side_by_range(seg, r.parsed.number) if seg else None
        line = sweep_lines.get(r.cnns[0])
        if rs is None:
            c["no range side"] += 1
            continue
        if not line:
            c["CNN not in sweeping data"] += 1
            continue
        _, geo = side_of_line(line, *r.point)
        c["agree" if geo == rs else "disagree"] += 1
    print(f"  permits: range side vs side of the EAS point from the app's sweeping line: {dict(c)}")


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Clariti permit -> CNN join prototype")
    parser.add_argument("--describe", action="store_true")
    parser.add_argument("--dataset", action="append", help="with --describe: this dataset id instead")
    parser.add_argument("--survey", action="store_true")
    parser.add_argument("--save-dir")
    parser.add_argument("--from-dir")
    parser.add_argument("--samples", type=int, default=15)
    args = parser.parse_args(argv)
    if args.describe:
        for ds in (args.dataset or [CLARITI, EAS, CENTERLINE, SWEEP]):
            describe(ds)
        return 0
    data = load(args.save_dir, args.from_dir)
    if args.survey:
        survey(data)
        return 0
    return report(data, date.today(), args.samples)


if __name__ == "__main__":
    sys.exit(main())
