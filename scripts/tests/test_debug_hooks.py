"""Tests for scripts/debug_hooks.py, the driver for the debug-only control receiver, and rearm_check's --scenario option.
A fake device stands in for adb, so the safety rules (emulator-only for anything that changes state) are checked without
a device.

Run:  python -m unittest discover -s scripts/tests -v
"""

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import debug_hooks  # noqa: E402
import rearm_check  # noqa: E402
from common import Adb, Result, ScriptError  # noqa: E402


class FakeDevice(Adb):
    """Answers `date`, accepts broadcasts, and 'logs' a ParkDebug reply for each one (unless silent)."""

    def __init__(self, serial="emulator-5554", replies=None, delivered=True, silent=False):
        super().__init__("adb", serial)
        self.commands = []
        self.replies = replies or {}
        self.delivered = delivered
        self.silent = silent
        self.log = []

    def shell(self, command, allow_fail=False, timeout=120):
        self.commands.append(command)
        if command.startswith("date"):
            return Result(0, "09-20 19:00:00.000\n", "")
        if command.startswith("am broadcast"):
            if not self.delivered:
                return Result(0, "Error: Activity not started, unable to resolve Intent", "")
            action = command.split("-a com.example.park.debug.")[1].split()[0]
            if not self.silent:
                self.log += self.replies.get(action, [f"{action}: ok"])
            return Result(0, "Broadcasting: Intent\nBroadcast completed: result=0\n", "")
        raise AssertionError(f"unexpected command: {command}")

    def run(self, *args, allow_fail=False, timeout=120):
        self.commands.append(" ".join(args))
        if args[0] == "logcat":
            return Result(0, "\n".join(f"09-20 19:00:01.000  1  2 I ParkDebug: {m}" for m in self.log) + "\n", "")
        raise AssertionError(f"unexpected adb call: {args}")


class HooksTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(debug_hooks, "REPLY_TIMEOUT", 0.05)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = mock.patch("time.sleep", lambda s: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_the_broadcast_command_names_the_receiver_action_and_typed_extras(self):
        device = FakeDevice(replies={"PARK": ["PARK: car 1 saved on 03rd Ave"]})
        debug_hooks.park(device, 3, 37.7802, -122.461)
        command = next(c for c in device.commands if c.startswith("am broadcast"))
        self.assertIn("-n com.example.park/.DebugControlReceiver", command)
        self.assertIn("-a com.example.park.debug.PARK", command)
        self.assertIn("--el carId 3", command)        # a long
        self.assertIn("--ed lat 37.7802", command)    # doubles
        self.assertIn("--ed lng -122.461", command)

    def test_dump_returns_the_reply_lines(self):
        device = FakeDevice(replies={"DUMP_STATE": ["DUMP_STATE begin: now=x", "cars: id=1", "DUMP_STATE end: the app expects 3 future alarm(s)"]})
        lines = debug_hooks.dump_state(device)
        self.assertEqual(3, len(lines))
        self.assertEqual("DUMP_STATE end: the app expects 3 future alarm(s)", debug_hooks.message_of(lines[-1]))

    def test_park_that_finds_no_street_is_an_error_not_a_silent_success(self):
        device = FakeDevice(replies={"PARK": ["PARK: no street segment within 30 m of 0.0,0.0 (confidence=NO_MATCH) - nothing saved"]})
        with self.assertRaises(ScriptError) as ctx:
            debug_hooks.park(device, 1, 0.0, 0.0)
        self.assertIn("no street segment", str(ctx.exception))

    def test_a_silent_receiver_means_a_release_build_or_an_unopened_app(self):
        with self.assertRaises(ScriptError) as ctx:
            debug_hooks.rearm(FakeDevice(silent=True))
        self.assertIn("DEBUG build", str(ctx.exception))

    def test_an_undelivered_broadcast_is_reported(self):
        with self.assertRaises(ScriptError):
            debug_hooks.rearm(FakeDevice(delivered=False))

    def test_park_is_not_confused_by_an_unpark_reply(self):
        device = FakeDevice(replies={"PARK": ["UNPARK: car 1 cleared"]})  # only an UNPARK-looking line is logged
        with self.assertRaises(ScriptError):
            debug_hooks.park(device, 1, 37.78, -122.46)

    # ---- safety: anything that changes state is emulator-only ----

    def test_state_changing_hooks_refuse_a_physical_phone_before_sending_anything(self):
        phone = FakeDevice(serial="R52WA025A5R")
        for action in (lambda: debug_hooks.park(phone, 1, 37.78, -122.46), lambda: debug_hooks.unpark(phone, 1), lambda: debug_hooks.rearm(phone)):
            with self.assertRaises(ScriptError) as ctx:
                action()
            self.assertIn("only runs on an emulator", str(ctx.exception))
        self.assertEqual([], phone.commands)

    def test_dump_is_read_only_so_it_is_allowed_on_a_named_phone(self):
        phone = FakeDevice(serial="R52WA025A5R", replies={"DUMP_STATE": ["DUMP_STATE end: 0"]})
        self.assertEqual(1, len(debug_hooks.dump_state(phone)))


class ScenarioTest(unittest.TestCase):
    """rearm_check --scenario: set the scene through the hooks, then check."""

    def setUp(self):
        for name, value in (("sleep", lambda s: None), ("monotonic", iter(range(10_000)).__next__)):
            patcher = mock.patch.object(rearm_check, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_no_scenario_does_nothing(self):
        device = FakeDevice()
        rearm_check.apply_scenario(device, None, 1, 0, 0)
        self.assertEqual([], device.commands)

    def test_park_scenario_parks_then_waits_for_alarms(self):
        with mock.patch.object(debug_hooks, "park") as park, mock.patch.object(rearm_check, "live_keys", return_value={("R", 1)}):
            rearm_check.apply_scenario(FakeDevice(), "park", 2, 37.7, -122.4)
        park.assert_called_once()
        self.assertEqual((2, 37.7, -122.4), park.call_args.args[1:])

    def test_park_scenario_that_produces_no_alarms_is_a_precondition_failure(self):
        with mock.patch.object(debug_hooks, "park"), mock.patch.object(rearm_check, "live_keys", return_value=set()):
            with self.assertRaises(rearm_check.PreconditionFailed):
                rearm_check.apply_scenario(FakeDevice(), "park", 1, 37.7, -122.4)

    def test_unpark_scenario_clears_the_car(self):
        with mock.patch.object(debug_hooks, "unpark") as unpark:
            rearm_check.apply_scenario(FakeDevice(), "unpark", 4, 0, 0)
        unpark.assert_called_once()

    def test_a_scenario_on_a_phone_is_refused_by_the_hooks(self):
        phone = FakeDevice(serial="R52WA025A5R")
        with self.assertRaises(ScriptError):
            rearm_check.apply_scenario(phone, "park", 1, 37.78, -122.46)
        self.assertEqual([], phone.commands)


if __name__ == "__main__":
    unittest.main()
