"""Tests for scripts/pw_permit_check.py: date and permit-number parsing, the histogram, lead times, the next-7-days
window, the tow cross-check and the verdict. No network.

Run:  python -m unittest discover -s scripts/tests -v
"""

import io
import sys
import unittest
from contextlib import redirect_stdout
from datetime import date, datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pw_permit_check as pw  # noqa: E402


def permit(number="26TOC-00001", type_="TempOccup", status="APPROVED", entry="2026-08-03T09:00:00",
           start="2026-08-08T07:00:00", end="2026-08-08T17:00:00", cnns=("100",)):
    return pw.Permit(number, type_, status, pw.parse_when(entry), pw.parse_when(start), pw.parse_when(end), tuple(cnns))


class ParsingTest(unittest.TestCase):
    def test_every_date_shape_the_datasets_use(self):
        self.assertEqual(datetime(2026, 9, 14), pw.parse_when("2026-09-14"))                      # Clariti issue_date
        self.assertEqual(datetime(2026, 9, 21, 8, 50, 25), pw.parse_when("2026-09-21T08:50:25.000"))  # Socrata calendar_date
        self.assertEqual(datetime(2026, 9, 23, 0, 0, 9), pw.parse_when("2026-09-23 00:00:09"))    # Clariti date_as_of
        self.assertEqual(datetime(2026, 9, 22), pw.parse_when("09/22/2026"))                      # sftu-nd43 startdate
        self.assertIsNone(pw.parse_when(""))
        self.assertIsNone(pw.parse_when(None))
        self.assertIsNone(pw.parse_when("next Tuesday"))

    def test_permit_numbers_are_normalized(self):
        self.assertEqual(["26TOC-03680"], pw.permit_numbers("26TOC-03680,"))  # the trailing comma sftu-nd43 has
        self.assertEqual(["1616785"], pw.permit_numbers("TC#1616785"))
        self.assertEqual(["A-1", "B-2"], pw.permit_numbers("a-1, b-2"))
        self.assertEqual([], pw.permit_numbers(""))
        self.assertEqual([], pw.permit_numbers(None))

    def test_cnn_columns(self):
        self.assertEqual(["11799000"], pw.cnns(11799000))
        self.assertEqual(["11799000"], pw.cnns("11799000.0"))
        self.assertEqual(["1", "2"], pw.cnns("1, 2"))  # the tow feed lists several
        self.assertEqual([], pw.cnns(None))

    def test_parse_counts_unreadable_dates_and_filters_signposting(self):
        rows = [
            {"permit_number": "TOC-26-1", "permit_type": "Temporary Occupancy", "status": "Active",
             "issue_date": "2026-08-01", "permit_start_date": "2026-08-05", "permit_end_date": "2026-08-06", "cnn": "5"},
            {"permit_number": "SW-26-1", "permit_type": "Sidewalk Repair", "status": "Active",
             "issue_date": "2026-08-01", "permit_start_date": "2026-08-05", "permit_end_date": "2026-08-06"},
            {"permit_number": "SSP-26-1", "permit_type": "Street Space", "status": "Void",
             "issue_date": "garbage", "permit_start_date": "2026-08-05", "permit_end_date": "2026-08-06"},
        ]
        permits, unreadable = pw.parse(pw.CLARITI, rows)
        self.assertEqual(3, len(permits))
        self.assertEqual(1, unreadable["issue_date"])
        self.assertEqual(("5",), permits[0].cnns)
        # Sidewalk repair isn't a sign-posting type and a void permit was never issued.
        self.assertEqual(["TOC-26-1"], [p.number for p in pw.signposting(pw.CLARITI, permits)])

    def test_number_kinds(self):
        kinds = pw.number_kinds(["26EXC-00031", "25EXC-1", "TOC-26-01392", "1616951", "26SF168"])
        self.assertEqual(2, kinds["EXC"])
        self.assertEqual(1, kinds["TOC- (Clariti)"])
        self.assertEqual(1, kinds["#"])
        self.assertEqual(1, kinds["other"])


