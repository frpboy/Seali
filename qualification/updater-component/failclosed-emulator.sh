#!/usr/bin/env bash
set -euo pipefail
# Ordinary workflow step owns all post-setup gates. No action can catch and continue these failures.
test -c /dev/kvm
test -r /dev/kvm
test -w /dev/kvm
python3 -c 'import os; fd=os.open("/dev/kvm",os.O_RDWR); print("KVM_OPEN_RDWR_AFTER_SETUP_PASS"); os.close(fd)'
"$ANDROID_HOME/emulator/emulator" -accel-check
printf '%s\n' 'ACCELERATION_GATE_PASSED'
# No emulator boot, build, fixture signing or install can precede the gate above.
printf 'no\n' | avdmanager create avd --force -n test --abi "$AVD_ABI" --package "$SYSTEM_IMAGE"
avd_root="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
test -f "$avd_root/test.avd/config.ini"
printf 'hw.cpu.ncore=2\nhw.ramSize=2048M\n' >> "$avd_root/test.avd/config.ini"
grep -E '^(hw.cpu.ncore|hw.ramSize|abi.type|image.sysdir.1)=' "$avd_root/test.avd/config.ini"
"$ANDROID_HOME/emulator/emulator" -port 5554 -avd test -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -camera-back none -accel on > emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  status=$?
  printf 'BOOT_OR_COMPONENT_EXIT=%s\n' "$status"
  printf '%s\n' 'EMULATOR_LOG_BEGIN'
  tail -120 emulator.log || true
  printf '%s\n' 'EMULATOR_LOG_END'
  adb devices -l || true
  adb -s emulator-5554 emu kill || true
  kill "$emulator_pid" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
export ANDROID_SERIAL=emulator-5554
# Bounded readiness, no fixed boot assumption.
export emulator_pid
# Preserve 480-second boot bound but fail promptly if the owned emulator exits.
timeout 480 bash -c 'until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = 1 ]; do kill -0 "$emulator_pid" 2>/dev/null || { printf "%s\n" "EMULATOR_EXITED_BEFORE_BOOT"; exit 1; }; sleep 2; done' 
actual_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
test "$actual_api" = "$EXPECTED_API"
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
bash run-component.sh
