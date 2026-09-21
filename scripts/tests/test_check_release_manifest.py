"""Tests for scripts/check_release_manifest.py: the pure checks, on hand-built APK zips (no aapt2 or Gradle needed).

The script is also run for real against the built debug and release APKs (see the commit message); these tests pin the
logic so it can't quietly stop noticing the receiver.

Run:  python -m unittest discover -s scripts/tests -v
"""

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_release_manifest as crm  # noqa: E402

DEBUG_MANIFEST = """
      E: receiver (line=20)
        A: android:name(0x01010003)="com.example.park.DebugControlReceiver" (Raw: ".DebugControlReceiver")
        A: android:exported(0x01010010)=true
        E: intent-filter (line=22)
          E: action (line=23)
            A: android:name(0x01010003)="com.example.park.debug.PARK" (Raw: "com.example.park.debug.PARK")
"""
RELEASE_MANIFEST = """
      E: receiver (line=20)
        A: android:name(0x01010003)="com.example.park.BootReceiver" (Raw: ".BootReceiver")
        A: android:exported(0x01010010)=false
"""


def make_apk(directory: Path, name: str, dex_contents) -> Path:
    path = directory / name
    with zipfile.ZipFile(path, "w") as apk:
        apk.writestr("AndroidManifest.xml", b"binary")
        for dex_name, data in dex_contents.items():
            apk.writestr(dex_name, data)
        apk.writestr("resources.arsc", b"DebugControlReceiver in a non-dex file must not count")
    return path


class ManifestFindingsTest(unittest.TestCase):
    def test_finds_both_markers_in_a_debug_manifest(self):
        self.assertEqual(["DebugControlReceiver", "com.example.park.debug."], crm.manifest_findings(DEBUG_MANIFEST))

    def test_a_release_manifest_is_clean(self):
        self.assertEqual([], crm.manifest_findings(RELEASE_MANIFEST))

    def test_either_marker_alone_is_enough_to_be_flagged(self):
        self.assertEqual(["com.example.park.debug."], crm.manifest_findings('action="com.example.park.debug.REARM"'))
        self.assertEqual(["DebugControlReceiver"], crm.manifest_findings('name=".DebugControlReceiver"'))


class DexFindingsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_names_the_dex_files_that_contain_the_receiver(self):
        apk = make_apk(self.dir, "debug.apk", {"classes.dex": b"clean", "classes3.dex": b"xx Lcom/example/park/DebugControlReceiver; xx"})
        self.assertEqual(["classes3.dex"], crm.dex_findings(apk))

    def test_a_clean_apk_has_no_findings_and_non_dex_files_are_ignored(self):
        apk = make_apk(self.dir, "release.apk", {"classes.dex": b"Lcom/example/park/BootReceiver;", "classes2.dex": b"more"})
        self.assertEqual([], crm.dex_findings(apk))


class EvaluateTest(unittest.TestCase):
    def test_release_with_no_hits_passes(self):
        self.assertEqual([], crm.evaluate([], [], expect_debug=False))

    def test_release_with_a_manifest_hit_or_a_code_hit_fails(self):
        self.assertTrue(crm.evaluate(["DebugControlReceiver"], [], expect_debug=False))
        self.assertTrue(crm.evaluate([], ["classes2.dex"], expect_debug=False))
        self.assertEqual(2, len(crm.evaluate(["DebugControlReceiver"], ["classes.dex"], expect_debug=False)))

    def test_expect_debug_requires_the_receiver_in_both_places(self):
        self.assertEqual([], crm.evaluate(["DebugControlReceiver"], ["classes.dex"], expect_debug=True))
        self.assertEqual(2, len(crm.evaluate([], [], expect_debug=True)))
        self.assertEqual(1, len(crm.evaluate(["DebugControlReceiver"], [], expect_debug=True)))


class SourceLayoutTest(unittest.TestCase):
    """The receiver must live ONLY in the debug source set - that is what keeps it out of release in the first place."""

    ROOT = Path(__file__).resolve().parent.parent.parent

    def test_the_receiver_source_and_manifest_are_in_the_debug_source_set_only(self):
        self.assertTrue((self.ROOT / "app/src/debug/java/com/example/park/DebugControlReceiver.kt").is_file())
        debug_manifest = (self.ROOT / "app/src/debug/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("DebugControlReceiver", debug_manifest)
        main_manifest = (self.ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn("DebugControlReceiver", main_manifest)
        self.assertNotIn("com.example.park.debug.", main_manifest)
        self.assertFalse(list((self.ROOT / "app/src/main").rglob("DebugControlReceiver*")))


if __name__ == "__main__":
    unittest.main()
