# F1 — Raw-data investigation findings

Date: 2026-09-20. Report only; no app code changed. Raw data was downloaded to a scratch folder outside the repo (not committed): the DataSF street-sweeping dataset `yhqp-riqs` (37,878 rows, fetched directly, no summarizing tool) and SFMTA's RPP ArcGIS layer 24 using the app's own filter (`RPPAREA1 IS NOT NULL AND RPPAREA1 <> ' '`, 6,502 rows) plus the whole layer (7,788 rows).

## Correction to earlier reports
The earlier "580 rows with HRLIMIT 0 and 268 null" came from a `where=1=1` query over the **whole** layer (every regulation type). Those rows are not RPP rows and the app never loads them. Among the rows the app uses there are **no** `HRLIMIT = 0` rows and only **10** null. The earlier figures (and the layer total, 8,188 then vs 7,788 now) also came through a summarizing fetch tool and should not be relied on.

## A. `HRLIMIT` in the rows the app loads (6,502)
| HRLIMIT | rows |
|---|---|
| 2.0 | 5,613 |
| 1.0 | 433 |
| 4.0 | 245 |
| 3.0 | 142 |
| 72.0 | 59 |
| null | 10 |
| 0.0 | **0** |

- The 0.0 / null rows in the whole layer belong to other regulations: 517 "No oversized vehicles", 172 null "No parking any time", 44 "Government permit", etc.
- **The 10 null RPP rows:** 2 × "No parking any time" (`RPPAREA1 = "0"`, no days/hours — junk); 3 × "Paid + Permit" (zones Y, X; meters, exceptions text "RPP holders are exempt from meters"); 5 × "Time Limited" (zones S, D, D, Q, U; days/hours present, exceptions "exempt from time limits", **limit value simply missing**). The 5 "Time Limited" rows are the only ones where a real limit exists but is unknown.
- **The 72 h rows:** all 59 are zone HV, "Pay or Permit", M-Sa 9–21, "RPP holders are exempt from payment". A 72 h limit can't be exceeded within one day's window; the app's per-day model gives no deadline. Multi-day tracking isn't modeled.
- **Nothing in the data separates "permit only" from "no limit."** No RPP row has a REGDETAILS text (it is blank/space in the rows inspected); EXCEPTIONS is the same boilerplate on 6,431 rows ("Yes. RPP holders are exempt from time limits"), 58 "exempt from payment", 3 "exempt from meters", 3 "None. Regulation applies to all vehicles", 7 blank.
- **Mixed blockface:** cannot be answered from attributes — the `ID`/`FID_100` fields are empty or constant, so they are not a blockface key. Would need geometry overlap.
- **Bonus finding — junk zone "0":** `RPPAREA1 = "0"` on the 2 "No parking any time" rows passes the app's `<> ' '` filter, so zone "0" can appear in the permit picker. Distinct RPPAREA1 values: 0, A–Z (incl. AA–HH, HV).

**Conclusion:** F4 as written (0 = permit-only?) is largely moot for RPP rows: there are no zeros. The real exposure is the 5 "Time Limited" rows with a missing limit (currently no deadline; conservative option = warn softly) and the junk "0" zone.

## B. The 824 `HOLIDAY` sweeping rows
- All 824 rows: `fullname = 'HOLIDAY'`, `weekday = 'Holiday'`, weeks 1–5 all true. Hours: 2–6 (267), 4–6 (230), 5–7 (176), 6–8 (116), 1–6 (35). `holidays` flag: 537 = 1, 287 = 0.
- **HOLIDAY-only vs plus weekday rows (by cnn + side): 0 vs 824.** Every HOLIDAY row sits on a curb that also has ordinary weekday rows; 756 of 824 have the same hours as a weekday row. Those curbs are the 7-day nightly routes: one row per weekday (Mon…Sun) + a HOLIDAY row (e.g. 03rd St, Howard–Clementina, L: Mon–Sun and HOLIDAY, all 2–6). 699 have 7 weekday rows.
- No HOLIDAY row shares a `blocksweepid` with a weekday row.
- SFMTA holiday page (read via a summarizing fetch tool — please verify on the page): says nightly sweeping (12am–6am) is not enforced on New Year's Day and Christmas (the summary omitted Thanksgiving; the existing calendar tests were transcribed from the page and include it), daytime sweeping and time-limited RPP are not enforced on the listed holidays, and the page contains **no** information about "holiday sweeping" routes.
- **Conclusion:** the HOLIDAY rows are complementary, not standalone. Given the current rule (nightly routes suspended only on the 3 major holidays), the day rows already produce the right holiday behavior, so the HOLIDAY rows add nothing the app needs. The only thing to guarantee is that the always-"no sweep" HOLIDAY row is never chosen instead of a real weekday row.

## C. The T0 holiday finding, re-verified
Rows by (`holidays` flag, starts before 6am): flag 0 & <6: 10,522; flag 0 & ≥6: 24,623; flag 1 & <6: 2,620; **flag 1 & ≥6: 113**.
- The 113 `holidays=1` rows starting at/after 6am are all Monday 6–8 (Tenderloin blocks: Ellis, O'Farrell, Jones, Larkin …). Under the current rule they get the short list.
- Overnight rows under flag 0 (e.g. 4,418 at 2–6, 1,992 at 4–6, 1,564 at 0–6, 1,348 at 0–2) also get the short list. This confirms the T0 finding: the flag does not identify nightly routes.
- **Dangerous direction (showing "not swept" when SFMTA says swept):** none found. Nightly-style rows are suspended only on the 3 major holidays; daytime rows on the full list. Both directions the rule can err in (the 113 flag-1 daytime rows, and 5–7 rows) err toward showing a sweep. Caveat: relies on the SFMTA table being read correctly, especially whether nightly sweeping is also not enforced on Thanksgiving Day.

## D. Bigger finding: many curbs have several sweeping rows, and parking saves only one
- 22,573 curbs (cnn + side); 16,812 have 1 row, but **5,761 (25.5%) have 2–13 rows**. **4,784 of those sweep on different weekdays** (the rest are the same weekday with different week patterns/hours). Row counts: 2 rows: 2,324; 3: 1,434; 4: 635; 5: 381; 7: 139; 8: 700; 13: 30.
- **What the code does (read, not run):** `findNearbySegmentMatches` returns each row separately, sorted by distance. Rows on the same curb are co-located, so `classifyMatch` sees a gap under 5 m and calls it AMBIGUOUS. The manual picker lists rows individually; the Bluetooth auto-park saves `matches.first()`. `saveParkedState` stores a single `segmentBlockSweepId`, and `nextSweepAtMillis` (and every later recompute) comes from that one row's schedule only.
- **Consequence to confirm on a device:** parking on a curb swept Monday *and* Thursday can subscribe to only one of them; on a 7-day nightly curb, to one weekday (or to the always-null HOLIDAY row, which gives no reminder at all). That is the false-"safe" direction. The map drawing is fine — it already keeps the most urgent row per curb (`groupBy cnn|cnnRightLeft`).
- Suggested follow-up (not started): merge rows per curb at park time — take the earliest next sweep across all parseable rows on the same cnn + side (override-aware), ignore unparseable rows, and collapse same-curb rows in the matcher so they don't read as AMBIGUOUS.
