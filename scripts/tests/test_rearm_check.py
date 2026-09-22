"""Tests for scripts/rearm_check.py using a fake device that can wipe and restore alarms on command, including the
FAIL cases (alarms never come back, only some come back, a phone without confirmation) that can't be produced by
deliberately breaking the real app.

Run:  python -m unittest discover -s scripts/tests -v
"""

import io
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import alarms  # noqa: E402
import rearm_check  # noqa: E402
from common import PACKAGE, Adb, Result, ScriptError  # noqa: E402

REMINDER = ("ParkingReminderReceiver", 1790856000000)
URGENT = ("ParkingReminderReceiver", 1790862300000)
ROLL = ("ScheduleRollForwardReceiver", 1790863200000)
THREE = [REMINDER, URGENT, ROLL]


def dumpsys_text(keys, history_rtc=None):
    """A minimal dumpsys alarm in the emulator's format for the given (receiver, due-ms) alarms."""
    lines = ["Current Alarm Manager state:"]
    for number, (receiver, when_ms) in enumerate(sorted(keys, key=lambda k: k[1]), start=40):
        lines += [
            f"    RTC_WAKEUP #{number}: Alarm{{aa{number} type 0 origWhen {when_ms} whenElapsed 1 {PACKAGE}}}",
            f"      tag=*walarm*:{PACKAGE}/.{receiver}",
            "      type=RTC_WAKEUP origWhen=2026-10-01 05:00:00.000 window=0 exactAllowReason=permission repeatInterval=0 count=0 flags=0x5",
            f"      operation=PendingIntent{{x{number}: PendingIntentRecord{{pi{number:04x} {PACKAGE} broadcastIntent}}}}",
        ]
    if history_rtc:  # the alarm history is a separate, less-indented section of the dump, after the alarm entries
        lines.append("  Alarm history:")
        for number, (receiver, _) in enumerate(sorted(keys, key=lambda k: k[1]), start=40):
            lines.append(f"    [tag=*walarm*:{PACKAGE}/.{receiver} T=0 F=32 AC=false H=PI:pi{number:04x} OW=x WL=0 elapsed=1 rtc={history_rtc}]")
    return "\n".join(lines) + "\n"


class FakeDevice(Adb):
    def __init__(self, alarms_now=THREE, serial="emulator-5554", restores="all", survives_force_stop=False,
                 boot_log=True, history_rtc=None, device_clock="2026-09-20 18:00:00", restore_after_polls=0):
        super().__init__("adb", serial)
        self.alarms_now = set(alarms_now)
        self.saved = set(alarms_now)
        self.restores = restores            # "all", "some", or "none": what the app re-arms
        self.survives_force_stop = survives_force_stop
        self.boot_log = boot_log
        self.history_rtc = history_rtc
        self.device_clock = device_clock
        self.restore_after_polls = restore_after_polls
        self._polls_since_restore_trigger = None
        self.commands = []

    # what "the app re-arms" means
    def _restore(self):
        if self.restores == "all":
            self.alarms_now = set(self.saved)
        elif self.restores == "some":
            self.alarms_now = {sorted(self.saved, key=lambda k: k[1])[0]}

    def shell(self, command, allow_fail=False, timeout=120):
        self.commands.append(command)
        if command == "dumpsys alarm":
            if self._polls_since_restore_trigger is not None:
                if self._polls_since_restore_trigger >= self.restore_after_polls:
                    self._restore()
                    self._polls_since_restore_trigger = None
                else:
                    self._polls_since_restore_trigger += 1
            return Result(0, dumpsys_text(self.alarms_now, self.history_rtc), "")
        if command.startswith("am force-stop"):
            if not self.survives_force_stop:
                self.alarms_now = set()
            return Result(0, "", "")
        if command.startswith("monkey"):
            self._polls_since_restore_trigger = 0
            return Result(0, "Events injected: 1", "")
        if command.startswith("date"):
            return Result(0, self.device_clock + "\n", "")
        if command.startswith("getprop sys.boot_completed"):
            return Result(0, "1\n", "")
        if command.startswith("getprop"):
            return Result(0, "0\n", "")
        raise AssertionError(f"unexpected command: {command}")

    def run(self, *args, allow_fail=False, timeout=120):
        self.commands.append(" ".join(args))
        if args[0] == "reboot":
            self.alarms_now = set()
            self._polls_since_restore_trigger = 0
            return Result(0, "", "")
        if args[0] == "wait-for-device":
            return Result(0, "", "")
        if args[0] == "logcat":
            return Result(0, "D/Park: BootReceiver: BOOT_COMPLETED - re-arming reminders\n" if self.boot_log else "", "")
        raise AssertionError(f"unexpected adb call: {args}")