class AnalysisTest(unittest.TestCase):
    def test_histogram_is_zero_filled_by_monday_week(self):
        weeks = pw.histogram([permit(entry="2026-07-22T10:00:00"), permit(entry="2026-07-26T23:00:00"),
                              permit(entry="2026-08-10T00:00:00"), permit(entry=None)],
                             date(2026, 7, 20), date(2026, 8, 12))
        self.assertEqual({date(2026, 7, 20): 2, date(2026, 7, 27): 0, date(2026, 8, 3): 0, date(2026, 8, 10): 1}, weeks)

    def test_after_cutoff_is_strictly_after_the_tow_feeds_last_day(self):
        ps = [permit(entry="2026-07-20T23:00:00"), permit(entry="2026-07-21T00:30:00")]
        self.assertEqual(1, len(pw.after_cutoff(ps)))

    def test_lead_times(self):
        ps = [permit(entry="2026-08-01T00:00:00", start=f"2026-08-{d:02d}T00:00:00") for d in (2, 3, 5, 8, 11)]
        ps.append(permit(entry="2026-08-10T00:00:00", start="2026-08-01T00:00:00"))  # back-dated
        ps.append(permit(entry="2026-05-01T00:00:00", start="2026-05-03T00:00:00"))  # before the window: ignored
        lt = pw.lead_times(ps, date(2026, 6, 1))
        self.assertEqual(6, lt.n)
        self.assertEqual(1, lt.negative)
        self.assertEqual(4.0, lt.median_days)  # of 1, 2, 4, 7, 10
        self.assertAlmostEqual(0.2, lt.under_2_days)
        self.assertEqual(0, pw.lead_times([], date(2026, 6, 1)).n)

    def test_covering_treats_a_date_only_end_as_the_whole_day(self):
        frm, to = datetime(2026, 9, 24), datetime(2026, 10, 1)
        whole_day = permit(start="2026-09-20T00:00:00", end="2026-09-24T00:00:00")   # ends "on" the 24th
        over = permit(start="2026-09-01T00:00:00", end="2026-09-23T00:00:00")
        later = permit(start="2026-10-01T00:00:00", end="2026-10-05T00:00:00")
        timed = permit(start="2026-09-30T07:00:00", end="2026-09-30T15:00:00")
        self.assertEqual([whole_day, timed], pw.covering([whole_day, over, later, timed], frm, to))

    def test_cross_check_by_number_then_by_cnn_and_window(self):
        pools = {
            "street_use": [permit(number="26EXC-1", cnns=("100",)),
                           permit(number="26TOC-9", cnns=("200",), start="2026-08-07T00:00:00", end="2026-08-09T00:00:00")],
            "clariti": [],
        }
        live = [permit(number="26EXC-1", cnns=("999",)),                     # by number, wherever it is
                permit(number="", cnns=("200",), start="2026-08-08T00:00:00", end="2026-08-08T00:00:00"),  # by block
                permit(number="", cnns=("300",)),                            # nowhere
                permit(number="", cnns=("300",))]                            # duplicate: counted once
        checks = pw.cross_check(live, pools)
        self.assertEqual(3, len(checks))
        self.assertEqual(["street_use"], checks[0].found_in)
        self.assertEqual(([], ["street_use"]), (checks[1].found_in, checks[1].cnn_window_in))
        self.assertEqual(([], []), (checks[2].found_in, checks[2].cnn_window_in))

    def test_verdict(self):
        self.assertEqual(("CLARITI_HAS_NEW_PERMITS", ["clariti"]),
                         pw.verdict({"clariti": 50, "signs": 2}, 9.0, {"clariti": False, "signs": False}))
        self.assertEqual(("NO_NEW_PERMITS_ANYWHERE", []),
                         pw.verdict({"clariti": 3, "signs": 0}, 9.0, {"clariti": False, "signs": False}))
        # Nothing new, but one dataset's entry dates couldn't be read: that's not evidence of absence.
        self.assertEqual(("INCONCLUSIVE", ["signs"]),
                         pw.verdict({"clariti": 3, "signs": 0}, 9.0, {"clariti": False, "signs": True}))


class ReportTest(unittest.TestCase):
    def test_report_runs_on_saved_data_and_prints_a_verdict(self):
        clariti = [{"permit_number": f"TOC-26-{i}", "permit_type": "Temporary Occupancy", "status": "Active",
                    "phase": "Tow Sign Photo", "issue_date": "2026-08-%02d" % (1 + i % 28),
                    "permit_start_date": "2026-09-25", "permit_end_date": "2026-09-26", "cnn": "100"} for i in range(80)]
        tow = [{"permitnumber": "26EXC-1", "cnn": "100", "source": "Web", "datetimeentered": "2026-07-01T10:00:00.000",
                "startdate": "2026-07-05T00:00:00.000", "enddate": "2026-07-06T00:00:00.000"}]
        data = {"clariti": clariti, "signs": [], "street_use": [], "active_street_use": [], "tow": tow, "tow_live": [],
                "street_use_tow_numbers": []}
        out = io.StringIO()
        with redirect_stdout(out):
            code = pw.report(data, date(2026, 9, 24))
        self.assertEqual(0, code)
        text = out.getvalue()
        self.assertIn("VERDICT: CLARITI_HAS_NEW_PERMITS (clariti)", text)
        self.assertIn("rows in the 'Tow Sign Photo' phase (all types and dates): 80", text)
        self.assertIn("found nowhere: 1", text)


if __name__ == "__main__":
    unittest.main()
