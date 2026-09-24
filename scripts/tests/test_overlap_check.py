"""Tests for scripts/overlap_check.py: parsing both feeds, tow enforcement windows, and the overlap count. No network.

Run:  python -m unittest discover -s scripts/tests -v
"""

import sys
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import overlap_check as oc  # noqa: E402


def closure(cnn="100", start="2026-09-28T07:00:00.000", end="2026-09-28T17:00:00.000", case="C1", impact="all-lanes-closed"):
    return {"objectid": case + start, "case_num": case, "case_name": "Special Event", "cnn": cnn, "street": "FELL ST",
            "veh_imp": impact, "start_dt": start, "end_dt": end}


def tow(cnn="100", start="2026-09-28T00:00:00.000", end="2026-09-30T00:00:00.000", st="7:00 AM", et="5:00 PM",
        notes="Monday - Friday", allday="No", row="r1"):
    return {":id": row, "casenumber": "T" + row, "cnn": cnn, "streetfrontagename": "FELL ST", "startdate": start,
            "enddate": end, "starttime": st, "endtime": et, "notes": notes, "_24hourenforcement": allday}


class ParsingTest(unittest.TestCase):
    def test_days_match_the_apps_rules(self):
        self.assertEqual(frozenset(range(5)), oc.parse_days("Monday - Friday"))
        self.assertEqual(frozenset({4, 5, 6, 0, 1, 2}), oc.parse_days("Friday - Wednesday"))  # wraps past Sunday
        self.assertEqual(frozenset({0, 1}), oc.parse_days("Monday,Tuesday"))
        self.assertEqual(oc.ALL_DAYS, oc.parse_days(""))
        self.assertEqual(oc.ALL_DAYS, oc.parse_days("weekdays mostly"))

    def test_clock(self):
        self.assertEqual(0, oc.parse_clock("12:00 AM"))
        self.assertEqual(23 * 60 + 59, oc.parse_clock("11:59 PM"))
        self.assertIsNone(oc.parse_clock("soon"))

    def test_multi_cnn_tow_rows_and_bad_rows(self):
        zones = oc.parse_tow_zones([tow(cnn="100,200"), tow(cnn="", row="bad")])
        self.assertEqual([("100", "200")], [z.cnns for z in zones])

    def test_partial_closure_is_not_full(self):
        self.assertFalse(oc.parse_closures([closure(impact="some-lanes-closed")])[0].full)
        self.assertTrue(oc.parse_closures([closure(impact="")])[0].full)  # missing = full, like the app


class OverlapTest(unittest.TestCase):
    def test_same_block_same_time_is_an_exact_match(self):
        m = oc.find_overlaps(oc.parse_closures([closure()]), oc.parse_tow_zones([tow()]))
        self.assertEqual(1, len(m))
        self.assertAlmostEqual(1.0, m[0].coverage)

    def test_other_block_or_other_time_is_no_match(self):
        zones = oc.parse_tow_zones([tow()])
        self.assertEqual([], oc.find_overlaps(oc.parse_closures([closure(cnn="999")]), zones))
        evening = closure(start="2026-09-28T18:00:00.000", end="2026-09-28T22:00:00.000")
        self.assertEqual([], oc.find_overlaps(oc.parse_closures([evening]), zones))
        saturday = closure(start="2026-10-03T08:00:00.000", end="2026-10-03T12:00:00.000")
        self.assertEqual([], oc.find_overlaps(oc.parse_closures([saturday]), oc.parse_tow_zones([tow(end="2026-10-10T00:00:00.000")])))

    def test_partial_coverage_is_measured(self):
        # Closure 7 AM - 9 PM (14 h), tow 7 AM - 5 PM (10 h).
        m = oc.find_overlaps(oc.parse_closures([closure(end="2026-09-28T21:00:00.000")]), oc.parse_tow_zones([tow()]))
        self.assertAlmostEqual(10 / 14, m[0].coverage, places=3)

    def test_overnight_and_all_day_windows(self):
        overnight = oc.parse_tow_zones([tow(st="7:00 PM", et="7:00 AM")])[0]
        w = oc.tow_windows(overnight, datetime(2026, 9, 29, 2, 0), datetime(2026, 9, 29, 4, 0))
        self.assertEqual(1, len(w))  # Monday's 7 PM window runs into Tuesday morning
        all_day = oc.parse_tow_zones([tow(st="12:00 AM", et="11:59 PM", allday="Yes")])[0]
        self.assertTrue(all_day.all_day)

    def test_summary_counts_a_recurring_closure_once(self):
        rows = [closure(start=f"2026-09-{d}T07:00:00.000", end=f"2026-09-{d}T17:00:00.000", case="SAME") for d in (28, 29)]
        closures, zones = oc.parse_closures(rows), oc.parse_tow_zones([tow()])
        s = oc.summarize(closures, zones, oc.find_overlaps(closures, zones))
        self.assertEqual(2, s["closure_rows_with_tow"])
        self.assertEqual(1, s["closure_cases_with_tow"])
        self.assertEqual(1, s["tow_zones_with_closure"])


class StalenessTest(unittest.TestCase):
    def test_stale_feed_gives_no_verdict_unless_forced(self):
        now = datetime(2026, 9, 24, 12, 0)
        data = {"closures": [closure()], "tow": [tow()], "tow_newest": [{"newest": "2026-07-20T17:01:33.000"}]}
        self.assertTrue(oc.feed_is_stale(datetime(2026, 7, 20), now))
        self.assertTrue(oc.feed_is_stale(None, now))
        self.assertFalse(oc.feed_is_stale(datetime(2026, 9, 22), now))
        self.assertEqual(2, oc.report(data, now, force=False))
        self.assertEqual(0, oc.report(data, now, force=True))
        fresh = dict(data, tow_newest=[{"newest": "2026-09-23T10:00:00.000"}])
        self.assertEqual(0, oc.report(fresh, now, force=False))


if __name__ == "__main__":
    unittest.main()
