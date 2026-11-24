#!/usr/bin/env bash
set -u
# Record independently. Never load modules, install packages, alter permissions, or boot an emulator.
check() {
  local label=$1
  shift
  printf '\nCHECK %s\n' "$label"
  "$@"
  local status=$?
  printf 'OUTCOME %s exit=%s\n' "$label" "$status"
  return 0
}
check identity id
check os uname -a
check kvm_node ls -l /dev/kvm
check kvm_node_metadata stat -c '%F mode=%a uid=%u gid=%g device=%t:%T' /dev/kvm
check kvm_read test -r /dev/kvm
check kvm_write test -w /dev/kvm
check cpu_virtualization bash -c 'grep -m1 -E "(^flags|^Features).* (vmx|svm)( |$)" /proc/cpuinfo'
check existing_kvm_modules bash -c 'grep -E "^kvm(_intel|_amd)? " /proc/modules'
check udev_binary command -v udevadm
check udev_daemon systemctl is-active systemd-udevd
check existing_kvm_rules bash -c 'grep -H -n kvm /usr/lib/udev/rules.d/* /etc/udev/rules.d/* 2>/dev/null'
check open_kvm python3 -c 'import os; fd=os.open("/dev/kvm",os.O_RDWR); print("opened read/write"); os.close(fd)'
check installed_emulator bash -c 'if [ -x "$ANDROID_HOME/emulator/emulator" ]; then "$ANDROID_HOME/emulator/emulator" -version; else printf "%s\n" "emulator binary not preinstalled"; exit 2; fi'
check existing_acceleration bash -c 'if [ -x "$ANDROID_HOME/emulator/emulator" ]; then "$ANDROID_HOME/emulator/emulator" -accel-check; else printf "%s\n" "emulator binary not preinstalled, no download"; exit 2; fi'
printf '\nDIAGNOSTIC_COMPLETE no mutation/emulator/build/install executed\n'
