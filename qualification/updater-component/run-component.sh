#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p assets
sdkmanager --list_installed | grep -E "(emulator|system-images|platforms;android-36|build-tools;36.0.0)"
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
# All keys are disposable synthetic fixture keys, created only within this job.
# AGP's ephemeral default debug key signs host and same-key fixtures.
bash gradlew :host:assembleDebug --no-daemon --no-configuration-cache --max-workers=2 --console=plain
build_fixture() {
  local name=$1 code=$2 package=$3
  bash gradlew :fixture:assembleDebug -PfixtureVersion="$code" -PfixturePackage="$package" --no-daemon --no-configuration-cache --max-workers=2 --console=plain
  cp fixture/build/outputs/apk/debug/fixture-debug.apk "assets/$name.apk"
}
build_fixture newer 11 com.frpboy.seali.updaterqualification
build_fixture same 10 com.frpboy.seali.updaterqualification
build_fixture older 9 com.frpboy.seali.updaterqualification
build_fixture wrong-package 11 com.frpboy.seali.updaterqualification.other
# Wrong signer fixture is re-signed using a second temporary debug key. No release keys/config exist here.
keytool -genkeypair -keystore "$RUNNER_TEMP/wrong-debug.jks" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Disposable Component Fixture' -keyalg RSA -validity 2
cp assets/newer.apk assets/wrong-key.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign --ks "$RUNNER_TEMP/wrong-debug.jks" --ks-pass pass:android --key-pass pass:android assets/wrong-key.apk
printf 'not an apk' > assets/malformed.apk
python3 - <<'PY'
import pathlib,json,hashlib,zipfile
root=pathlib.Path('assets');index={}
for p in [*root.glob('*.apk'),pathlib.Path('host/build/outputs/apk/debug/host-debug.apk')]:
 if p.name!='malformed.apk':
  with zipfile.ZipFile(p) as z:assert not any(n.startswith('lib/') or n.endswith(('.so','.aar')) for n in z.namelist()),p.name
 if p.name=='host-debug.apk':continue
 data=p.read_bytes();index[p.name]={'size':len(data),'digest':'sha256:'+hashlib.sha256(data).hexdigest()}
root.joinpath('index.json').write_text(json.dumps(index));print('SYNTHETIC_ASSET_MANIFEST',json.dumps(index,sort_keys=True))
PY
python3 server.py > server.log 2>&1 &
server_pid=$!
trap 'kill "$server_pid" || true; rm -f "$RUNNER_TEMP/wrong-debug.jks"' EXIT
adb install host/build/outputs/apk/debug/host-debug.apk
adb logcat -c
adb shell am instrument -w com.frpboy.seali.updaterqualification/com.frpboy.seali.ComponentInstrumentation | tee instrument.log
grep -q 'COMPONENT_SUCCESS' instrument.log
adb logcat -d -s ComponentQualification:I '*:S'
uri=$(adb shell run-as com.frpboy.seali.updaterqualification cat files/component-uri | tr -d '\r')
test "${uri#content://com.frpboy.seali.updaterqualification.provider/}" != "$uri"
# Real cross-UID shell read through ContentProvider permission grant, not a fake resolver.
adb shell content read --uri "$uri" > receiver-read.apk
python3 - <<'PY'
import hashlib,pathlib
assert hashlib.sha256(pathlib.Path('receiver-read.apk').read_bytes()).digest()==hashlib.sha256(pathlib.Path('assets/newer.apk').read_bytes()).digest()
print('PASS actual cross-UID FileProvider URI grant read')
PY
# Real system package-manager checks, distinct from verifier fixtures and installer UI.
adb install -r assets/wrong-key.apk > wrong-key-install.log 2>&1 && exit 1
grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' wrong-key-install.log
printf '%s\n' 'PASS real package manager rejects wrong signer'
adb install -r assets/older.apk > downgrade-install.log 2>&1 && exit 1
grep -q 'INSTALL_FAILED_VERSION_DOWNGRADE' downgrade-install.log
printf '%s\n' 'PASS real package manager rejects downgrade'
adb install -r assets/newer.apk
adb shell dumpsys package com.frpboy.seali.updaterqualification | grep 'versionCode=11'
test "$(adb shell run-as com.frpboy.seali.updaterqualification cat files/upgrade-sentinel | tr -d '\r')" = component-data-preserved
printf '%s\n' 'PASS real same-key debug package upgrade version10-to11 with private data retained' 'Not installer-screen confirmation, not Seali full application or production upgrade proof'
