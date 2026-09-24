# Park — Status Update: v1.04 released (2026-09-24)

For updating another Claude conversation. Builds on what that chat already has: the reliability plan (T0–T9), the
follow-up pass (F0–F6, `docs/StatusUpdate-FollowUpPass.md`), automation (A1–A7, `docs/StatusUpdate-Automation.md`),
permissions banners (P1–P2, `docs/StatusUpdate-Permissions-P1P2.md`) and the closures spec (`docs/park-closures-spec.md`).
All of it is now merged into `main` and pushed; `main` is the only branch. Tags: `v1.04` (this release), `v1.02-tested`,
`baseline-sep18`. Room database: **v22**.

## Shipped since v1.03

- **Street closures** (SFMTA feed `8x25-yybr`): a check when you park, plus optional background checks every 12 h.
  Alerts: "blocked in" when your own block closes, and "nearby" once per park. A map layer shows the next week of
  closures (needs background checks on). There's a line in the parked-car banner, and a one-time map card offering
  background checks.
- **Temporary tow zones** (`6r5h-j298`). Built with a warning because the city's feed is **stale**: the newest permit was
  entered 2026-07-20, and only 7 zones are live. A zone on your block works like a sweep deadline (normal and urgent
  reminders, roll-forward), plus a heads-up 2 days before, sent at once for a late permit. Parking inside a zone gives an
  urgent "in effect now". A garage or lot with no street segment near a zone gets one "check signs" notice. The app never
  says "no tow zones": the banner and Settings say the city's tow data may be out of date. Details: closures spec §10.
- **Saved locations:** one "What kind of spot is this?" choice: *On the street* (all checks), *On the street, never
  swept*, or *Off the street* (garage or lot: skips the permit limit and tow zones, closures still checked).
- **First-park notification ask:** on Android 13+, if notifications are off and the app never asked, it asks once after
  the first manual park.
- **UI:** dependent Settings options are greyed out with "Needs: …" instead of hidden. Edit dialogs have an
  always-visible scroll bar, and colour/icon rows have one underneath. The block details sheet now shows every schedule
  on the curb (e.g. Mon + Thu), the soonest next cleaning, and all sweep days. The parking flow has Back/Cancel on every
  step, and asks when a pin is far from the chosen street.
- **Tools:** `debug_hooks.py inject-tow / clear-tow / reset-notification-ask`, and `scripts/overlap_check.py`, which
  compares the closure and tow feeds and refuses a verdict while the tow feed is stale.

## Checked

All JVM unit tests and all 193 Python script tests pass. The release APK is clean (no debug receiver). The owner tested on
emulators (API 29 and 33+) and ran the full v1.04 checklist (`docs/CheckFirst-Closures-Tow.md`) on the **S25**:
everything passed, boot re-arm included. SFMTA's holiday page matches the app's rule: nightly sweeping is off only on
New Year's, Thanksgiving and Christmas.

## Decisions made

- Build tow zones now, with the stale-data warning.
- Off-street also skips the permit (RPP) limit.
- Ask for notification permission at the first park: yes.
- P3 (boot-missed detector): not built, since the boot checks pass.

## Still open

1. The S9 check (Android 10): the notification ask should never appear.
2. The tow/closure overlap verdict: waiting for SFMTA to fix the tow feed. When Settings stops showing the red
   out-of-date note, run `python scripts\overlap_check.py`. That decides whether to build the spec's merge rule (one
   warning where a tow zone and a closure overlap; for now both alerts show).
3. Whether CI on GitHub is green hasn't been checked (Actions tab).

The planned work is complete. Next steps are new features or issues found while using the app.
