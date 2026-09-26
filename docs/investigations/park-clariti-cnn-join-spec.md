# Spec: Clariti Permit → CNN Join (Investigation)

## Status

**Investigation, not yet committed to.** This spec is for Claude Code to prototype and report back on — feasibility, match rate, and data quality — before deciding whether to wire it into `PermitSource` for real.

## Background

`PermitSource` (v1.06) currently ingests only the old street-use system's TempOccup permits, matched by CNN/block. Those permits have dates but no reliable hours, so they already only produce "check signs" wording rather than a hard deadline (see `PERMIT_ADVANCE`, the 48h heads-up).

Separately, DataSF now publishes **Department of Public Works Permits issued by Clariti** (`fxfq-npa9`), the newer permitting system built on Salesforce. It covers four permit types — Street Space (~6,200 rows), Temporary Occupancy (~1,400), Sidewalk Repair (~520), Inspection ROW conformity (~150) — refreshed roughly every 2 days. It is **not currently used by Park**.

## The blocker

Clariti's `cnn` and `location` columns exist in the schema but are **null on every row** in the current export. The dataset only gives a free-text `permit_address` (e.g. `"1020 UNION  ST"`, note double space) and a separately parsed `street_name`. There is no way to place a Clariti permit on the map as-is.

## The proposed fix: join against EAS

DataSF's **Addresses with Units – Enterprise Addressing System** (`ramy-di5m`) is the city's master address registry (~388,550 rows, refreshes nightly) and carries a `cnn` field directly on every address row. The plan is to join Clariti's `permit_address` against EAS to recover a CNN for each permit.

### What this join can and can't give us

- **Can give us:** CNN (street segment) — enough to say "there's a permit somewhere on this block."
- **Cannot give us:** `cnnRightLeft` (side of street). EAS ties an address to a segment, not a specific curb side. This would need to be *inferred* from SF's odd/even street-numbering convention, not matched directly — and that inference needs to be spot-checked against known blocks before being trusted, the same way past "trust the feed" assumptions in this app have burned us before (see `learnings.md` — RPP pagination, the SFMTA tow feed).
- **Does not touch timing:** Clariti's `permit_start_date`/`permit_end_date` are date-only, same limitation TempOccup already has. This investigation is scoped to *location* only.

## What to build (prototype, not production)

1. **Fetch both datasets** as static exports for offline analysis — no need to wire into the app's sync pipeline yet:
   - Clariti: `https://data.sf.gov/api/v3/views/fxfq-npa9/export.csv?accessType=DOWNLOAD`
   - EAS: `https://data.sfgov.org/resource/ramy-di5m.json` (Socrata SODA — confirm current domain per the `data.sf.gov` migration note in `learnings.md`; paginate)

2. **Normalize addresses on both sides** before matching:
   - Clariti's `permit_address` has irregular internal spacing (e.g. `"1020 UNION  ST"`) and street type appended to the number+street string
   - EAS parses address number, street name, and street type into separate fields already — Clariti's raw string needs to be split the same way (number / name / type) before comparing
   - Normalize case, strip extra whitespace, standardize common abbreviations (ST/STREET, AVE/AVENUE, etc.) on both sides

3. **Join** on (address number, street name, street type) → pull `cnn` from the matching EAS row.

