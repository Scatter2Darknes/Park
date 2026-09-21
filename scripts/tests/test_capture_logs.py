"""Tests for scripts/capture_logs.py. No device: it digests saved logs and uses a fake adb for the capture itself.

Fixtures (tests/fixtures/):
  logcat-emulator.txt      a REAL capture from the emulator: a PARK, a REARM and a DUMP_STATE through the debug receiver.
  logcat-other-events.txt  lines in the app's actual log formats (copied from the source) for events the emulator
                           run didn't produce: boot, stale-row cleanup, exact-alarm warnings, tunnel decisions, a crash.

Run:  python -m unittest discover -s scripts/tests -v
"""

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import capture_logs  # noqa: E402
from common import Adb, Result, ScriptError  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
EMULATOR = (FIXTURES / "logcat-emulator.txt").read_text(encoding="utf-8")
OTHER = (FIXTURES / "logcat-other-events.txt").read_text(encoding="utf-8")


class ParseTest(unittest.TestCase):
    def test_parses_a_threadtime_line(self):
        parsed = capture_logs.parse_line("09-20 19:36:31.762  4494  4530 I ParkDebug: UNPARK: car 1 cleared")
        self.assertEqual("ParkDebug", parsed["tag"])
        self.assertEqual("I", parsed["level"])
        self.assertEqual("UNPARK: car 1 cleared", parsed["msg"])

    def test_tags_padded_with_spaces_are_trimmed_by_the_digest(self):
        parsed = capture_logs.parse_line("09-20 19:37:48.853  4494  4529 D RppSync : saveParkedState: RPP deadline = x")
        self.assertEqual("RppSync", parsed["tag"].strip())

    def test_non_log_lines_are_ignored(self):
        self.assertIsNone(capture_logs.parse_line("--------- beginning of main"))
        self.assertIsNone(capture_logs.parse_line(""))


class RealCaptureDigestTest(unittest.TestCase):
    def setUp(self):
        self.digest = capture_logs.build_digest(EMULATOR)

    def test_finds_the_parking_saves_and_rearm_from_the_real_capture(self):
        self.assertGreaterEqual(self.digest.counts["Parking saves"], 3)   # two RppSync lines + the ParkDebug PARK line
        self.assertGreaterEqual(self.digest.counts["Re-arm / recompute / roll-forward"], 1)
        self.assertTrue(any("PARK: car 1 saved on 03rd Ave" in line for line in self.digest.lines["Parking saves"]))

    def test_no_crashes_in_the_real_capture(self):
        self.assertEqual([], self.digest.exceptions)

    def test_the_digest_only_quotes_lines_that_really_are_in_the_file(self):
        for lines in self.digest.lines.values():
            for line in lines:
                self.assertIn(line, EMULATOR)


class OtherEventsDigestTest(unittest.TestCase):
    def setUp(self):
        self.digest = capture_logs.build_digest(OTHER)

    def test_counts_per_category(self):
        self.assertEqual(1, self.digest.counts["BootReceiver"])
        self.assertEqual(3, self.digest.counts["Re-arm / recompute / roll-forward"])   # boot line, recompute, roll-forward
        self.assertEqual(3, self.digest.counts["Stale-row cleanup"])
        self.assertEqual(2, self.digest.counts["Exact-alarm permission"])
        self.assertEqual(3, self.digest.counts["Tunnel decisions"])

    def test_stale_cleanup_totals_are_summed_per_tag(self):
        self.assertEqual({"segment": 812, "RPP": 5}, self.digest.cleanup_totals)

    def test_a_crash_is_reported_with_its_first_stack_lines_and_stops_at_the_next_log_line(self):
        self.assertEqual(1, len(self.digest.exceptions))
        block = self.digest.exceptions[0]
        self.assertIn("FATAL EXCEPTION", block[0])
        self.assertTrue(any("IllegalStateException" in line for line in block))
        self.assertTrue(any("armParkedState" in line for line in block))
        self.assertLessEqual(len(block), 7)                       # FATAL line + at most 6 more
        self.assertFalse(any("unrelated line after the crash" in line for line in block))

    def test_rendered_digest_keeps_em_dashes_intact(self):
        text = capture_logs.render_digest(self.digest)
        self.assertIn("—", text)
        self.assertIn("BootReceiver: BOOT_COMPLETED — re-arming reminders", text)
        self.assertIn("removed in total: RPP 5 rows, segment 812 rows", text)
        self.assertIn("Crashes (AndroidRuntime FATAL EXCEPTION): 1", text)

    def test_only_the_most_recent_few_lines_per_category_are_quoted(self):
        many = "\n".join(f"09-20 19:00:{i:02d}.000  1  1 D Tunnel  : gap={i}ms -> dimming" for i in range(30))
        digest = capture_logs.build_digest(many, per_category=5)
        self.assertEqual(30, digest.counts["Tunnel decisions"])
        self.assertEqual(5, len(digest.lines["Tunnel decisions"]))
        self.assertIn("gap=29ms", digest.lines["Tunnel decisions"][-1])


