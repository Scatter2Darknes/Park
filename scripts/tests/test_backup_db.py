"""Tests for scripts/backup_db.py. No device needed: a fake device stands in for adb, so the failure paths
(truncated copy, missing database, release build, phone without confirmation) can be exercised safely.

Run:  python -m unittest discover -s scripts/tests -v
"""

import hashlib
import io
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import backup_db  # noqa: E402
from common import DEVICE_TMP_DIR, PACKAGE, Adb, Result, ScriptError  # noqa: E402

SQLITE = b"SQLite format 3\x00" + b"\x01" * 100


class ParsingTest(unittest.TestCase):
    def test_file_list_keeps_only_safe_app_paths(self):
        text = "\n".join([
            "databases/park_database", "databases/park_database-wal", "files/datastore/settings.preferences_pb",
            "find: files/datastore: No such file or directory",
            "databases/has space", "databases/quote'd", "/etc/passwd", "databases/../secret", "cache/other", "",
        ])
        self.assertEqual(
            ["databases/park_database", "databases/park_database-wal", "files/datastore/settings.preferences_pb"],
            backup_db.parse_file_list(text),
        )

    def test_size_from_stat_or_ls(self):
        self.assertEqual(4096, backup_db.parse_size("4096\n"))
        self.assertEqual(53248, backup_db.parse_size("-rw------- 1 u0_a172 u0_a172 53248 2026-09-20 15:00 park_database"))
        self.assertIsNone(backup_db.parse_size("stat: no such file"))
        self.assertIsNone(backup_db.parse_size(""))

    def test_sqlite_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = Path(tmp) / "good"; good.write_bytes(SQLITE)
            bad = Path(tmp) / "bad"; bad.write_bytes(b"not a database at all")
            self.assertTrue(backup_db.has_sqlite_header(good))
            self.assertFalse(backup_db.has_sqlite_header(bad))
            self.assertFalse(backup_db.has_sqlite_header(Path(tmp) / "missing"))

    def test_package_info(self):
        text = "    versionCode=1 minSdk=29 targetSdk=37\n    versionName=1.011\n    firstInstallTime=2026-09-20 10:00:00\n" \
               "    lastUpdateTime=2026-09-20 18:00:00\n    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]"
        info = backup_db.parse_package_info(text)
        self.assertEqual("1.011", info["versionName"])
        self.assertEqual("1", info["versionCode"])
        self.assertEqual("2026-09-20 18:00:00", info["lastUpdateTime"])
        self.assertEqual("yes", info["debuggable"])


class FakeDevice(Adb):
    """Just enough of a phone to run the backup: an app folder, a temp folder, and the few commands the script uses."""

    def __init__(self, files, serial="emulator-5554", debuggable=True, truncate_pulls=False,
                 restarts=0, has_sha256sum=True, mutate_on_copy=False):
        super().__init__("adb", serial)
        self.restarts_left = restarts          # how many times the "app" comes straight back after a force-stop
        self.has_sha256sum = has_sha256sum
        self.mutate_on_copy = mutate_on_copy   # change a file IN PLACE (same size) while it is being copied
        self.app_files = dict(files)      # relative path -> bytes
        self.tmp_files = {}               # temp name -> bytes
        self.commands = []
        self.debuggable = debuggable
        self.truncate_pulls = truncate_pulls

    def shell(self, command, allow_fail=False, timeout=120):
        self.commands.append(command)
        run_as = f"run-as {PACKAGE} "
        if command.startswith("getprop"):
            return Result(0, "0\n", "")  # a phone: ro.kernel.qemu is not 1
        if command.startswith("pidof"):
            if self.restarts_left > 0:
                self.restarts_left -= 1
                return Result(0, "12345\n", "")
            return Result(1, "", "")
        if command.startswith("pm list packages"):
            return Result(0, f"package:{PACKAGE}\n", "")
        if command.startswith("dumpsys package"):
            return Result(0, "versionName=1.011\nversionCode=1 minSdk=29\nlastUpdateTime=2026-09-20 18:00:00\nflags=[ DEBUGGABLE ]", "")
        if command.startswith("am force-stop"):
            return Result(0, "", "")
        if command.startswith("rm -f /data/local/tmp/"):
            self.tmp_files.pop(command.rsplit("/", 1)[1], None)
            return Result(0, "", "")
        if command.startswith(run_as):
            if not self.debuggable:
                return Result(1, "", f"run-as: package not debuggable: {PACKAGE}")
            rest = command[len(run_as):]
            if rest == "id":
                return Result(0, "uid=10172(u0_a172)\n", "")
            if rest.startswith("find "):
                return Result(0, "\n".join(sorted(self.app_files)) + "\n", "")
            if rest.startswith("stat -c %s "):
                name = rest.split()[-1]
                return Result(0, f"{len(self.app_files[name])}\n", "") if name in self.app_files else Result(1, "", "no such file")
            if rest.startswith("sha256sum "):
                if not self.has_sha256sum:
                    return Result(127, "", "sha256sum: not found")
                name = rest.split()[-1]
                return Result(0, f"{hashlib.sha256(self.app_files[name]).hexdigest()}  {name}\n", "")
            if rest.startswith("cat ") and " > " + DEVICE_TMP_DIR + "/" in rest:
                source, target = rest[4:].split(" > ")
                self.tmp_files[target.rsplit("/", 1)[1]] = self.app_files[source]
                if self.mutate_on_copy:  # the app rewrites the file right after it was read
                    self.app_files[source] = bytes(b ^ 0x01 for b in self.app_files[source])
                return Result(0, "", "")
        raise AssertionError(f"unexpected device command: {command}")

    def run(self, *args, allow_fail=False, timeout=120):
        if args[0] == "pull":
            data = self.tmp_files[args[1].rsplit("/", 1)[1]]
            if self.truncate_pulls:
                data = data[:-5]
            Path(args[2]).write_bytes(data)
            return Result(0, "1 file pulled", "")
        raise AssertionError(f"unexpected adb call: {args}")


