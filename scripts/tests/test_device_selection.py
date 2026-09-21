"""Tests for how scripts choose a device (safety rule 1). No adb or device needed: these use fake `adb devices` output.

Run:  python -m unittest discover -s scripts/tests -v
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common import (  # noqa: E402
    Adb, Device, ScriptError, is_emulator_serial, parse_adb_devices, remove_device_temp, select_target,
)

EMU = "emulator-5554"
EMU2 = "emulator-5556"
PHONE = "R52WA025A5R"
PHONE2 = "R3CY505PDZM"


def devices(*pairs):
    return [Device(serial, state) for serial, state in pairs]


class ParseTest(unittest.TestCase):
    def test_parses_adb_devices_output(self):
        text = "List of devices attached\nemulator-5554\tdevice\nR52WA025A5R\tunauthorized\n\n"
        self.assertEqual([Device(EMU, "device"), Device(PHONE, "unauthorized")], parse_adb_devices(text))

    def test_ignores_daemon_chatter_and_windows_line_endings(self):
        text = "* daemon not running; starting now at tcp:5037\r\n* daemon started successfully\r\nList of devices attached\r\nemulator-5554\tdevice\r\n"
        self.assertEqual([Device(EMU, "device")], parse_adb_devices(text))

    def test_empty_listing(self):
        self.assertEqual([], parse_adb_devices("List of devices attached\n\n"))

    def test_emulator_serial_detection(self):
        self.assertTrue(is_emulator_serial("emulator-5554"))
        self.assertFalse(is_emulator_serial("R52WA025A5R"))


class SelectTargetTest(unittest.TestCase):

    def test_one_emulator_alone_is_used(self):
        self.assertEqual(EMU, select_target(devices((EMU, "device"))))

    def test_one_emulator_plus_a_phone_uses_the_emulator_never_the_phone(self):
        self.assertEqual(EMU, select_target(devices((PHONE, "device"), (EMU, "device"))))

    def test_a_lone_physical_phone_is_refused_without_an_explicit_serial(self):
        with self.assertRaises(ScriptError) as ctx:
            select_target(devices((PHONE, "device")))
        self.assertIn("--device", str(ctx.exception))
        self.assertIn(PHONE, str(ctx.exception))  # lists what is attached

    def test_two_emulators_are_refused_and_listed(self):
        with self.assertRaises(ScriptError) as ctx:
            select_target(devices((EMU, "device"), (EMU2, "device")))
        self.assertIn(EMU, str(ctx.exception))
        self.assertIn(EMU2, str(ctx.exception))

    def test_two_phones_are_refused(self):
        with self.assertRaises(ScriptError):
            select_target(devices((PHONE, "device"), (PHONE2, "device")))

    def test_nothing_attached_is_refused(self):
        with self.assertRaises(ScriptError):
            select_target([])

    def test_offline_or_unauthorized_devices_are_not_usable(self):
        with self.assertRaises(ScriptError):
            select_target(devices((EMU, "offline")))
        with self.assertRaises(ScriptError):
            select_target(devices((PHONE, "unauthorized")), PHONE)

    def test_an_explicit_serial_selects_a_phone_deliberately(self):
        self.assertEqual(PHONE, select_target(devices((EMU, "device"), (PHONE, "device")), PHONE))

    def test_an_explicit_serial_that_isnt_attached_is_refused(self):
        with self.assertRaises(ScriptError) as ctx:
            select_target(devices((EMU, "device")), "nope")
        self.assertIn("nope", str(ctx.exception))

    def test_two_emulators_can_be_disambiguated_explicitly(self):
        self.assertEqual(EMU2, select_target(devices((EMU, "device"), (EMU2, "device")), EMU2))


class RemoveDeviceTempTest(unittest.TestCase):
    """remove_device_temp may only ever delete this tool's own temp files."""

    class FakeAdb(Adb):
        def __init__(self):
            super().__init__("adb", "emulator-5554")
            self.commands = []

        def shell(self, command, allow_fail=False, timeout=120):
            self.commands.append(command)

    def test_deletes_only_its_own_temp_files(self):
        fake = self.FakeAdb()
        remove_device_temp(fake, "park-backup-abc123-0.bin")
        self.assertEqual(["rm -f /data/local/tmp/park-backup-abc123-0.bin"], fake.commands)

    def test_refuses_anything_else(self):
        fake = self.FakeAdb()
        for bad in ("../../data/data/com.example.park/databases/park_database", "park_database",
                    "park-backup-x; reboot", "other.bin", "/data/local/tmp/park-backup-1", ""):
            with self.subTest(name=bad), self.assertRaises(ScriptError):
                remove_device_temp(fake, bad)
        self.assertEqual([], fake.commands)


if __name__ == "__main__":
    unittest.main()
