#!/usr/bin/env python3
"""Qualify the EXACT BYTES of a freshly built Seali Play upload AAB.

A CI-built AAB is discarded at job end (Play artifacts are never retained
on GitHub), so the artifact that would actually be uploaded to the Play
Console must be qualified itself, on its own bytes, after a fresh rebuild.
This script is that qualification gate. It is source-only: it never
uploads, dispatches a release, or touches the Play Console.

Checks (each fail-closed; any failure or tooling gap exits non-zero):
  1. Artifact sanity: file exists, non-empty, valid zip, .aab suffix,
     App Bundle layout (base/manifest/AndroidManifest.xml).
  2. Signer identity: the AAB verifies as signed (jarsigner -verify),
     has EXACTLY ONE distinct signer certificate, and that certificate's
     SHA-256 equals --expected-cert-sha256 (the dedicated Play upload
     key). Optionally --forbid-cert-sha256 rejects a known wrong key
     (e.g. the GitHub upload key) explicitly rather than by absence.
  3. Manifest invariants (via `bundletool dump manifest`): package equals
     --expected-package, versionCode is a positive integer, versionName is
     present, and no forbidden permission (REQUEST_INSTALL_PACKAGES,
     MANAGE_EXTERNAL_STORAGE, plus any --forbid-permission) appears.

Tooling: keytool + jarsigner from a JDK on PATH (or JAVA_HOME), and
bundletool (--bundletool path or BUNDLETOOL env; a plain `bundletool`
on PATH also works). Missing tools fail closed with a clear message.
Nothing here modifies the artifact.

`--self-test` exercises zip/signer/manifest verdicts against synthetic
fixtures (throwaway keys, fixture manifest dumps) and exits non-zero if
any expectation breaks. It never touches a real keystore.
"""

import argparse
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

SHA256_RE = re.compile(r"SHA256:\s*([0-9A-Fa-f:]{95})")


def normalize_sha256(value):
    return value.replace(":", "").strip().lower()


def fail(results, check, detail):
    results.append(("FAIL", check, detail))


def ok(results, check, detail):
    results.append(("PASS", check, detail))


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
        fail(results, "artifact", "not found: %s" % path)
        return False
    if os.path.getsize(path) == 0:
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
    ok(results, "artifact", "valid App Bundle zip with base manifest")
    return True


def signer_sha256s(path, jarsigner, keytool):
    code, out = run([jarsigner, "-verify", path])
    if code != 0 or "jar verified" not in out:
        tail = out.strip().splitlines()[-1] if out.strip() else "unknown error"
        return None, "jarsigner -verify failed: %s" % tail
    code, out = run([keytool, "-printcert", "-jarfile", path])
    if code != 0:
        return None, "keytool -printcert failed on the AAB"
    digests = [normalize_sha256(m.group(1)) for m in SHA256_RE.finditer(out)]
    if not digests:
        return None, "no signer certificate SHA-256 digest found"
    # One digest per signer block; dedupe cert-chain repeats, keep order.
    seen = []
    for digest in digests:
        if digest not in seen:
            seen.append(digest)
    return seen, None


def check_signer(path, expected, forbid, results):
    jarsigner = tool_path("jarsigner")
    keytool = tool_path("keytool")
    if not jarsigner or not keytool:
        fail(results, "signer", "jarsigner/keytool not found (set JAVA_HOME)")
        return
    digests, error = signer_sha256s(path, jarsigner, keytool)
    if error:
        fail(results, "signer", error)
        return
    distinct = set(digests)
    if len(distinct) != 1:
        fail(results, "signer",
             "expected exactly one distinct signer certificate, found %d" % len(distinct))
        return
    actual = digests[0]
    if normalize_sha256(expected) != actual:
        fail(results, "signer",
             "signer certificate does not match the dedicated Play upload key")
        return
    if forbid and normalize_sha256(forbid) == actual:
        fail(results, "signer", "signed with an explicitly forbidden certificate")
        return
    ok(results, "signer", "exactly one signer, matches the dedicated Play upload key")


MANIFEST_NS = "{http://schemas.android.com/apk/res/android}"


