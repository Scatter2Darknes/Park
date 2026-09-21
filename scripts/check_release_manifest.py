"""Prove that a RELEASE APK contains no trace of the debug-only control receiver.

    python scripts/check_release_manifest.py                  # every APK under app/build/outputs/apk/release/
    python scripts/check_release_manifest.py path\\to\\some.apk
    python scripts/check_release_manifest.py --expect-debug app\\build\\outputs\\apk\\debug\\app-debug.apk

Why: DebugControlReceiver lets anyone with adb park, unpark and re-arm the app. It is exported (so adb can reach it),
which is fine on an emulator and a bug in something shipped. It lives in app/src/debug/, so it should only ever be
compiled and merged into the debug variant; this check confirms it in the artifact you would actually install.

Two things are checked in each APK:
  1. the compiled manifest (read with the SDK's aapt2): no DebugControlReceiver and no "com.example.park.debug." action;
  2. the compiled code: no class named DebugControlReceiver in any classes*.dex.
It also checks the merged release manifest Gradle wrote, if there is one.

--expect-debug flips the check to make sure the CHECKER works: on a debug APK it must FIND the receiver in both places.
Exit code 0 = as expected, 1 = not.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path
from typing import List, Optional

from common import ScriptError, repo_root, resolve_adb, run_process, setup_console

MANIFEST_MARKERS = ["DebugControlReceiver", "com.example.park.debug."]
DEX_MARKER = b"DebugControlReceiver"


# ---------------------------------------------------------------------------------------------
# Pure checks (unit-tested)
# ---------------------------------------------------------------------------------------------

def manifest_findings(manifest_text: str) -> List[str]:
    """The debug-receiver markers that appear in a manifest's text."""
    return [marker for marker in MANIFEST_MARKERS if marker in manifest_text]


def dex_findings(apk_path: Path) -> List[str]:
    """Names of the classes*.dex files in the APK that mention the debug receiver."""
    found: List[str] = []
    with zipfile.ZipFile(apk_path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex") and DEX_MARKER in apk.read(name):
                found.append(name)
    return found


def evaluate(manifest_hits: List[str], dex_hits: List[str], expect_debug: bool) -> List[str]:
    """Problems for one APK: with expect_debug False (a release build) any hit is a problem; with True, a MISSING hit is."""
    problems: List[str] = []
    if expect_debug:
        if not manifest_hits:
            problems.append("the manifest does NOT contain the debug receiver (the checker or the debug build is broken)")
        if not dex_hits:
            problems.append("the code does NOT contain the debug receiver (the checker or the debug build is broken)")
    else:
        if manifest_hits:
            problems.append("the manifest contains: " + ", ".join(manifest_hits))
        if dex_hits:
            problems.append("the code contains DebugControlReceiver (" + ", ".join(dex_hits) + ")")
    return problems


# ---------------------------------------------------------------------------------------------
# Finding tools and files
# ---------------------------------------------------------------------------------------------

def find_aapt2(explicit: Optional[str] = None) -> str:
    if explicit:
        if Path(explicit).is_file():
            return explicit
        raise ScriptError(f"--aapt2 file doesn't exist: {explicit}")
    sdk = Path(resolve_adb()).resolve().parent.parent          # <sdk>/platform-tools/adb.exe -> <sdk>
    candidates = sorted((sdk / "build-tools").glob("*/aapt2*"), key=lambda p: [int(x) if x.isdigit() else 0 for x in p.parent.name.split(".")])
    if not candidates:
        raise ScriptError(f"Couldn't find aapt2 under {sdk / 'build-tools'} - install Android SDK Build-Tools, or pass --aapt2.")
    return str(candidates[-1])


def manifest_of(apk: Path, aapt2: str) -> str:
    result = run_process([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], timeout=60)
    if result.returncode != 0:
        raise ScriptError(f"aapt2 couldn't read the manifest of {apk.name}: {(result.err or result.out).strip()}")
    return result.out


def default_apks() -> List[Path]:
    folder = repo_root() / "app" / "build" / "outputs" / "apk" / "release"
    apks = sorted(folder.glob("*.apk")) if folder.is_dir() else []
    if not apks:
        raise ScriptError(f"No release APK under {folder}. Build one first:  gradlew assembleRelease")
    return apks


def merged_release_manifests() -> List[Path]:
    root = repo_root() / "app" / "build" / "intermediates" / "merged_manifests" / "release"
    return sorted(root.rglob("AndroidManifest.xml")) if root.is_dir() else []


def main(argv: Optional[List[str]] = None) -> int:
    setup_console()
    parser = argparse.ArgumentParser(description="Check a release APK has no debug-only receiver.")
    parser.add_argument("apk", nargs="*", type=Path, help="APK(s) to check (default: the release APKs Gradle built)")
    parser.add_argument("--expect-debug", action="store_true", help="expect the receiver to be PRESENT (to test the checker on a debug APK)")
    parser.add_argument("--aapt2", help="path to aapt2 (default: the newest in the SDK's build-tools)")
    args = parser.parse_args(argv)

    try:
        aapt2 = find_aapt2(args.aapt2)
        apks = args.apk or default_apks()
        ok = True
        for apk in apks:
            problems = evaluate(manifest_findings(manifest_of(apk, aapt2)), dex_findings(apk), args.expect_debug)
            ok = ok and not problems
            print(f"{'ok  ' if not problems else 'FAIL'} {apk.name}" + ("" if not problems else "\n       " + "\n       ".join(problems)))
        if not args.apk and not args.expect_debug:
            for path in merged_release_manifests():
                hits = manifest_findings(path.read_text(encoding="utf-8", errors="replace"))
                ok = ok and not hits
                print(f"{'ok  ' if not hits else 'FAIL'} merged manifest {path.relative_to(repo_root())}" + ("" if not hits else f" contains: {hits}"))
    except ScriptError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print("PASS: as expected." if ok else "FAIL: see above.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
