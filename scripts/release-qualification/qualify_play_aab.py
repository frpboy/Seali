#!/usr/bin/env python3
"""Qualify the EXACT BYTES of a freshly built Seali Play upload AAB.

A CI-built AAB is discarded at job end (Play artifacts are never retained
on GitHub), so the artifact that would actually be uploaded to the Play
Console must be qualified itself, on its own bytes, after a fresh rebuild.
This script is that qualification gate. It is source-only: it never
uploads, dispatches a release, or touches the Play Console.

Checks (each fail-closed; any failure or tooling gap exits non-zero):
  1. Artifact sanity: file exists, non-empty, valid zip, .aab suffix,
     App Bundle layout (base/manifest/AndroidManifest.xml). The artifact
     SHA-256 and byte size are printed; the identical file (same digest)
     must be the one uploaded - record the digest in the evidence.
  2. Signer identity: the AAB verifies as signed (jarsigner -verify),
     every non-META-INF entry carries a signature flag (jarsigner -verify
     -verbose, so entries appended after signing fail), there is EXACTLY
     ONE distinct signer certificate (a multi-certificate chain per signer
     is rejected by design; the Play upload key is a single self-signed
     certificate), and that certificate's SHA-256 equals
     --expected-cert-sha256. Optionally --forbid-cert-sha256 rejects a
     known wrong key (e.g. the GitHub upload key) explicitly. The
     certificate's validity window is printed for the reviewer; expiry is
     informational, not a gate (Play upload keys are long-lived).
  3. Manifest invariants (via `bundletool dump manifest`): package equals
     --expected-package, versionCode equals --expected-version-code
     (required), versionName is present,
     uses-sdk minSdkVersion equals --expected-min-sdk (default 26),
     android:debuggable is not true, no io.sentry.dsn meta-data value,
     io.sentry.auto-init meta-data is explicitly false, and no forbidden permission
     (REQUEST_INSTALL_PACKAGES, MANAGE_EXTERNAL_STORAGE, plus any
     --forbid-permission) appears under uses-permission,
     uses-permission-sdk-23 or uses-permission-sdk-m.

Tooling: keytool + jarsigner from a JDK on PATH (or JAVA_HOME), and
bundletool (--bundletool path or BUNDLETOOL env; a plain `bundletool`
on PATH also works). Missing tools fail closed with a clear message.
Nothing here modifies the artifact.

`--self-test` exercises artifact/signer/manifest verdicts against
synthetic fixtures (throwaway keys, fixture manifest dumps) and exits
non-zero if any expectation breaks. It never touches a real keystore.
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

DEFAULT_FORBIDDEN_PERMISSIONS = (
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
)
PERMISSION_TAGS = ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")

SHA256_RE = re.compile(r"SHA256:\s*([0-9A-Fa-f:]{95})")
VALIDITY_RE = re.compile(r"Valid from:\s*(.+?)\s+until:\s*(.+)")
# jarsigner -verbose entry line: flags (subset of "smk", may be empty),
# size, locale-dependent date, then the entry name. Entry names may contain
# spaces, so names are matched against the line tail (" " + name) instead of
# splitting tokens; the prefix still must look like flags+size. Only actual
# signature metadata is exempt from the coverage check; anything else under
# META-INF/ must be signed like any other entry.
VERBOSE_PREFIX_RE = re.compile(r"^([a-z]{0,3})\s+(\d+)\s+")
SIGNATURE_METADATA_RE = re.compile(
    r"^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$", re.IGNORECASE)


def normalize_sha256(value):
    return value.replace(":", "").strip().lower()


def fail(results, check, detail):
    results.append(("FAIL", check, detail))


def ok(results, check, detail):
    results.append(("PASS", check, detail))


def info(results, check, detail):
    results.append(("INFO", check, detail))


def run(cmd):
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


def tool_path(name):
    found = shutil.which(name)
    if found:
        return found
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", name)
        if os.path.isfile(candidate):
            return candidate
    return None


def check_artifact(path, results):
    if not os.path.isfile(path):
        fail(results, "artifact", "missing file: %s" % path)
        return False
    size = os.path.getsize(path)
    if size == 0:
        fail(results, "artifact", "empty file: %s" % path)
        return False
    if not path.endswith(".aab"):
        fail(results, "artifact", "expected an .aab file: %s" % path)
        return False
    if not zipfile.is_zipfile(path):
        fail(results, "artifact", "not a valid zip/AAB: %s" % path)
        return False
    with zipfile.ZipFile(path) as bundle:
        names = set(bundle.namelist())
    if "base/manifest/AndroidManifest.xml" not in names:
        fail(results, "artifact",
            "base/manifest/AndroidManifest.xml missing; not an App Bundle layout")
        return False
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    ok(results, "artifact", "valid App Bundle zip with base manifest")
    ok(results, "artifact",
       "sha256=%s bytes=%d; record this digest - the identical file must be "
       "the uploaded artifact" % (digest.hexdigest(), size))
    return True


def signer_sha256s(path, jarsigner, keytool):
    """(digests, error). Verifies the signature AND per-entry coverage."""
    code, out = run([jarsigner, "-verify", "-verbose", path])
    if code != 0 or "jar verified" not in out:
        tail = out.strip().splitlines()[-1] if out.strip() else "unknown error"
        return None, "jarsigner -verify failed: %s" % tail
    lowered = out.lower()
    if "unsigned entries" in lowered or "signer errors" in lowered:
        return None, "jarsigner reported unsigned entries or signer errors"
    flagged = []
    for line in out.splitlines():
        match = VERBOSE_PREFIX_RE.match(line)
        if match and all(char in "smk" for char in match.group(1)):
            flagged.append((line, "s" in match.group(1)))
    with zipfile.ZipFile(path) as bundle:
        names = [n for n in bundle.namelist()
                 if not n.endswith("/") and not SIGNATURE_METADATA_RE.match(n)]
    signed = {}
    for name in names:
        suffix = " " + name
        for line, has_s in flagged:
            if line.endswith(suffix):
                signed[name] = has_s
                break
    unsigned = [n for n in names if not signed.get(n, False)]
    if unsigned:
        return None, ("%d non-META-INF entr%s not covered by the signature "
                      "(added after signing?), first: %s"
                      % (len(unsigned), "y is" if len(unsigned) == 1 else "ies are",
                         unsigned[0]))
    code, out = run([keytool, "-printcert", "-jarfile", path])
    if code != 0:
        return None, "keytool -printcert failed on the AAB"
    digests = [normalize_sha256(m.group(1)) for m in SHA256_RE.finditer(out)]
    if not digests:
        return None, "no signer certificate SHA-256 digest found"
    validity = VALIDITY_RE.search(out)
    seen = []
    for digest in digests:
        if digest not in seen:
            seen.append(digest)
    return (seen, validity.groups() if validity else None), None


def check_signer(path, expected, forbid, results):
    jarsigner = tool_path("jarsigner")
    keytool = tool_path("keytool")
    if not jarsigner or not keytool:
        fail(results, "signer", "jarsigner/keytool not found (set JAVA_HOME)")
        return
    parsed, error = signer_sha256s(path, jarsigner, keytool)
    if error:
        fail(results, "signer", error)
        return
    digests, validity = parsed
    distinct = set(digests)
    if len(distinct) != 1:
        fail(results, "signer",
             "expected exactly one distinct signer certificate, found %d "
             "(a multi-certificate chain per signer is rejected by design)" % len(distinct))
        return
    actual = digests[0]
    if normalize_sha256(expected) != actual:
        fail(results, "signer",
             "signer certificate does not match the dedicated Play upload key")
        return
    if forbid and normalize_sha256(forbid) == actual:
        fail(results, "signer", "signed with an explicitly forbidden certificate")
        return
    ok(results, "signer",
       "every entry signature-covered; exactly one signer, matches the dedicated "
       "Play upload key")
    if validity:
        info(results, "signer",
             "certificate validity %s until %s (informational; expiry is not a gate)"
             % validity)


MANIFEST_NS = "{http://schemas.android.com/apk/res/android}"


def verdict_manifest_text(text, expected_package, forbidden_permissions,
                          expected_min_sdk="26", expected_version_code=None):
    """Verdicts for `bundletool dump manifest` XML output. Pure, fixture-tested."""
    import xml.etree.ElementTree as ET

    results = []
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        fail(results, "manifest", "unparseable manifest dump: %s" % exc)
        return results
    package = root.attrib.get("package", "")
    if package != expected_package:
        fail(results, "manifest",
             "package %r != expected %r" % (package, expected_package))
    else:
        ok(results, "manifest", "package is %s" % expected_package)
    code = root.attrib.get(MANIFEST_NS + "versionCode", "")
    if not code.isdigit() or int(code) <= 0:
        fail(results, "manifest", "versionCode %r is not a positive integer" % code)
    elif expected_version_code is not None and code != str(expected_version_code):
        fail(results, "manifest",
             "versionCode %s != expected %s (stale or reused version code)"
             % (code, expected_version_code))
    name = root.attrib.get(MANIFEST_NS + "versionName", "")
    if not name:
        fail(results, "manifest", "versionName missing")
    uses_sdk = root.find("uses-sdk")
    min_sdk = uses_sdk.attrib.get(MANIFEST_NS + "minSdkVersion", "") if uses_sdk is not None else ""
    if min_sdk != str(expected_min_sdk):
        fail(results, "manifest",
             "minSdkVersion %r != expected %r" % (min_sdk, expected_min_sdk))
    application = root.find("application")
    if application is not None and application.attrib.get(MANIFEST_NS + "debuggable", "false") == "true":
        fail(results, "manifest", "android:debuggable is true")
    auto_init = None
    for meta in root.iter("meta-data"):
        meta_name = meta.attrib.get(MANIFEST_NS + "name", "")
        meta_value = meta.attrib.get(MANIFEST_NS + "value", "")
        if meta_name == "io.sentry.dsn" and (meta_value.strip() or meta.attrib.get(MANIFEST_NS + "resource")):
            fail(results, "manifest", "io.sentry.dsn meta-data present (Sentry must stay disabled)")
        if meta_name == "io.sentry.auto-init":
            auto_init = meta_value.strip().lower()
    if auto_init != "false":
        fail(results, "manifest",
             "io.sentry.auto-init meta-data missing or not false (Sentry must stay disabled)")
    present = {
        el.attrib.get(MANIFEST_NS + "name", "")
        for tag in PERMISSION_TAGS
        for el in root.iter(tag)
    }
    for permission in forbidden_permissions:
        if permission in present:
            fail(results, "manifest", "forbidden permission present: %s" % permission)
    if not any(r[0] == "FAIL" and r[1] == "manifest" for r in results):
        ok(results, "manifest",
           "version fields, minSdk, debuggable, Sentry and permission invariants hold")
    return results


def check_manifest(path, bundletool, expected_package, forbidden, results,
                   expected_min_sdk="26", expected_version_code=None):
    if bundletool.endswith(".jar"):
        java = tool_path("java") or "java"
        cmd = [java, "-jar", bundletool, "dump", "manifest", "--bundle", path]
    else:
        cmd = [bundletool, "dump", "manifest", "--bundle", path]
    code, out = run(cmd)
    if code != 0:
        fail(results, "manifest",
             "bundletool dump manifest failed: %s" % out.strip()[:300])
        return
    results.extend(verdict_manifest_text(out, expected_package, forbidden,
                                         expected_min_sdk, expected_version_code))


def resolve_bundletool(value):
    if value:
        return value
    env = os.environ.get("BUNDLETOOL")
    if env:
        return env
    return shutil.which("bundletool")


def report(results):
    width = max(len(check) for _, check, _ in results)
    failed = 0
    for status, check, detail in results:
        if status == "FAIL":
            failed += 1
        print("%s  %-*s  %s" % (status, width, check, detail))
    return failed


def self_test():
    keytool = tool_path("keytool")
    jarsigner = tool_path("jarsigner")
    if not keytool or not jarsigner:
        print("SELF-TEST FAIL: keytool/jarsigner unavailable")
        return 1
    failures = 0

    def expect_fails(res, label):
        nonlocal failures
        if not any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: %s accepted: %s" % (label, res))
            failures += 1

    def expect_passes(res, label):
        nonlocal failures
        if any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: %s rejected: %s" % (label, res))
            failures += 1

    with tempfile.TemporaryDirectory() as tmp:
        def make_key(alias, store):
            code, out = run([
                keytool, "-genkeypair", "-alias", alias, "-keystore", store,
                "-storepass", "selftest", "-keypass", "selftest",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=%s" % alias, "-validity", "30",
            ])
            assert code == 0, out

        def sign(jar, store, alias):
            code, out = run([
                jarsigner, "-keystore", store, "-storepass", "selftest",
                "-keypass", "selftest", jar, alias,
            ])
            assert code == 0, out

        def cert_sha(store, alias):
            exported = subprocess.run(
                [keytool, "-exportcert", "-alias", alias, "-keystore", store,
                 "-storepass", "selftest"], capture_output=True)
            assert exported.returncode == 0
            digest = subprocess.run(
                ["openssl", "dgst", "-sha256"],
                input=exported.stdout, capture_output=True).stdout.decode()
            return normalize_sha256(digest.split("=")[-1])

        good_store = os.path.join(tmp, "good.jks")
        bad_store = os.path.join(tmp, "bad.jks")
        make_key("play", good_store)
        make_key("github", bad_store)
        good_sha = cert_sha(good_store, "play")
        bad_sha = cert_sha(bad_store, "github")

        def fresh_bundle(name="candidate.aab"):
            bundle = os.path.join(tmp, name)
            with zipfile.ZipFile(bundle, "w") as z:
                z.writestr("base/manifest/AndroidManifest.xml", b"\x00")
                z.writestr("base/dex/classes.dex", b"\x00")
            sign(bundle, good_store, "play")
            return bundle

        bundle = fresh_bundle()
        with zipfile.ZipFile(bundle, "a") as z:
            z.writestr("base/res/with space.xml", b"\x00")
        sign(bundle, good_store, "play")

        def signer_verdicts(target, expected, forbid):
            res = []
            check_signer(target, expected, forbid, res)
            return res

        expect_passes(signer_verdicts(bundle, good_sha, bad_sha), "good signer")
        expect_fails(signer_verdicts(bundle, bad_sha, None), "wrong signer")
        expect_fails(signer_verdicts(bundle, good_sha, good_sha), "forbidden signer")

        tampered = fresh_bundle("tampered.aab")
        with zipfile.ZipFile(tampered, "a") as z:
            z.writestr("base/dex/extra-unsigned.dex", b"\x00")
        expect_fails(signer_verdicts(tampered, good_sha, None),
                     "entry appended after signing")

        tampered_meta = fresh_bundle("tampered-meta.aab")
        with zipfile.ZipFile(tampered_meta, "a") as z:
            z.writestr("META-INF/evil.dex", b"\x00")
        expect_fails(signer_verdicts(tampered_meta, good_sha, None),
                     "non-signature META-INF entry appended after signing")

        unsigned = os.path.join(tmp, "unsigned.aab")
        with zipfile.ZipFile(unsigned, "w") as z:
            z.writestr("base/manifest/AndroidManifest.xml", b"\x00")
        expect_fails(signer_verdicts(unsigned, good_sha, None), "unsigned artifact")

        res = []
        if not check_artifact(bundle, res):
            print("SELF-TEST FAIL: valid bundle rejected: %s" % res)
            failures += 1
        res = []
        if check_artifact(os.path.join(tmp, "nope.aab"), res):
            print("SELF-TEST FAIL: missing artifact accepted")
            failures += 1

        good_manifest = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
            ' package="com.frpboy.seali.google" android:versionCode="7"'
            ' android:versionName="2.0.0">'
            '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>'
            '<uses-permission android:name="android.permission.INTERNET"/>'
            '<application android:label="Seali" android:debuggable="false">'
            '<meta-data android:name="io.sentry.auto-init" android:value="false"/>'
            '</application>'
            "</manifest>"
        )
        expect_passes(verdict_manifest_text(good_manifest, "com.frpboy.seali.google",
                                            DEFAULT_FORBIDDEN_PERMISSIONS,
                                            "26", "7"),
                      "good manifest")
        bad_manifests = {
            "wrong package": good_manifest.replace("com.frpboy.seali.google",
                                                   "com.frpboy.seali"),
            "forbidden permission": good_manifest.replace(
                "android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES"),
            "forbidden sdk-23 permission": good_manifest.replace(
                '<uses-permission android:name="android.permission.INTERNET"/>',
                '<uses-permission-sdk-23 android:name="android.permission.MANAGE_EXTERNAL_STORAGE"/>'),
            "missing versionCode": good_manifest.replace('android:versionCode="7"', ''),
            "stale versionCode": good_manifest,  # expects 7, checked against 8 below
            "missing versionName": good_manifest.replace('android:versionName="2.0.0"', ''),
            "missing uses-sdk": good_manifest.replace(
                '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>', ''),
            "wrong minSdk": good_manifest.replace('minSdkVersion="26"',
                                                  'minSdkVersion="24"'),
            "debuggable true": good_manifest.replace('android:debuggable="false"',
                                                     'android:debuggable="true"'),
            "sentry dsn": good_manifest.replace(
                "</application>",
                '<meta-data android:name="io.sentry.dsn" android:value="https://x@sentry.io/1"/></application>'),
            "sentry auto-init true": good_manifest.replace(
                'android:value="false"/>', 'android:value="true"/>'),
            "sentry auto-init missing": good_manifest.replace(
                '<meta-data android:name="io.sentry.auto-init" android:value="false"/>', ''),
            "not xml": "not xml at all",
        }
        for label, mutation in bad_manifests.items():
            expected_code = "8" if label == "stale versionCode" else "7"
            expect_fails(verdict_manifest_text(mutation, "com.frpboy.seali.google",
                                               DEFAULT_FORBIDDEN_PERMISSIONS,
                                               "26", expected_code),
                         "bad manifest (%s)" % label)
    if failures:
        print("SELF-TEST: %d expectation(s) broke" % failures)
        return 1
    print("SELF-TEST OK")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bundle", help="path to the freshly built Play AAB")
    parser.add_argument("--expected-package", default="com.frpboy.seali.google")
    parser.add_argument("--expected-cert-sha256",
                        help="SHA-256 of the dedicated Play upload certificate")
    parser.add_argument("--forbid-cert-sha256",
                        help="SHA-256 of a certificate that must never sign (e.g. GitHub key)")
    parser.add_argument("--expected-version-code",
                        help="versionCode the upload candidate must carry (required; stale/reused codes fail)")
    parser.add_argument("--expected-min-sdk", default="26",
                        help="minSdkVersion the manifest must declare (default 26)")
    parser.add_argument("--forbid-permission", action="append", default=[],
                        help="extra forbidden permission (repeatable)")
    parser.add_argument("--bundletool", help="path to bundletool jar/binary")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not args.bundle or not args.expected_cert_sha256 or not args.expected_version_code:
        parser.error("--bundle, --expected-cert-sha256 and --expected-version-code are required")

    results = []
    artifact_ok = check_artifact(args.bundle, results)
    if artifact_ok:
        check_signer(args.bundle, args.expected_cert_sha256,
                     args.forbid_cert_sha256, results)
        bundletool = resolve_bundletool(args.bundletool)
        if not bundletool:
            fail(results, "manifest",
                 "bundletool not found (--bundletool or BUNDLETOOL); "
                 "manifest invariants cannot be verified, failing closed")
        else:
            check_manifest(args.bundle, bundletool, args.expected_package,
                           tuple(DEFAULT_FORBIDDEN_PERMISSIONS) + tuple(args.forbid_permission),
                           results, args.expected_min_sdk, args.expected_version_code)
    failed = report(results)
    if failed:
        print("QUALIFICATION FAILED: %d check(s) failed" % failed)
        return 1
    print("QUALIFICATION PASSED: exact-bytes Play AAB qualified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
