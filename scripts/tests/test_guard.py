"""Guard test: fails if any script under scripts/ contains a command the safety rules forbid.

Why this exists: on a physical phone, uninstalling the app, clearing its data, or writing into its
data folders destroys the owner's real data. These scripts are only allowed to READ app data. A future
edit (by a person or by Claude) could add a forbidden command by accident, so this test scans the
source and fails loudly.

It also tests ITSELF: each pattern is checked against a bad example it must flag and a legitimate
example it must not, and one case writes a forbidden line into a temporary script and confirms the
scan fails - the "temporarily add a forbidden string" check from the plan, automated.

Run:  python -m unittest discover -s scripts/tests -v
"""

import re
import sys
import tempfile
import unittest
from pathlib import Path
from typing import List, Tuple

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
THIS_FILE = Path(__file__).resolve()

# (name, regex). Case-insensitive; applied to every line of every script, comments included, so a
# forbidden command can't hide in a string or a "just for now" comment.
FORBIDDEN: List[Tuple[str, str]] = [
    ("uninstall", r"\buninstall\b"),
    ("pm clear (wipes app data)", r"\bpm\s+clear\b|[\"']pm[\"']\s*,\s*[\"']clear[\"']"),
    ("connected(Debug)AndroidTest (uninstalls the app afterwards)", r"connected(Debug|Release)?AndroidTest"),
    ("run-as ... rm/mv/cp/tee/dd/truncate/touch/mkdir (writes app data)",
     r"run-as\s+\S+\s+(rm|mv|cp|tee|dd|truncate|touch|mkdir|chmod)\b"
     r"|[\"']run-as[\"']\s*,[^\n]*[\"'](rm|mv|cp|tee|dd|truncate|touch|mkdir|chmod)[\"']"),
    ("redirect INTO databases/ or files/", r">\s*[\"']?/?(data/data/[\w.]+/)?(databases|files)/"),
    ("cp/mv/tee/dd targeting databases/ or files/", r"\b(cp|mv|tee|dd)\b[^\n]*\b(databases|files)/"),
]

# A device-side `rm` is allowed ONLY on the script's own temp files in /data/local/tmp/. Checked
# per line: an `rm` word on a line that doesn't mention that folder is a violation.
RM_WORD = re.compile(r"\brm\b", re.IGNORECASE)
TMP_DIR = "/data/local/tmp/"

SCANNED_SUFFIXES = {".py", ".ps1", ".bat", ".cmd", ".sh"}


def find_violations(text: str) -> List[Tuple[int, str, str]]:
    """Return (line number, rule name, the line) for every forbidden command in `text`."""
    found: List[Tuple[int, str, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        for name, pattern in FORBIDDEN:
            if re.search(pattern, line, re.IGNORECASE):
                found.append((number, name, line.strip()))
        if RM_WORD.search(line) and TMP_DIR not in line:
            found.append((number, "rm outside /data/local/tmp/", line.strip()))
    return found


def scan_folder(folder: Path, skip: Path = THIS_FILE) -> List[str]:
    """Scan every script in `folder` (recursively), except `skip` (this file, which lists the patterns)."""
    problems: List[str] = []
    for path in sorted(folder.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SCANNED_SUFFIXES or path.resolve() == skip:
            continue
        if "__pycache__" in path.parts:
            continue
        for number, name, line in find_violations(path.read_text(encoding="utf-8", errors="replace")):
            problems.append(f"{path.relative_to(folder)}:{number}: [{name}] {line}")
    return problems


class GuardTest(unittest.TestCase):

    def test_scripts_contain_no_forbidden_commands(self):
        problems = scan_folder(SCRIPTS_DIR)
        self.assertEqual([], problems, "forbidden commands found in scripts/:\n" + "\n".join(problems))

    # ---- the patterns catch what they should (each bad example is a real forbidden command) ----

    BAD = {
        "adb uninstall com.example.park": "uninstall",
        "adb.run('shell', 'pm uninstall com.example.park')": "uninstall",
        "adb shell pm clear com.example.park": "pm clear",
        "['shell', 'pm', 'clear', 'com.example.park']": "pm clear",
        "gradlew connectedDebugAndroidTest": "connected",
        "gradlew connectedAndroidTest": "connected",
        "adb shell run-as com.example.park rm databases/park_database": "run-as",
        "adb shell run-as com.example.park cp /sdcard/x databases/park_database": "run-as",
        "['run-as', 'com.example.park', 'mv', 'a', 'b']": "run-as",
        "adb shell \"cat /sdcard/x > /data/data/com.example.park/databases/park_database\"": "redirect",
        "cmd = 'cat x > files/datastore/settings.preferences_pb'": "redirect",
        "adb shell tee databases/park_database": "cp/mv/tee/dd",
        "adb shell rm -rf /sdcard/x": "rm outside",
        "adb shell rm databases/park_database": "rm outside",
    }

    OK = [
        # The legitimate backup copy: reads app data, writes only into the device's own temp folder.
        "adb shell \"run-as com.example.park cat databases/park_database > /data/local/tmp/park-backup-1.bin\"",
        "adb.shell(f'rm -f /data/local/tmp/{name}')",
        "run-as com.example.park find databases files/datastore -type f",
        "run-as com.example.park stat -c %s databases/park_database",
        "print('backups are gitignored')",
        "# firmware and format are not forbidden words",
    ]

    def test_every_bad_example_is_flagged(self):
        for line, expected in self.BAD.items():
            with self.subTest(line=line):
                hits = find_violations(line)
                self.assertTrue(hits, f"not flagged: {line}")
                self.assertTrue(any(expected.lower() in name.lower() for _, name, _ in hits),
                                f"flagged, but not for '{expected}': {hits}")

    def test_legitimate_commands_are_not_flagged(self):
        for line in self.OK:
            with self.subTest(line=line):
                self.assertEqual([], find_violations(line))

    def test_adding_a_forbidden_line_to_a_script_makes_the_scan_fail(self):
        """The plan's check: temporarily add a forbidden string, watch the guard fail, then it's gone again."""
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            good = folder / "fine.py"
            good.write_text("adb.shell('run-as com.example.park cat databases/park_database > /data/local/tmp/park-backup-1')\n")
            self.assertEqual([], scan_folder(folder, skip=Path("nothing")))

            bad = folder / "sneaky.py"
            bad.write_text("import os\nos.system('adb uninstall com.example.park')\n")
            problems = scan_folder(folder, skip=Path("nothing"))
            self.assertEqual(1, len(problems))
            self.assertIn("sneaky.py:2", problems[0])

            bad.unlink()
            self.assertEqual([], scan_folder(folder, skip=Path("nothing")))


if __name__ == "__main__":
    unittest.main()
