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

Lookup: AGP 8.x writes the merged manifest one level below the variant
dir (merged_manifest/<variant>/<task>/AndroidManifest.xml). All known
layouts are globbed; zero candidates and MULTIPLE candidates both fail
closed (no ambiguous or stale manifest is ever silently accepted).
`--self-test` exercises the lookup against fixture trees and exits
non-zero if any expectation breaks.
"""
import glob
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

PATTERNS = [
    # AGP 8.x: task-named directory below the variant dir.
    "app/build/intermediates/merged_manifest/genericDebug/*/AndroidManifest.xml",
    "app/build/intermediates/merged_manifests/genericDebug/*/AndroidManifest.xml",
    # Older AGP layouts, kept as fallbacks.
    "app/build/intermediates/merged_manifest/genericDebug/AndroidManifest.xml",
    "app/build/intermediates/merged_manifests/genericDebug/AndroidManifest.xml",
]

MINIMAL = (
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
    "<application>{}</application></manifest>"
)
AUTO_INIT = '<meta-data android:name="io.sentry.auto-init" android:value="false" />'
DSN = '<meta-data android:name="io.sentry.dsn" android:value="https://example.invalid/1" />'
PROVIDER = '<provider android:name="io.sentry.android.core.SentryInitProvider" />'


def find_merged_manifest():
    candidates = sorted({p for pattern in PATTERNS for p in glob.glob(pattern)})
    if not candidates:
        print(
            "ERROR: merged genericDebug manifest not found under any known "
            "AGP layout; run :app:processGenericDebugMainManifest first",
            file=sys.stderr,
        )
        sys.exit(2)
    if len(candidates) > 1:
        print(
            "ERROR: ambiguous merged genericDebug manifests (fail closed, no "
            "stale acceptance):\n  " + "\n  ".join(candidates),
            file=sys.stderr,
        )
        sys.exit(2)
    return candidates[0]


def check(path):
    root = ET.parse(path).getroot()
    if root.find("application") is None:
        print(f"ERROR: no <application> element in {path}", file=sys.stderr)
        return 2

    meta = [
        (md.get(ANDROID + "name"), md.get(ANDROID + "value"))
        for md in root.iter("meta-data")
    ]

    dsn = [n for n, _ in meta if n == "io.sentry.dsn"]
    if dsn:
        print(
            f"ERROR: {path} hard-codes io.sentry.dsn meta-data; the DSN must "
            "only come from the SEALI_SENTRY_DSN build property",
            file=sys.stderr,
        )
        return 1

    if ("io.sentry.auto-init", "false") not in meta:
        print(
            f'ERROR: {path} lacks <meta-data android:name="io.sentry.auto-init" '
            'android:value="false"/>; the SentryInitProvider would crash '
            "startup when no DSN is configured",
            file=sys.stderr,
        )
        return 1

    providers = [p.get(ANDROID + "name") for p in root.iter("provider")]
    auto_init_provider = "io.sentry.android.core.SentryInitProvider" in providers
    print(
        f"OK: {path}: io.sentry.auto-init=false present, no io.sentry.dsn "
        f"element ({len(meta)} meta-data elements inspected; "
        f"SentryInitProvider {'present' if auto_init_provider else 'ABSENT'})"
    )
    return 0


def self_test():
    failures = []

    def expect(name, cond):
        print(("PASS " if cond else "FAIL ") + name)
        if not cond:
            failures.append(name)

    with tempfile.TemporaryDirectory() as tmp:
        os.chdir(tmp)

        # Not found -> exit 2.
        try:
            find_merged_manifest()
            expect("missing tree exits 2", False)
        except SystemExit as e:
            expect("missing tree exits 2", e.code == 2)

        # Deep AGP 8.x layout is found and a good manifest passes.
        deep = "app/build/intermediates/merged_manifest/genericDebug/processGenericDebugMainManifest"
        os.makedirs(deep)
        good = MINIMAL.format(AUTO_INIT + PROVIDER)
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(good)
        path = find_merged_manifest()
        expect("deep AGP 8.x path found", path.endswith("processGenericDebugMainManifest/AndroidManifest.xml"))
        expect("good manifest passes", check(path) == 0)

        # A second candidate layout -> ambiguous, exit 2.
        shallow = "app/build/intermediates/merged_manifest/genericDebug"
        with open(os.path.join(shallow, "AndroidManifest.xml"), "w") as f:
            f.write(good)
        try:
            find_merged_manifest()
            expect("ambiguous candidates exit 2", False)
        except SystemExit as e:
            expect("ambiguous candidates exit 2", e.code == 2)
        os.remove(os.path.join(shallow, "AndroidManifest.xml"))

        # Missing auto-init -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(PROVIDER))
        expect("missing auto-init fails 1", check(path) == 1)

        # Baked DSN -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(AUTO_INIT + DSN + PROVIDER))
        expect("baked DSN fails 1", check(path) == 1)

        # auto-init value "true" -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(AUTO_INIT.replace('"false"', '"true"')))
        expect("auto-init true fails 1", check(path) == 1)

    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} case(s)", file=sys.stderr)
        sys.exit(1)
    print("SELF-TEST OK")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        sys.exit(check(find_merged_manifest()))