class FakeAdb(Adb):
    def __init__(self, main_text, crash_text=""):
        super().__init__("adb", "emulator-5554")
        self.main_text, self.crash_text, self.calls = main_text, crash_text, []

    def run(self, *args, allow_fail=False, timeout=120):
        self.calls.append(list(args))
        return Result(0, self.crash_text if "crash" in args else self.main_text, "")


class CaptureTest(unittest.TestCase):
    def test_logcat_args_select_the_apps_tags_only(self):
        args = capture_logs.logcat_args(None, follow=False)
        self.assertIn("-d", args)
        self.assertEqual("-s", args[args.index("-s")])
        for tag in ("Park", "RppSync", "DataSF", "Tunnel", "AndroidRuntime"):
            self.assertIn(f"{tag}:V", args)

    def test_since_maps_to_logcat_t_and_follow_streams(self):
        args = capture_logs.logcat_args("09-20 18:30:00", follow=False)
        self.assertEqual("09-20 18:30:00.000", args[args.index("-t") + 1])
        self.assertNotIn("-d", capture_logs.logcat_args(None, follow=True))

    def test_a_malformed_since_is_rejected_with_an_example(self):
        for bad in ("yesterday", "2026-09-20 18:30", "18:30:00"):
            with self.subTest(since=bad), self.assertRaises(ScriptError) as ctx:
                capture_logs.logcat_args(bad, follow=False)
            self.assertIn("MM-dd HH:mm:ss", str(ctx.exception))

    def test_capture_appends_the_crash_buffer_under_a_heading(self):
        adb = FakeAdb("09-20 19:00:00.000  1  1 D Park    : hello — world\n", "09-20 19:01:00.000  1  1 E AndroidRuntime: FATAL EXCEPTION: x\n")
        text = capture_logs.capture(adb, None)
        self.assertIn("hello — world", text)
        self.assertIn("----- crash buffer -----", text)
        self.assertIn("FATAL EXCEPTION", text)
        self.assertEqual(2, len(adb.calls))

    def test_no_crash_section_when_the_crash_buffer_is_empty(self):
        self.assertNotIn("crash buffer", capture_logs.capture(FakeAdb("09-20 19:00:00.000  1  1 D Park    : x\n"), None))

    def test_only_read_commands_are_ever_sent(self):
        adb = FakeAdb("x")
        capture_logs.capture(adb, "09-20 18:30:00")
        for call in adb.calls:
            self.assertEqual("logcat", call[0])
            self.assertNotIn("-c", call, "clearing the device's log buffer is a change, so it is never done")


class MainFromFileTest(unittest.TestCase):
    def test_digests_a_saved_file_without_a_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "saved.txt"
            path.write_text(OTHER, encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(0, capture_logs.main(["--from-file", str(path)]))
            self.assertIn("Stale-row cleanup: 3", out.getvalue())
            self.assertIn("Crashes (AndroidRuntime FATAL EXCEPTION): 1", out.getvalue())


if __name__ == "__main__":
    unittest.main()
