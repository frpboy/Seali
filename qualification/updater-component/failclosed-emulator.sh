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
printf 'no\n' | avdmanager create avd --force -n component -k "$SYSTEM_IMAGE"
"$ANDROID_HOME/emulator/emulator" -avd component -port 5554 -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -camera-back none -accel on > emulator.log 2>&1 &
emulator_pid=$!
trap 'adb -s emulator-5554 emu kill || true; kill "$emulator_pid" 2>/dev/null || true' EXIT
export ANDROID_SERIAL=emulator-5554
# Bounded readiness, no fixed boot assumption.
timeout 480 bash -c 'adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d "\r")" = 1 ]; do sleep 2; done'
actual_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
test "$actual_api" = "$EXPECTED_API"
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
bash run-component.sh
