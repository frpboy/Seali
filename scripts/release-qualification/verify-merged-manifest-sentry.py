#!/usr/bin/env python3
"""Verify Sentry invariants on the MERGED debug manifest (manifest-merger
output), not the source manifest.

Background: Firebase Test Lab robo run matrix-2wkcs6zz3bjp5 (API 26)
crashed at startup because the sentry-android-core SentryInitProvider
auto-initializes the SDK before Application.onCreate and throws FATAL
"DSN is required" when the merged manifest has no io.sentry.dsn
meta-data. Seali initializes Sentry explicitly in CrashReporting.init
(no-op without a configured DSN), so auto-init must stay disabled in the
merged manifest and no DSN element may be baked in (the DSN only ever
comes from the SEALI_SENTRY_DSN build property via BuildConfig).

Checks (both fail the build):
  1. <application> contains <meta-data android:name="io.sentry.auto-init"
     android:value="false"/>.
  2. No <meta-data android:name="io.sentry.dsn"> element anywhere.

XML-aware parsing (xml.etree), so comments, attribute order and
formatting cannot fool it. Exits non-zero with a clear message on
failure. Run after :app:processGenericDebugMainManifest.
"""
import glob
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

candidates = sorted(
    glob.glob("app/build/intermediates/merged_manifest*/genericDebug*/AndroidManifest.xml")
    + glob.glob("app/build/intermediates/merged_manifest/genericDebug/AndroidManifest.xml")
)
if not candidates:
    print("ERROR: merged genericDebug manifest not found; run "
          ":app:processGenericDebugMainManifest first", file=sys.stderr)
    sys.exit(2)
path = candidates[0]

root = ET.parse(path).getroot()
app = root.find("application")
if app is None:
    print(f"ERROR: no <application> element in {path}", file=sys.stderr)
    sys.exit(2)

meta = [
    (md.get(ANDROID + "name"), md.get(ANDROID + "value"))
    for md in root.iter("meta-data")
]

dsn = [n for n, _ in meta if n == "io.sentry.dsn"]
if dsn:
    print(f"ERROR: {path} hard-codes io.sentry.dsn meta-data; the DSN must "
          "only come from the SEALI_SENTRY_DSN build property", file=sys.stderr)
    sys.exit(1)

if ("io.sentry.auto-init", "false") not in meta:
    print(f"ERROR: {path} lacks <meta-data android:name=\"io.sentry.auto-init\" "
          "android:value=\"false\"/>; the SentryInitProvider would crash "
          "startup when no DSN is configured", file=sys.stderr)
    sys.exit(1)

print(f"OK: {path}: io.sentry.auto-init=false present, no io.sentry.dsn element "
      f"({len(meta)} meta-data elements inspected)")
