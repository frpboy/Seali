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

Lookup: AGP 8.x writes more than one legitimate merged manifest per
variant (the manifest-merger output under merged_manifest/<variant>/
process<Variant>MainManifest/ and the packaging manifest under
merged_manifests/<variant>/process<Variant>Manifest/). All known
layouts are globbed and EVERY candidate is checked: the build passes
only if every merged manifest carries the invariants, so a stale or
divergent manifest that lacks them still fails the build. Zero
candidates fails closed. `--self-test` exercises the lookup and
verdicts against fixture trees and exits non-zero if any expectation
breaks.
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


def find_merged_manifests():
    candidates = sorted({p for pattern in PATTERNS for p in glob.glob(pattern)})
    if not candidates:
        print(
            "ERROR: merged genericDebug manifest not found under any known "
            "AGP layout; run :app:processGenericDebugMainManifest first",
            file=sys.stderr,
        )
        sys.exit(2)
    return candidates


def check_all(paths):
    worst = 0
    for path in paths:
        worst = max(worst, check(path))
    if len(paths) > 1 and worst == 0:
        print(f"OK: all {len(paths)} merged genericDebug manifests carry the invariants")
    return worst


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
            find_merged_manifests()
            expect("missing tree exits 2", False)
        except SystemExit as e:
            expect("missing tree exits 2", e.code == 2)

        # Deep AGP 8.x layout is found and a good manifest passes.
        deep = "app/build/intermediates/merged_manifest/genericDebug/processGenericDebugMainManifest"
        os.makedirs(deep)
        good = MINIMAL.format(AUTO_INIT + PROVIDER)
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(good)
        paths = find_merged_manifests()
        expect("deep AGP 8.x path found", any(p.endswith("processGenericDebugMainManifest/AndroidManifest.xml") for p in paths))
        expect("good manifest passes", check_all(paths) == 0)

        # Two legitimate candidates, both good -> pass (real AGP layout).
        pkg = "app/build/intermediates/merged_manifests/genericDebug/processGenericDebugManifest"
        os.makedirs(pkg)
        with open(os.path.join(pkg, "AndroidManifest.xml"), "w") as f:
            f.write(good)
        paths = find_merged_manifests()
        expect("two legitimate candidates found", len(paths) == 2)
        expect("two good candidates pass", check_all(paths) == 0)

        # One good + one missing auto-init -> fail (stale/divergent fails closed).
        with open(os.path.join(pkg, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(PROVIDER))
        expect("divergent candidate fails 1", check_all(find_merged_manifests()) == 1)
        with open(os.path.join(pkg, "AndroidManifest.xml"), "w") as f:
            f.write(good)

        # Missing auto-init -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(PROVIDER))
        expect("missing auto-init fails 1", check_all(find_merged_manifests()) == 1)

        # Baked DSN -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(AUTO_INIT + DSN + PROVIDER))
        expect("baked DSN fails 1", check_all(find_merged_manifests()) == 1)

        # auto-init value "true" -> exit 1.
        with open(os.path.join(deep, "AndroidManifest.xml"), "w") as f:
            f.write(MINIMAL.format(AUTO_INIT.replace('"false"', '"true"')))
        expect("auto-init true fails 1", check_all(find_merged_manifests()) == 1)

    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} case(s)", file=sys.stderr)
        sys.exit(1)
    print("SELF-TEST OK")


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        sys.exit(check_all(find_merged_manifests()))