def verdict_manifest_text(text, expected_package, forbidden_permissions):
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
    name = root.attrib.get(MANIFEST_NS + "versionName", "")
    if not name:
        fail(results, "manifest", "versionName missing")
    present = {
        el.attrib.get(MANIFEST_NS + "name", "")
        for el in root.iter("uses-permission")
    }
    for permission in forbidden_permissions:
        if permission in present:
            fail(results, "manifest", "forbidden permission present: %s" % permission)
    if not any(r[0] == "FAIL" and r[1] == "manifest" for r in results):
        ok(results, "manifest", "no forbidden permissions; version fields sane")
    return results


def check_manifest(path, bundletool, expected_package, forbidden, results):
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
    results.extend(verdict_manifest_text(out, expected_package, forbidden))


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
            proc = subprocess.run(
                [keytool, "-exportcert", "-alias", alias,
                 "-keystore", store, "-storepass", "selftest"],
                capture_output=True)
            assert proc.returncode == 0
            digest = subprocess.run(
                ["openssl", "dgst", "-sha256"],
                input=proc.stdout, capture_output=True).stdout.decode()
            return normalize_sha256(digest.split("=")[-1])

        good_store = os.path.join(tmp, "good.jks")
        bad_store = os.path.join(tmp, "bad.jks")
        make_key("play", good_store)
        make_key("github", bad_store)
        good_sha = cert_sha(good_store, "play")
        bad_sha = cert_sha(bad_store, "github")

        bundle = os.path.join(tmp, "candidate.aab")
        with zipfile.ZipFile(bundle, "w") as z:
            z.writestr("base/manifest/AndroidManifest.xml", b"\x00")
            z.writestr("base/dex/classes.dex", b"\x00")
        sign(bundle, good_store, "play")

        def signer_verdicts(expected, forbid):
            res = []
            check_signer(bundle, expected, forbid, res)
            return res

        res = signer_verdicts(good_sha, bad_sha)
        if any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: good signer rejected: %s" % res)
            failures += 1
        res = signer_verdicts(bad_sha, None)
        if not any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: wrong signer accepted")
            failures += 1
        res = signer_verdicts(good_sha, good_sha)
        if not any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: forbidden signer accepted")
            failures += 1
        unsigned = os.path.join(tmp, "unsigned.aab")
        with zipfile.ZipFile(unsigned, "w") as z:
            z.writestr("base/manifest/AndroidManifest.xml", b"\x00")
        res = []
        check_signer(unsigned, good_sha, None, res)
        if not any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: unsigned artifact accepted")
            failures += 1

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
            '<uses-permission android:name="android.permission.INTERNET"/>'
            "</manifest>"
        )
        res = verdict_manifest_text(good_manifest, "com.frpboy.seali.google",
                                    DEFAULT_FORBIDDEN_PERMISSIONS)
        if any(r[0] == "FAIL" for r in res):
            print("SELF-TEST FAIL: good manifest rejected: %s" % res)
            failures += 1
        for mutation in (
            good_manifest.replace("com.frpboy.seali.google", "com.frpboy.seali"),
            good_manifest.replace(
                '<uses-permission android:name="android.permission.INTERNET"/>',
                '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>'),
            good_manifest.replace('android:versionCode="7"', ''),
            "not xml at all",
        ):
            res = verdict_manifest_text(mutation, "com.frpboy.seali.google",
                                        DEFAULT_FORBIDDEN_PERMISSIONS)
            if not any(r[0] == "FAIL" for r in res):
                print("SELF-TEST FAIL: bad manifest accepted: %r" % mutation[:60])
                failures += 1
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
    parser.add_argument("--forbid-permission", action="append", default=[],
                        help="extra forbidden permission (repeatable)")
    parser.add_argument("--bundletool", help="path to bundletool jar/binary")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    if not args.bundle or not args.expected_cert_sha256:
        parser.error("--bundle and --expected-cert-sha256 are required")

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
                           results)
    failed = report(results)
    if failed:
        print("QUALIFICATION FAILED: %d check(s) failed" % failed)
        return 1
    print("QUALIFICATION PASSED: exact-bytes Play AAB qualified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
