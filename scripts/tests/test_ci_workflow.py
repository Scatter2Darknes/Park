"""Guards for the CI workflows in .github/workflows/: no secrets, read-only permissions, the right commands, and only test
reports uploaded. Text-based on purpose (no YAML library needed), and it uses PyYAML as an extra check when it is installed.

Why: the repository is private and the API keys are deliberately NOT in it. A workflow that starts referencing `secrets.`,
or uploads backups or logs as an artifact, would quietly undo that; this test makes such an edit fail loudly.

Run:  python -m unittest discover -s scripts/tests -v
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
CI = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
INSTRUMENTED = (WORKFLOWS / "instrumented.yml").read_text(encoding="utf-8")


def code_lines(text):
    """The workflow's lines with comments removed, so a comment that MENTIONS a forbidden thing doesn't trip the checks."""
    return "\n".join(line.split(" #")[0] for line in text.splitlines() if not line.strip().startswith("#"))


class NoSecretsTest(unittest.TestCase):
    def test_no_workflow_references_secrets_or_tokens(self):
        for name, text in (("ci.yml", CI), ("instrumented.yml", INSTRUMENTED)):
            body = code_lines(text)
            with self.subTest(workflow=name):
                self.assertNotIn("secrets.", body)
                self.assertNotRegex(body, r"(?i)api[_-]?key|app[_-]?token|stadia|datasf")

    def test_permissions_are_read_only(self):
        for name, text in (("ci.yml", CI), ("instrumented.yml", INSTRUMENTED)):
            with self.subTest(workflow=name):
                self.assertRegex(code_lines(text), r"(?m)^permissions:\s*\n\s+contents: read\s*$")
                self.assertNotRegex(code_lines(text), r"(?i)(write|admin)")


class CiContentTest(unittest.TestCase):
    def test_runs_on_push_and_pull_request(self):
        body = code_lines(CI)
        self.assertRegex(body, r"(?m)^on:\s*\n\s+push:\s*\n\s+pull_request:")

    def test_runs_the_unit_tests_and_the_debug_build_with_the_wrapper(self):
        self.assertIn("./gradlew testDebugUnitTest assembleDebug", code_lines(CI))

    def test_runs_the_script_tests_on_python_3(self):
        body = code_lines(CI)
        self.assertIn("python -m unittest discover -s scripts/tests", body)
        self.assertRegex(body, r'python-version: "3\.\d+"')

    def test_uploads_only_test_reports_and_only_on_failure(self):
        body = code_lines(CI)
        uploads = re.findall(r"uses: actions/upload-artifact@v\d+(.*?)(?=\n\s*- name:|\Z)", body, re.S)
        self.assertEqual(1, len(uploads))
        self.assertIn("if: failure()", body)
        self.assertIn("path: app/build/reports/tests/", uploads[0])
        for forbidden in ("backups", "logs", "databases", "*.db", "park_database"):
            self.assertNotIn(forbidden, uploads[0])

    def test_the_wrapper_is_made_executable_and_the_jdk_is_new_enough(self):
        body = code_lines(CI)
        self.assertIn("chmod +x gradlew", body)
        self.assertRegex(body, r"java-version: 2[1-9]|java-version: 17")


class AndroidSdkSetupTest(unittest.TestCase):
    """Both workflows use the runner's own SDK plus two sdkmanager installs. android-actions/setup-android@v3 fails on the
    runners ("Failed to find package 'tools'"), so it must not come back in either."""

    WORKFLOW_TEXTS = (("ci.yml", CI), ("instrumented.yml", INSTRUMENTED))

    def test_the_setup_action_is_gone_and_the_runners_sdk_is_used(self):
        for name, text in self.WORKFLOW_TEXTS:
            body = code_lines(text)
            with self.subTest(workflow=name):
                self.assertNotIn("android-actions/setup-android", body)
                self.assertIn("/usr/local/lib/android/sdk", text)
                self.assertIn('--install "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS"', body)
                self.assertIn('echo "ANDROID_HOME=$sdk_root" >> "$GITHUB_ENV"', body)

    def test_the_platform_it_installs_is_the_projects_compile_sdk(self):
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        compile_sdk = re.search(r"compileSdk\s*\{\s*version\s*=\s*release\((\d+)\)", gradle)
        self.assertIsNotNone(compile_sdk, "compileSdk is no longer written as release(N): update this test and the workflows")
        for name, text in self.WORKFLOW_TEXTS:
            with self.subTest(workflow=name):
                self.assertIn(f'ANDROID_PLATFORM: "platforms;android-{compile_sdk.group(1)}.0"', code_lines(text))
                self.assertRegex(code_lines(text), r'ANDROID_BUILD_TOOLS: "build-tools;\d+\.\d+\.\d+"')

    def test_the_two_workflows_have_the_same_sdk_step(self):
        def sdk_step(text):
            match = re.search(r"      # GitHub's Ubuntu runners already have.*?(?=\n      # Restores|\n      - name: Set up Gradle)", text, re.S)
            self.assertIsNotNone(match, "the 'Set up Android SDK' step (with its comment) wasn't found")
            return match.group(0).strip()
        self.assertEqual(sdk_step(CI), sdk_step(INSTRUMENTED))


class InstrumentedWorkflowTest(unittest.TestCase):
    def test_it_is_manual_only(self):
        body = code_lines(INSTRUMENTED)
        self.assertRegex(body, r"(?m)^on:\s*\n\s+workflow_dispatch:")
        self.assertNotRegex(body, r"(?m)^\s+(push|pull_request|schedule):")

    def test_it_runs_the_instrumented_tests_on_a_created_emulator(self):
        body = code_lines(INSTRUMENTED)
        self.assertIn("reactivecircus/android-emulator-runner", body)
        # (Pattern rather than the literal task name: scripts/tests/test_guard.py bans that name anywhere under scripts/.)
        self.assertRegex(body, r"gradlew connected\w*AndroidTest")


class RepoStateTest(unittest.TestCase):
    def test_the_api_key_files_are_gitignored_so_ci_builds_without_them(self):
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("app/src/main/assets/stadia_api_key.txt", ignore)
        self.assertIn("app/src/main/assets/datasf_app_token.txt", ignore)

    def test_yaml_parses_when_pyyaml_is_available(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML isn't installed")
        for path in WORKFLOWS.glob("*.yml"):
            with self.subTest(workflow=path.name):
                data = yaml.safe_load(path.read_text(encoding="utf-8"))
                self.assertIn("jobs", data)
                self.assertTrue(all("runs-on" in job and "steps" in job for job in data["jobs"].values()))


if __name__ == "__main__":
    unittest.main()