GOOD_FILES = {
    "databases/park_database": SQLITE,
    "databases/park_database-wal": b"wal-bytes" * 10,
    "files/datastore/settings.preferences_pb": b"\x0a\x03abc",
}


class BackupTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.out = Path(self._tmp.name)
        patcher = mock.patch.object(backup_db, "SETTLE_SECONDS", 0)  # don't really wait in tests
        patcher.start()
        self.addCleanup(patcher.stop)

    def tearDown(self):
        self._tmp.cleanup()

    def only_backup_folder(self):
        folders = [p for p in self.out.iterdir() if p.is_dir()]
        self.assertEqual(1, len(folders))
        return folders[0]

    def test_a_good_backup_passes_and_writes_a_manifest(self):
        device = FakeDevice(GOOD_FILES)
        self.assertTrue(backup_db.run_backup(device, self.out, no_stop=False))
        folder = self.only_backup_folder()
        for rel, data in GOOD_FILES.items():
            self.assertEqual(data, (folder / rel).read_bytes())
        manifest = (folder / "manifest.txt").read_text(encoding="utf-8")
        self.assertIn("result:           PASS", manifest)
        self.assertIn("versionName:      1.011", manifest)
        self.assertIn("emulator-5554 (emulator)", manifest)
        self.assertIn("databases/park_database", manifest)
        self.assertEqual({}, device.tmp_files, "temp files on the device must be cleaned up")
        self.assertIn(f"am force-stop {PACKAGE}", device.commands)

    def test_only_the_scripts_own_temp_files_are_ever_deleted(self):
        device = FakeDevice(GOOD_FILES)
        backup_db.run_backup(device, self.out, no_stop=False)
        deletions = [c for c in device.commands if c.startswith("rm -f /data/local/tmp/")]
        self.assertFalse([c for c in device.commands if re.search(r"\brm\b", c) and c not in deletions],
                         "the only delete allowed is of the tool's own temp files")
        self.assertEqual(len(GOOD_FILES), len(deletions))
        for command in deletions:
            self.assertRegex(command, r"^rm -f /data/local/tmp/park-backup-[0-9a-f]{8}-\d\.bin$")
        # ...and nothing was ever written into the app's own folders.
        for command in device.commands:
            self.assertNotRegex(command, r"> (databases|files)/")

    def test_a_file_that_changes_in_place_during_the_copy_is_caught(self):
        """Same size, different content: only re-hashing on the device can notice this."""
        device = FakeDevice(GOOD_FILES, mutate_on_copy=True)
        self.assertFalse(backup_db.run_backup(device, self.out, no_stop=False))
        manifest = (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8")
        self.assertIn("result:           FAIL", manifest)
        self.assertIn("changed on the device while it was being copied", manifest)
        self.assertIn("NO - at least one file changed", manifest)

    def test_a_good_backup_reports_that_content_was_rechecked(self):
        self.assertTrue(backup_db.run_backup(FakeDevice(GOOD_FILES), self.out, no_stop=False))
        manifest = (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8")
        self.assertIn("SHA-256 on the device still matches the copy", manifest)

    def test_a_device_without_sha256sum_still_backs_up_but_says_it_couldnt_recheck(self):
        self.assertTrue(backup_db.run_backup(FakeDevice(GOOD_FILES, has_sha256sum=False), self.out, no_stop=False))
        manifest = (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8")
        self.assertIn("not possible on this device", manifest)

    def test_an_app_that_restarts_after_the_force_stop_is_stopped_again(self):
        device = FakeDevice(GOOD_FILES, restarts=2)
        self.assertTrue(backup_db.run_backup(device, self.out, no_stop=False))
        self.assertEqual(3, device.commands.count(f"am force-stop {PACKAGE}"))
        self.assertIn("confirmed not running", (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8"))

    def test_an_app_that_never_stays_down_is_reported_but_the_copy_is_still_verified(self):
        device = FakeDevice(GOOD_FILES, restarts=99)
        self.assertTrue(backup_db.run_backup(device, self.out, no_stop=False))  # nothing actually changed, so it verifies
        self.assertEqual(backup_db.STOP_ATTEMPTS, device.commands.count(f"am force-stop {PACKAGE}"))
        self.assertIn("kept restarting", (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8"))

    def test_a_truncated_copy_fails_the_size_check_and_is_reported(self):
        device = FakeDevice(GOOD_FILES, truncate_pulls=True)
        self.assertFalse(backup_db.run_backup(device, self.out, no_stop=False))
        manifest = (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8")
        self.assertIn("result:           FAIL", manifest)
        self.assertIn("size mismatch", manifest)
        self.assertEqual({}, device.tmp_files)

    def test_a_database_without_the_sqlite_header_fails(self):
        files = dict(GOOD_FILES); files["databases/park_database"] = b"this is not sqlite" * 5
        self.assertFalse(backup_db.run_backup(FakeDevice(files), self.out, no_stop=False))
        self.assertIn("SQLite format 3", (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8"))

    def test_no_stop_skips_the_force_stop_and_says_so(self):
        device = FakeDevice(GOOD_FILES)
        self.assertTrue(backup_db.run_backup(device, self.out, no_stop=True))
        self.assertNotIn(f"am force-stop {PACKAGE}", device.commands)
        self.assertIn("NO (--no-stop, or it kept restarting)",(self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8"))

    def test_a_release_build_is_refused_with_a_clear_message(self):
        with self.assertRaises(ScriptError) as ctx:
            backup_db.run_backup(FakeDevice(GOOD_FILES, debuggable=False), self.out, no_stop=False)
        self.assertIn("debug build", str(ctx.exception))
        self.assertEqual([], list(self.out.iterdir()), "no backup folder should be left behind")

    def test_a_missing_database_is_an_error_not_an_empty_pass(self):
        files = {"files/datastore/settings.preferences_pb": b"x"}
        with self.assertRaises(ScriptError) as ctx:
            backup_db.run_backup(FakeDevice(files), self.out, no_stop=False)
        self.assertIn("park_database", str(ctx.exception))

    def test_a_physical_phone_is_not_force_stopped_without_an_interactive_confirmation(self):
        phone = FakeDevice(GOOD_FILES, serial="R52WA025A5R")
        with mock.patch("common.sys.stdin", io.StringIO("")):  # not a terminal
            with self.assertRaises(ScriptError) as ctx:
                backup_db.run_backup(phone, self.out, no_stop=False)
        self.assertIn("interactive", str(ctx.exception))
        self.assertNotIn(f"am force-stop {PACKAGE}", phone.commands)

    def test_a_terminal_with_nobody_typing_counts_as_a_refusal(self):
        class SilentTerminal(io.StringIO):
            def isatty(self):
                return True
        phone = FakeDevice(GOOD_FILES, serial="R52WA025A5R")
        with mock.patch("common.sys.stdin", SilentTerminal("")), mock.patch("builtins.input", side_effect=EOFError):
            with self.assertRaises(ScriptError):
                backup_db.run_backup(phone, self.out, no_stop=False)
        self.assertNotIn(f"am force-stop {PACKAGE}", phone.commands)

    def test_typing_the_confirmation_phrase_lets_the_phone_backup_proceed(self):
        class Terminal(io.StringIO):
            def isatty(self):
                return True
        phone = FakeDevice(GOOD_FILES, serial="R52WA025A5R")
        with mock.patch("common.sys.stdin", Terminal("")), mock.patch("builtins.input", return_value="FORCE-STOP"):
            self.assertTrue(backup_db.run_backup(phone, self.out, no_stop=False))
        self.assertIn(f"am force-stop {PACKAGE}", phone.commands)

    def test_a_wrong_confirmation_cancels(self):
        class Terminal(io.StringIO):
            def isatty(self):
                return True
        phone = FakeDevice(GOOD_FILES, serial="R52WA025A5R")
        with mock.patch("common.sys.stdin", Terminal("")), mock.patch("builtins.input", return_value="yes"):
            with self.assertRaises(ScriptError):
                backup_db.run_backup(phone, self.out, no_stop=False)
        self.assertNotIn(f"am force-stop {PACKAGE}", phone.commands)

    def test_a_physical_phone_with_no_stop_needs_no_confirmation_and_is_read_only(self):
        phone = FakeDevice(GOOD_FILES, serial="R52WA025A5R")
        self.assertTrue(backup_db.run_backup(phone, self.out, no_stop=True))
        self.assertNotIn(f"am force-stop {PACKAGE}", phone.commands)
        self.assertIn("physical phone", (self.only_backup_folder() / "manifest.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
