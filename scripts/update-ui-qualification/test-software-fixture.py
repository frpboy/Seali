#!/usr/bin/env python3
"""Offline orchestration tests. Every process is mocked; no emulator, SDK install or device operation."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("fixture", Path(__file__).with_name("run-software-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class Process:
    def __init__(self, early_exit=False):
        self.exited = early_exit
    def poll(self):
        return 3 if self.exited else None
    def terminate(self):
        self.exited = True
    def wait(self, timeout):
        return 0
    def kill(self):
        self.exited = True


class FixtureTests(unittest.TestCase):
    def exercise(self, revision="9", page_ok=True, extraction_ok=True, missing_shot=False, early_exit=False, control_exit=0, headless_exit=0):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            sdk = root / "sdk"
            props = sdk / "system-images/android-35/google_apis/x86_64/source.properties"
            props.parent.mkdir(parents=True)
            props.write_text("Pkg.Revision=" + revision + "\n")
            for folder in ("debug", "androidTest/debug"):
                p = root / "ui-qualification/build/outputs/apk" / folder
                p.mkdir(parents=True)
                (p / "fixture.apk").write_bytes(b"fixture")
            calls = []
            def run(args, **kwargs):
                calls.append(args)
                self.assertNotIn("sudo", args)
                self.assertNotIn("chmod", args)
                if args[0] == "adb":
                    command = args[3:]
                    if command[:3] == ["shell", "getprop", "sys.boot_completed"]: data = b"1\n"
                    elif command[:3] == ["shell", "getprop", "ro.build.version.sdk"]: data = b"35\n"
                    elif command[:3] == ["shell", "getprop", "ro.product.cpu.abi"]: data = b"x86_64\n"
                    elif command[:2] == ["install", "-r"]: data = b"Success\n"
                    elif command[:3] == ["shell", "am", "instrument"]:
                        page = "UpdateProgressPageTest" in " ".join(command)
                        ok = page_ok if page else extraction_ok
                        data = ("OK (1 test)" if page else "OK (2 tests)").encode() if ok else b"FAILURES!!!"
                    elif command[:2] == ["exec-out", "run-as"]:
                        data = b"not a png" if missing_shot else b"\x89PNG\r\n\x1a\nfixture"
                    elif command[:3] == ["shell", "settings", "put"] or command[:2] == ["emu", "kill"]: data = b""
                    else: raise AssertionError("Unexpected adb call " + repr(command))
                elif "-version" in args:
                    code = headless_exit if "-no-window" in args else control_exit
                    if code == "timeout":
                        raise subprocess.TimeoutExpired(args, 30, output=b"partial preflight diagnostic")
                    return subprocess.CompletedProcess(args, code, b"fixture-version or loader error\n")
                elif "create" in args and "avd" in args: data = b"Created fixture\n"
                else: raise AssertionError("Unexpected process " + repr(args))
                return subprocess.CompletedProcess(args, 0, data)
            with patch.dict(os.environ, {"ANDROID_HOME": str(sdk), "RUNNER_TEMP": td, "GITHUB_SHA": "a" * 40}), \
                 patch.object(fixture.subprocess, "run", side_effect=run), \
                 patch.object(fixture.subprocess, "Popen", return_value=Process(early_exit)), \
                 contextlib.redirect_stdout(io.StringIO()):
                code = fixture.main(root)
            result = json.loads((root / "update-ui-evidence/result.json").read_text())
            preflight = root / "update-ui-evidence/sdk-preflight.json"
            if preflight.exists(): result["captured_preflight"] = json.loads(preflight.read_text())
            return code, result, calls

    def test_success_is_fixture_only_and_bounded(self):
        code, result, calls = self.exercise()
        self.assertEqual(0, code)
        self.assertTrue(result["passed"])
        self.assertIn("NOT an actual app update", result["qualification"])
        self.assertEqual(900, result["limits_seconds"]["boot"])

    def test_image_change_fails_before_process_launch(self):
        code, result, calls = self.exercise(revision="10")
        self.assertEqual(1, code)
        self.assertEqual("IMAGE_REVISION_CHANGED_REVIEW_REQUIRED", result["error"])
        self.assertEqual([], calls)

    def test_page_failure_does_not_hide_extraction_or_images(self):
        code, result, calls = self.exercise(page_ok=False)
        self.assertEqual(1, code)
        self.assertFalse(result["phases"]["page-result.txt"]["passed"])
        self.assertTrue(result["phases"]["extraction-result.txt"]["passed"])
        self.assertTrue(result["phases"]["screenshots"]["passed"])

    def test_extraction_failure_does_not_claim_success(self):
        code, result, calls = self.exercise(extraction_ok=False)
        self.assertEqual(1, code)
        self.assertTrue(result["phases"]["page-result.txt"]["passed"])
        self.assertFalse(result["phases"]["extraction-result.txt"]["passed"])

    def test_missing_images_fail_visually_unverified(self):
        code, result, calls = self.exercise(missing_shot=True)
        self.assertEqual(1, code)
        self.assertEqual(list(fixture.SHOTS), result["phases"]["screenshots"]["missing"])

    def test_early_emulator_exit_is_not_test_success(self):
        code, result, calls = self.exercise(early_exit=True)
        self.assertEqual(1, code)
        self.assertEqual("EMULATOR_EXITED_BEFORE_BOOT", result["error"])
        self.assertNotIn("page-result.txt", result["phases"])

    def test_gui_control_failure_does_not_misclassify_headless_backend(self):
        code, result, calls = self.exercise(control_exit=127)
        self.assertEqual(0, code)
        self.assertEqual(127, result["captured_preflight"][0]["exit"])
        self.assertEqual(0, result["captured_preflight"][1]["exit"])

    def test_headless_loader_failure_keeps_output_and_never_starts_tests(self):
        code, result, calls = self.exercise(headless_exit=127)
        self.assertEqual(1, code)
        self.assertEqual("HEADLESS_EMULATOR_PREFLIGHT_FAILED", result["error"])
        self.assertEqual(127, result["captured_preflight"][1]["exit"])
        self.assertIn("loader error", result["captured_preflight"][1]["output"])
        self.assertFalse(any("instrument" in c for c in calls))

    def test_headless_timeout_keeps_partial_and_control_output(self):
        code, result, calls = self.exercise(headless_exit="timeout")
        self.assertEqual(1, code)
        self.assertEqual("timeout", result["captured_preflight"][1]["exit"])
        self.assertIn("partial preflight", result["captured_preflight"][1]["output"])
        self.assertEqual(0, result["captured_preflight"][0]["exit"])


if __name__ == "__main__":
    unittest.main()
