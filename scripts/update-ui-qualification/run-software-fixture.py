#!/usr/bin/env python3
"""Bounded, owner-triggered software-emulator fixture. Never touches KVM or production Seali.

TCG is explicitly unsupported/slow in current Android docs. This is an opt-in qualification attempt,
not a promise of boot or useful pixels. The source/test/app are the isolated ui-qualification host.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

IMAGE = "system-images;android-35;google_apis;x86_64"
AVD = "seali-update-fixture-tcg"
SERIAL = "emulator-5554"
PACKAGE = "com.frpboy.seali.updaterfixture"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
BOOT_SECONDS = 900
PAGE_SECONDS = 360
EXTRACTION_SECONDS = 240
OVERALL_SECONDS = 1800
SHOTS = ("available", "downloading", "verifying", "confirm", "refused")


def main(root=None):
    root = Path(root) if root is not None else Path(__file__).resolve().parents[2]
    out = root / "update-ui-evidence"
    out.mkdir(exist_ok=False)
    started = time.monotonic()
    deadline = started + OVERALL_SECONDS
    state = {
        "source_sha": os.environ.get("GITHUB_SHA", "unknown"),
        "acceleration": "software-tcg; -accel off; ANDROID_I_WANT_MY_TCG=yes",
        "image": IMAGE,
        "limits_seconds": {"overall": OVERALL_SECONDS, "boot": BOOT_SECONDS,
                           "page_test": PAGE_SECONDS, "extraction_test": EXTRACTION_SECONDS},
        "qualification": "presentation and self-APK extraction fixture, NOT an actual app update",
        "phases": {},
    }
    emulator = None
    emulator_log = None

    def persist():
        state["elapsed_seconds"] = round(time.monotonic() - started, 1)
        (out / "result.json").write_text(json.dumps(state, indent=2) + "\n")

    def run(args, seconds=60, **kwargs):
        budget = min(seconds, int(deadline - time.monotonic()))
        if budget < 1:
            raise TimeoutError("OVERALL_DEADLINE")
        return subprocess.run(args, timeout=budget, check=False, **kwargs)

    def adb(*args, seconds=60):
        return run(["adb", "-s", SERIAL, *args], seconds=seconds,
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def command_ok(args, phase, seconds=60, **kwargs):
        result = run(args, seconds, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)
        state["phases"][phase] = {"exit": result.returncode}
        persist()
        if result.returncode:
            raise RuntimeError(phase + "_FAILED")
        return result.stdout.decode("utf-8", "replace")

    def instrument(klass, filename, expected, seconds):
        try:
            result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", klass, RUNNER, seconds=seconds)
            text = result.stdout.decode("utf-8", "replace")
            (out / filename).write_text(text)
            passed = (result.returncode == 0 and expected in text and
                      not re.search(r"FAILURES|INSTRUMENTATION_FAILED|shortMsg=", text))
            state["phases"][filename] = {"exit": result.returncode, "passed": passed}
        except subprocess.TimeoutExpired:
            state["phases"][filename] = {"passed": False, "error": "INSTRUMENTATION_TIMEOUT"}
        persist()

    try:
        sdk = Path(os.environ["ANDROID_HOME"])
        # Match the reviewed image revision instead of silently qualifying a different image.
        props = sdk / "system-images/android-35/google_apis/x86_64/source.properties"
        properties = dict(line.split("=", 1) for line in props.read_text().splitlines() if "=" in line)
        state["image_revision"] = properties.get("Pkg.Revision", "").strip()
        if state["image_revision"] != "9":
            raise RuntimeError("IMAGE_REVISION_CHANGED_REVIEW_REQUIRED")
        state["emulator_version"] = command_ok([str(sdk / "emulator/emulator"), "-version"], "emulator_version")[:1000]
        command_ok([str(sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--force",
                    "--name", AVD, "--package", IMAGE, "--device", "pixel_2"], "create_avd", input=b"no\n")
        # No chmod, udev, sudo, group changes, KVM probing or hypervisor fallback.
        env = dict(os.environ, ANDROID_I_WANT_MY_TCG="yes")
        emulator_log = open(os.environ["RUNNER_TEMP"] + "/update-fixture-emulator.log", "wb")
        emulator = subprocess.Popen([
            str(sdk / "emulator/emulator"), "-avd", AVD, "-port", "5554", "-accel", "off",
            "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader",
            "-memory", "1536", "-cores", "2", "-wipe-data",
        ], stdout=emulator_log, stderr=subprocess.STDOUT, env=env)
        boot_start = time.monotonic()
        boot_deadline = min(deadline, boot_start + BOOT_SECONDS)
        booted = False
        while time.monotonic() < boot_deadline:
            if emulator.poll() is not None:
                raise RuntimeError("EMULATOR_EXITED_BEFORE_BOOT")
            try:
                result = adb("shell", "getprop", "sys.boot_completed", seconds=10)
                if result.returncode == 0 and result.stdout.strip() == b"1":
                    booted = True
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(5)
        state["phases"]["boot"] = {"passed": booted, "seconds": round(time.monotonic() - boot_start, 1)}
        persist()
        if not booted:
            raise RuntimeError("SOFTWARE_BOOT_TIMEOUT")
        for prop in ("ro.build.version.sdk", "ro.product.cpu.abi"):
            result = adb("shell", "getprop", prop)
            state[prop] = result.stdout.decode("utf-8", "replace").strip()
        if state["ro.build.version.sdk"] != "35" or state["ro.product.cpu.abi"] != "x86_64":
            raise RuntimeError("UNEXPECTED_GUEST_IDENTITY")
        for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            if adb("shell", "settings", "put", "global", name, "0").returncode:
                raise RuntimeError("GUEST_ANIMATION_SETTING_FAILED")
        for folder in ("debug", "androidTest/debug"):
            apks = list((root / "ui-qualification/build/outputs/apk" / folder).glob("*.apk"))
            if len(apks) != 1:
                raise RuntimeError("AMBIGUOUS_FIXTURE_APK")
            result = adb("install", "-r", str(apks[0]), seconds=120)
            if result.returncode or b"Success" not in result.stdout:
                raise RuntimeError("FIXTURE_INSTALL_FAILED")
        state["phases"]["fixture_install"] = {"passed": True}
        instrument("com.frpboy.seali.UpdateProgressPageTest", "page-result.txt", "OK (1 test)", PAGE_SECONDS)
        # Collect every available image EVEN if an assertion failed; missing shots remain explicit failures.
        missing = []
        for name in SHOTS:
            result = adb("exec-out", "run-as", PACKAGE, "cat", "files/update-" + name + ".png", seconds=30)
            if result.returncode == 0 and result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                (out / ("update-" + name + ".png")).write_bytes(result.stdout)
            else:
                missing.append(name)
        state["phases"]["screenshots"] = {"passed": not missing, "missing": missing}
        persist()
        # Independent result, still attempted when presentation assertions failed.
        instrument("com.frpboy.seali.ApkArchiveReaderFixtureTest", "extraction-result.txt", "OK (2 tests)", EXTRACTION_SECONDS)
        state["passed"] = all(state["phases"].get(key, {}).get("passed", False) for key in
                              ("page-result.txt", "screenshots", "extraction-result.txt"))
    except (RuntimeError, TimeoutError, subprocess.TimeoutExpired, KeyError, OSError) as exc:
        state["passed"] = False
        state["error_type"] = type(exc).__name__
        # Exceptions here contain fixed phase labels, file paths or command parameters, never user data/secrets.
        state["error"] = str(exc)[:500]
    finally:
        if emulator is not None:
            try:
                adb("emu", "kill", seconds=10)
            except (TimeoutError, subprocess.TimeoutExpired):
                pass
            if emulator.poll() is None:
                emulator.terminate()
                try:
                    emulator.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    emulator.kill()
                    emulator.wait(timeout=10)
        if emulator_log is not None:
            emulator_log.close()
        persist()
        # Publish only structured diagnostics, fixture screenshots and synthetic test output. Raw emulator
        # log stays ephemeral and is not in the artifact allowlist (it may include local console auth data).
        print(json.dumps(state, indent=2))
    return 0 if state.get("passed") else 1


if __name__ == "__main__":
    sys.exit(main())