4. **Report back, don't ship:**
   - What fraction of Clariti rows get a confident single-match CNN?
   - What fraction get zero matches, and why (spot-check a sample — abbreviation mismatches? intersections/no-number addresses? Clariti addresses outside EAS's coverage?)
   - What fraction get multiple ambiguous EAS matches for the same address string?
   - For a small hand-picked sample of matched rows, sanity-check the resulting CNN against the actual street (does `1020 UNION ST` really land on Union St's centerline segment?)

5. **Separately, sketch (don't build) the side-of-street inference**: given a permit's house number and the matched CNN's two `cnnRightLeft` values, propose the odd/even → side mapping and flag how confident that logic actually is — this is the part most likely to be wrong silently.

## Explicitly out of scope for this pass

- Wiring the join into `StreetDataSyncCenter` or `CurbSources`
- Any UI/banner changes
- Handling permit types beyond what's already relevant to `PermitSource`'s existing wording model
- Solving the "no reliable hours" problem — not in scope, may never be solvable from this data

## Deliverable

A short written report (matches found / ambiguous / unmatched, with real examples of each) plus the side-of-street inference sketch, so Z can decide whether the match rate and reliability are good enough to justify integrating this into `PermitSource` for real.

---

## Findings (2026-09-26)

Run: `python scripts/clariti_cnn_join.py --save-dir <scratch>` then `--from-dir <scratch>` (public citywide data, no
device, no app code changed). Data as downloaded today: Clariti 8,284 rows, EAS 388,619 rows (219,356 base addresses
once units are collapsed), street centerline `3psu-pn9h` 17,170 segments, sweeping `yhqp-riqs` 37,878 rows.

### Corrections to the spec's assumptions
- `learnings.md` doesn't exist in the repo, so the domain note couldn't be checked. The app itself (`DataSFApi.kt`)
  and the Part A script use `https://data.sf.gov/resource/…`, and every dataset here answered there.
- Confirmed: Clariti's `cnn` and `location` are null on all 8,284 rows.
- Street types needn't be split out of Clariti's string by guesswork: Clariti writes **two spaces** between name and
  type (`"572 SAN JOSE  AVE"`), which separates multi-word names cleanly. 84% of rows have that shape.
- A better tool exists for side of street than an odd/even convention: the centerline dataset `3psu-pn9h` has
  **left and right address ranges for every CNN** (`lf_fadd/lf_toadd/rt_fadd/rt_toadd`). See below: a fixed
  "odd = left" rule would be wrong on about 1 block in 9.

### 1. Match rate (address -> EAS -> CNN)
| group | rows | one CNN | several CNNs | no match | not an address |
|---|---|---|---|---|---|
| all | 8,284 | **7,902 (95.4%)** | 0 | 98 (1.2%) | 284 (3.4%) |
| live (ends today or later) | 2,343 | **2,279 (97.3%)** | 0 | 26 (1.1%) | 38 (1.6%) |
| Temporary Occupancy | 1,421 | 1,162 (81.8%) | 0 | 35 (2.5%) | 224 (15.8%) |
| Street Space | 6,189 | 6,084 (98.3%) | 0 | 53 (0.9%) | 52 (0.8%) |
| Sidewalk Repair | 524 | 507 (96.8%) | 0 | 9 (1.7%) | 8 (1.5%) |
| Inspection ROW conformity | 149 | 148 (99.3%) | 0 | 1 | 0 |

How the single matches were found: exact number + suffix + name + type 7,719; street type missing in Clariti
(`"2320 BROADWAY"`, `"1527A 10TH"`) 148; letter suffix not in EAS, so dropped (`"164A DIAMOND  ST"` -> 164) 23;
type disagreed and was ignored 12. Normalizing is just upper-casing, collapsing spaces and `07TH` -> `7TH`.

**Ambiguous: none.** EAS gives every base address exactly one CNN, so the same address string never produced two.

**Not an address (284):** 279 **blank** (`permit_address` and `street_name` both empty, so unrecoverable; 220 of
them are Temporary Occupancy, 233 already expired, 36 live), 3 intersections (`"3600 JACKSON  ST & SPRUCE ST"`),
1 range (`"2200 - 2299 POST  ST"`), 1 with no number (`"OCTAVIA  ST"`). The blanks are why Temporary Occupancy, the
type `PermitSource` cares about, has the lowest rate.

**No match (98), spot-checked:**
- 61: the number isn't a registered address, but a centerline range holds it (`"1501 SUNNYDALE  AVE"`,
  `"3334 SACRAMENTO  ST"`, `"175 04TH  ST"`, `"3300 16TH  ST"`). Construction sites and vacant lots, typically.
- 36: the street name is written differently: Clariti puts the direction inside the name (`"455 MISSION BAY SOUTH
  BLVD"`, `"441 BUENA VISTA EAST AVE"`, `"320 WILLARD NORTH ST"`, `"9 25TH NORTH AVE"`). A small alias table would fix
  these.
- 1: nothing anywhere (`"601 WILLOW  ST"`).
- Falling back to the centerline ranges (section 2) gives exactly one CNN for 57 of the 98.

### 2. Is the CNN right? (sanity checks on all 7,902 single matches)
- The CNN's centerline street name equals the permit's street: **100%**.
- The house number lies inside that CNN's address range, on a side of the right parity: **98.8%**.
- An **independent second join**, from the centerline's address ranges alone (no EAS), finds the same CNN: **98.8%**.
- The EAS address point is a median 25 m from the CNN's centerline (p90 31 m, i.e. a building's frontage). 37 are over
  60 m: large parcels whose address point sits deep in the lot (`"50 FRIDA KAHLO  WAY"` = City College, 219 m;
  `"1001 POTRERO  AVE"` = SF General, 140 m). The block is still the right street.
- The CNN is one the app knows (it's in the sweeping data, which is where the app's parked CNN comes from,
  `PermitAlerts.kt:120`): **89.7%**. The rest are blocks without sweeping, which the app can't place a car on either,
  so they're not a new blind spot.

Hand-checked examples (random draw): `"1650 MISSION  ST"` -> 9110000 Mission St, 1600–1667; `"800 CHESTNUT  ST"` ->
3961000 Chestnut St, 800–899; `"2126 24TH  ST"` -> 1324000 24th St, 2100–2199; `"392 SAN JOSE  AVE"` -> 11432000
San Jose Ave, 321–399; `"1244 BROADWAY"` -> 3165000 Broadway, 1212–1298. All on the right street and block range.

### 3. The real risk: the permit's address is not always where its signs go
Clariti has no ground truth for sign locations (no Clariti permit number appears in the parking-signs dataset
`sftu-nd43`, even after normalizing `26TOC-01234` <-> `TOC-26-01234`). But the **old** street-use system
(`b6tj-gt35`, TempOccup since 2026-01-01) has both a `permit_address` **and** the official CNN of every block the permit
covers. Running the same address -> CNN method on its 692 permits that have an address:

| | permits |
|---|---|
| address block is one of the permit's blocks | 540 (79%) |
| ...and the permit covers only that block | 466 (68%) |
| **address block is not among the permit's blocks** | **147 (21%)** |
| no match | 5 |

The 147 misses are applicants' office addresses and corner lots: `"1 DR CARLTON B GOODLETT PL"` (City Hall) for
permits on Grove/Larkin/McAllister and on Van Ness; `"1 MARKET ST"` for a permit on Spear; `"555 PINE ST"` for two
Bush blocks; `"1801 CASTRO ST"` for Cesar Chavez; `"601 DOLORES ST"` for 19th St. Of the 147, the real block
**shares a corner** with the address block in 100, is two blocks away in 33, and is further in 14. Also 89 permits
(13%) cover 2+ blocks, and an address can only ever name one.

Taking the **address block plus every block that shares a corner with it** covers all of a permit's blocks for 621
of 687 (90%), some for 19, none for 47 (7%).

Caveats: the old system is a proxy (only 21% of its TempOccup permits even have an address, and Clariti's
applicants may fill the field differently). Street Space permits (75% of Clariti's rows) are building-site permits,
where "the address is the site" is more plausible than for Temporary Occupancy, but that's untested.

**This is the silent failure to worry about**, not the address lookup: a join that is right about the address can
still give "no permit on your block" while signs are up around the corner. That is the false-safe direction.

### 4. Side-of-street sketch (not built)
Proposal: **don't use a fixed odd/even rule; read the side from the CNN's own centerline ranges**:
`side = "L" if n is in [lf_fadd, lf_toadd] with matching parity, "R" if in [rt_fadd, rt_toadd]; else unknown`.

How confident, measured:
- A fixed convention would be wrong on many blocks: of 13,204 segments with both ranges, 11,669 have odd numbers on
  the left and **1,533 (12%) have even on the left** (e.g. Cole St 700 block: L 730–798, R 751–799).
- Range-derived side = geometric side of the EAS address point relative to the centerline: **99.3%** (58,377 of a
  60,000-address sample).
- The sweeping data's `cnnrightleft` means left/right of its own drawn line: 100% of the 26,825 rows with a plain
  compass `blockside` agree with the line's bearing. And the sweeping line is drawn in the same direction as the
  centerline for the same CNN in 37,809 of 37,856 rows (47 reversed).
- End to end on the permits: range side vs the side of the permit's EAS point relative to the app's sweeping line:
  **6,992 agree, 19 disagree**; 94 have no range side, 797 aren't on a swept block.

So the mapping itself is reliable (~99.7%). But it only says which side the **address** is on, and section 3 shows
the address often isn't where the signs are. A side filter would narrow the one block the join already names and make
the false-safe miss more likely, so it isn't worth adding until there is real sign-location data.

### Recommendation
- The join is **technically good**: 97% of live permits get one CNN, with no ambiguity and strong self-consistency.
  Cost to build: one more download (EAS is ~390k rows; the centerline ranges alone give 98.8% of the same answers
  from 17k rows, so they could replace EAS entirely in the app), plus the name normalizing above.
- But as a location for **signs** it is right on the exact block only ~68–79% of the time. If it goes into
  `PermitSource`, it should warn on the **address block and every block sharing a corner with it** (~90% coverage),
  with wording that says the permit is "near" rather than "on your block". Keep it block-level; don't filter by side.
- Blank-address Clariti permits (3.4%, mostly Temporary Occupancy) can't be placed at all.
- Decision for Z: is a "nearby permit, check the signs" hint at ~90% coverage worth a sixth feed, given that the
  existing `b6tj-gt35` TempOccup feed already gives exact blocks for the old system's permits?