class UnreadableDumpDevice(FakeDevice):
    """Its `dumpsys alarm` has an alarm line in a layout the parser doesn't know."""

    def shell(self, command, allow_fail=False, timeout=120):
        if command == "dumpsys alarm":
            return Result(0, f"    RTC_WAKEUP #0: Alarm{{ab weird-layout {PACKAGE}}}\n", "")
        return super().shell(command, allow_fail, timeout)


class Api29Device(FakeDevice):
    """Serves the REAL dumpsys captured from the API 29 emulator (5 alarms of the app, plus a platform placeholder)."""

    def shell(self, command, allow_fail=False, timeout=120):
        if command == "dumpsys alarm":
            return Result(0, (Path(__file__).parent / "fixtures" / "dumpsys-alarm-api29-emulator.txt").read_text(encoding="utf-8"), "")
        return super().shell(command, allow_fail, timeout)


class TerminalStdin(io.StringIO):
    def isatty(self):
        return True


class ClockedTest(unittest.TestCase):
    """Every test runs on a fake clock, so nothing really waits."""

    def setUp(self):
        self.now = [0.0]
        for name, value in (("sleep", lambda s: self.now.__setitem__(0, self.now[0] + s)), ("monotonic", lambda: self.now[0])):
            patcher = mock.patch.object(rearm_check, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.out = io.StringIO()
        patcher = mock.patch("sys.stdout", self.out)
        patcher.start()
        self.addCleanup(patcher.stop)


class PureHelpersTest(ClockedTest):
    def test_diff_keys(self):
        self.assertEqual(([URGENT], [("Other", 5)]), rearm_check.diff_keys({REMINDER, URGENT}, {REMINDER, ("Other", 5)}))
        self.assertEqual(([], []), rearm_check.diff_keys({REMINDER}, {REMINDER}))

    def test_poll_until_returns_as_soon_as_true_and_gives_up_at_the_timeout(self):
        calls = []
        self.assertTrue(rearm_check.poll_until(lambda: calls.append(1) or len(calls) == 3, timeout=30, interval=2))
        self.assertEqual(3, len(calls))
        self.assertFalse(rearm_check.poll_until(lambda: False, timeout=10, interval=2))
        self.assertGreaterEqual(self.now[0], 10)

    def test_set_after_compares_history_times_with_the_reboot_marker(self):
        history = [alarms.HistoryEntry("a", 0, "2026-09-20 18:05:00.000", 0), alarms.HistoryEntry("b", 0, "2026-09-20 17:00:00.000", 1)]
        self.assertTrue(rearm_check.set_after(history, "2026-09-20 18:00:00"))
        self.assertFalse(rearm_check.set_after(history, "2026-09-20 19:00:00"))
        self.assertIsNone(rearm_check.set_after([], "2026-09-20 18:00:00"))


class ForegroundTest(ClockedTest):
    def test_pass_when_the_same_alarms_come_back(self):
        device = FakeDevice()
        self.assertEqual(rearm_check.EXIT_PASS, rearm_check.run_check(device, "foreground"))
        self.assertIn("PASS", self.out.getvalue())
        self.assertIn(f"am force-stop {PACKAGE}", device.commands)

    def test_pass_even_if_the_alarms_take_a_few_polls_to_reappear(self):
        self.assertEqual(rearm_check.EXIT_PASS, rearm_check.run_check(FakeDevice(restore_after_polls=4), "foreground"))

    def test_fail_when_the_app_does_not_re_arm(self):
        self.assertEqual(rearm_check.EXIT_FAIL, rearm_check.run_check(FakeDevice(restores="none"), "foreground"))
        text = self.out.getvalue()
        self.assertIn("FAIL", text)
        self.assertIn("missing", text)
        self.assertIn("ScheduleRollForwardReceiver", text)

    def test_fail_lists_exactly_which_alarms_are_missing_when_only_some_return(self):
        code = rearm_check.run_check(FakeDevice(restores="some"), "foreground")
        self.assertEqual(rearm_check.EXIT_FAIL, code)
        text = self.out.getvalue()
        self.assertIn("missing", text)
        self.assertEqual(2, text.split("missing:")[1].count("due"))  # two of the three are missing

    def test_no_live_alarms_is_a_precondition_failure_not_a_test_failure(self):
        with self.assertRaises(rearm_check.PreconditionFailed) as ctx:
            rearm_check.run_check(FakeDevice(alarms_now=[]), "foreground")
        self.assertIn("park a car with a future sweep first", str(ctx.exception))

    def test_alarms_that_survive_a_force_stop_make_the_check_meaningless(self):
        with self.assertRaises(rearm_check.PreconditionFailed):
            rearm_check.run_check(FakeDevice(survives_force_stop=True), "foreground")

    def test_main_maps_the_outcomes_to_exit_codes(self):
        for device, expected in ((FakeDevice(), 0), (FakeDevice(restores="none"), 1), (FakeDevice(alarms_now=[]), 2)):
            with mock.patch.object(rearm_check, "connect", return_value=device):
                self.assertEqual(expected, rearm_check.main(["--mode", "foreground"]))


class BootTest(ClockedTest):
    def test_pass_and_reports_the_boot_receiver_log(self):
        device = FakeDevice()
        self.assertEqual(rearm_check.EXIT_PASS, rearm_check.run_check(device, "boot"))
        text = self.out.getvalue()
        self.assertIn("PASS", text)
        self.assertIn("BootReceiver: BOOT_COMPLETED", text)
        self.assertIn("reboot", device.commands)

    def test_a_missing_boot_receiver_log_is_only_a_note(self):
        code = rearm_check.run_check(FakeDevice(boot_log=False), "boot")
        self.assertEqual(rearm_check.EXIT_PASS, code)
        self.assertIn("does NOT show", self.out.getvalue())

    def test_fail_when_the_alarms_never_come_back_after_boot(self):
        self.assertEqual(rearm_check.EXIT_FAIL, rearm_check.run_check(FakeDevice(restores="none"), "boot"))
        self.assertIn("FAIL", self.out.getvalue())

    def test_history_saying_the_alarms_were_set_after_the_reboot_is_reported(self):
        code = rearm_check.run_check(FakeDevice(history_rtc="2026-09-20 18:05:00.000"), "boot")
        self.assertEqual(rearm_check.EXIT_PASS, code)
        self.assertIn("set AFTER the reboot", self.out.getvalue())

    def test_history_older_than_the_reboot_means_the_alarms_may_not_have_been_re_armed(self):
        code = rearm_check.run_check(FakeDevice(history_rtc="2026-09-20 17:00:00.000"), "boot")
        self.assertEqual(rearm_check.EXIT_FAIL, code)
        self.assertIn("BEFORE the reboot", self.out.getvalue())


class PhoneSafetyTest(ClockedTest):
    PHONE = "R52WA025A5R"

    def test_a_phone_is_never_touched_without_an_interactive_confirmation(self):
        phone = FakeDevice(serial=self.PHONE)
        with mock.patch("common.sys.stdin", io.StringIO("")):
            with self.assertRaises(ScriptError):
                rearm_check.run_check(phone, "foreground")
        self.assertEqual([], phone.commands, "not even a read: nothing may be sent before the confirmation")

    def test_a_wrong_phrase_cancels_and_touches_nothing(self):
        phone = FakeDevice(serial=self.PHONE)
        with mock.patch("common.sys.stdin", TerminalStdin("")), mock.patch("builtins.input", return_value="yes"):
            with self.assertRaises(ScriptError):
                rearm_check.run_check(phone, "boot")
        self.assertEqual([], phone.commands)

    def test_the_confirmation_names_what_will_happen(self):
        text = rearm_check.describe_plan(FakeDevice(serial=self.PHONE), "foreground")
        self.assertIn("FORCE-STOP", text)
        self.assertIn(self.PHONE, text)
        self.assertIn("REBOOT", rearm_check.describe_plan(FakeDevice(serial=self.PHONE), "boot"))

    def test_with_the_phrase_typed_a_phone_boot_check_asks_you_to_unlock_it(self):
        phone = FakeDevice(serial=self.PHONE)
        with mock.patch("common.sys.stdin", TerminalStdin("")), mock.patch("rearm_check.sys.stdin", TerminalStdin("")), \
                mock.patch("builtins.input", side_effect=["REARM-CHECK", ""]) as fake_input:
            self.assertEqual(rearm_check.EXIT_PASS, rearm_check.run_check(phone, "boot"))
        prompts = [call.args[0] for call in fake_input.call_args_list]
        self.assertTrue(any("Unlock the phone" in p for p in prompts), prompts)


class UnreadableDumpTest(ClockedTest):
    """A dump that can't be read is an ERROR (exit 1): never 'no alarms' (a precondition, exit 2) and never a pass."""

    def test_an_unreadable_dump_is_an_error_not_a_precondition(self):
        with self.assertRaises(ScriptError) as ctx:
            rearm_check.require_alarms(UnreadableDumpDevice())
        self.assertNotIsInstance(ctx.exception, rearm_check.PreconditionFailed)
        self.assertIn("NOT understood", str(ctx.exception))

    def test_main_exits_1_for_an_unreadable_dump(self):
        with mock.patch.object(rearm_check, "connect", return_value=UnreadableDumpDevice()), mock.patch("sys.stderr", io.StringIO()):
            self.assertEqual(rearm_check.EXIT_FAIL, rearm_check.main(["--mode", "foreground"]))

    def test_the_real_api29_dump_yields_five_alarms_to_check(self):
        self.assertEqual(5, len(rearm_check.require_alarms(Api29Device())))


if __name__ == "__main__":
    unittest.main()
