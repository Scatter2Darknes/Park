"""Tests for scripts/clariti_cnn_join.py: address parsing, the EAS join tiers, side-of-street from address ranges
and from geometry. No network.

Run:  python -m unittest discover -s scripts/tests -v
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import clariti_cnn_join as cj  # noqa: E402


class ParseAddressTest(unittest.TestCase):
    def test_double_space_separates_name_from_type(self):
        self.assertEqual(cj.Parsed("address", 1020, "", "UNION", "ST"), cj.parse_address("1020 UNION  ST"))
        self.assertEqual(cj.Parsed("address", 572, "", "SAN JOSE", "AVE"), cj.parse_address("572 SAN JOSE  AVE"))

    def test_suffix_ordinals_and_missing_type(self):
        self.assertEqual(cj.Parsed("address", 164, "A", "DIAMOND", "ST"), cj.parse_address("164A DIAMOND  ST"))
        self.assertEqual(cj.Parsed("address", 175, "", "4TH", "ST"), cj.parse_address("175 04TH  ST"))
        self.assertEqual(cj.Parsed("address", 2320, "", "BROADWAY", None), cj.parse_address("2320 BROADWAY"))
        self.assertEqual(cj.Parsed("address", 3270, "", "18TH", None), cj.parse_address("3270 18TH"))

    def test_single_space_known_type_and_long_names(self):
        self.assertEqual("ST", cj.parse_address("1 MAIN ST").type)
        self.assertEqual(cj.Parsed("address", 1, "", "DR CARLTON B GOODLETT", "PL"),
                         cj.parse_address("1 DR CARLTON B GOODLETT  PL"))

    def test_things_that_are_not_one_address(self):
        self.assertEqual("blank", cj.parse_address(None).kind)
        self.assertEqual("blank", cj.parse_address("   ").kind)
        self.assertEqual("intersection", cj.parse_address("3600 JACKSON  ST & SPRUCE ST").kind)
        self.assertEqual("range", cj.parse_address("2200 - 2299 POST  ST").kind)
        self.assertEqual("no_number", cj.parse_address("OCTAVIA  ST").kind)


def eas_row(n, name, typ, cnn, suffix=None):
    return {"address_number": str(n), "street_name": name, "street_type": typ, "cnn": str(cnn),
            "address_number_suffix": suffix, "longitude": "-122.4", "latitude": "37.8", "zip_code": "94133"}


class JoinTest(unittest.TestCase):
    def setUp(self):
        rows = [eas_row(1020, "UNION", "ST", 100), eas_row(1020, "UNION", "ST", 100),   # two units, one address
                eas_row(164, "DIAMOND", "ST", 200), eas_row(2320, "BROADWAY", None, 300),
                eas_row(175, "04TH", "ST", 400)]
        self.idx = cj.index_eas(rows)

    def run_join(self, address):
        return cj.join({"permit_address": address}, *self.idx, {})

    def test_exact(self):
        r = self.run_join("1020 UNION  ST")
        self.assertEqual(("single", "exact", ("100",)), (r.outcome, r.tier, r.cnns))

    def test_suffix_dropped_type_missing_and_ordinal_zero(self):
        self.assertEqual(("single", "suffix dropped"), (lambda r: (r.outcome, r.tier))(self.run_join("164A DIAMOND  ST")))
        self.assertEqual(("single", "type missing"), (lambda r: (r.outcome, r.tier))(self.run_join("2320 BROADWAY")))
        self.assertEqual(("400",), self.run_join("175 4TH  ST").cnns)

    def test_no_match_and_not_an_address(self):
        self.assertEqual("none", self.run_join("9 NOWHERE  ST").outcome)
        self.assertEqual("blank", self.run_join(None).outcome)


class SideTest(unittest.TestCase):
    def seg(self, left, right):
        return cj.Segment("1", "X", "ST", {"L": left, "R": right}, [], True)

    def test_side_from_the_segments_own_ranges_not_a_fixed_parity(self):
        odd_left = self.seg((1, 99), (2, 98))
        even_left = self.seg((730, 798), (751, 799))     # 768 COLE ST's block: the even side is on the left
        self.assertEqual("L", cj.side_by_range(odd_left, 51))
        self.assertEqual("R", cj.side_by_range(odd_left, 50))
        self.assertEqual("L", cj.side_by_range(even_left, 768))
        self.assertEqual("R", cj.side_by_range(even_left, 761))
        self.assertIsNone(cj.side_by_range(odd_left, 150))

    def test_geometry_side_and_bearing(self):
        east = [[-122.41, 37.78], [-122.40, 37.78]]          # drawn west -> east
        d, side = cj.side_of_line(east, -122.405, 37.7802)   # a bit north
        self.assertEqual("L", side)
        self.assertLess(d, 30)
        self.assertEqual("R", cj.side_of_line(east, -122.405, 37.7798)[1])
        self.assertAlmostEqual(90, cj.bearing(east), delta=1)
        self.assertEqual("North", cj.left_compass(90))
        self.assertEqual("South", cj.left_compass(270))


if __name__ == "__main__":
    unittest.main()
