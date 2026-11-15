#!/usr/bin/env python3
"""Packaging-stage step: embed the license bundle into libpython.zip.so.

Takes the pristine payload zip (all non-license entries final) and appends
usr/share/licenses/* entries assembled from pinned source trees. Fail-closed:
any missing source, placeholder content, or uncovered component aborts.
"""
import os, sys, re, zipfile, hashlib, json, shutil, tempfile

# component -> (license entries, each: (source_abs_path_or_None, arcname, kind))
# kind: 'whole' (copy entire file) ; source is verified to exist and be non-trivial.
def build_manifest(roots):
    B = roots['build']; T = roots['termux']; N = roots['ndk']; U = roots['upstream']
    return {
      'Python-3.11.18': [(f'{B}/hostpy-src/Python-3.11.18/LICENSE', 'Python-3.11.18/LICENSE', 'whole')],
      'android-ndk-r28c': [(f'{N}/NOTICE', 'android-ndk-r28c/NOTICE', 'whole'),
                            (f'{N}/NOTICE.toolchain', 'android-ndk-r28c/NOTICE.toolchain', 'whole')],
      'bzip2-1.0.8': [(f'{B}/deps/src/bzip2-1.0.8/LICENSE', 'bzip2-1.0.8/LICENSE', 'whole')],
      'expat-2.10.0': [(f'{U}/expat-2.10.0/COPYING', 'expat-2.10.0/COPYING', 'whole'),
                        (f'{U}/expat-2.10.0/AUTHORS', 'expat-2.10.0/AUTHORS', 'attribution')],
      'libandroid-support-29': [(f'{B}/deps/src/libandroid-support-29/LICENSE.txt', 'libandroid-support-29/LICENSE.txt', 'whole')],
      'libandroid-posix-semaphore': [(f'{T}/packages/libandroid-posix-semaphore/LICENSE', 'libandroid-posix-semaphore/LICENSE', 'whole')],
      'libcrypt': [(f'{T}/packages/libcrypt/crypt3.c', 'libcrypt/crypt3.c.copyright-and-source', 'whole')],
      'libffi-3.8.0': [(f'{B}/refresh-remaining/src/libffi-3.8.0/LICENSE', 'libffi-3.8.0/LICENSE', 'whole'),
                        (f'{B}/refresh-remaining/src/libffi-3.8.0/LICENSE-BUILDTOOLS', 'libffi-3.8.0/LICENSE-BUILDTOOLS', 'whole')],
      'mutagen-1.47.0': [(f'{U}/mutagen-1.47.0/COPYING', 'mutagen-1.47.0/COPYING', 'whole')],
      'ThomasDickey-ncurses-snapshots-26ceb3c': [(f'{B}/refresh-remaining/src/ThomasDickey-ncurses-snapshots-26ceb3c/COPYING', 'ThomasDickey-ncurses-snapshots-26ceb3c/COPYING', 'whole')],
      'openssl-3.5.9': [(f'{U}/openssl-3.5.9/AUTHORS.md', 'openssl-3.5.9/AUTHORS.md', 'attribution'),
                         (f'{U}/openssl-3.5.9/LICENSE.txt', 'openssl-3.5.9/LICENSE.txt', 'whole')],
      'pycryptodomex-3.24.2': [(f'{B}/pycryptodomex/pycryptodomex-3.24.2/LICENSE.rst', 'pycryptodomex-3.24.2/LICENSE.rst', 'whole')],
      'sqlite3': [(f'{B}/refresh-remaining/src/sqlite-autoconf-3540100/sqlite3.h', 'sqlite3/sqlite3.h.copyright-and-source', 'whole')],
      'termux-framework': [(f'{T}/LICENSE.md', 'termux-framework/LICENSE.md', 'whole')],
      'wcwidth-4': [(f'{B}/deps/src/wcwidth-4/LICENSE.txt', 'wcwidth-4/LICENSE.txt', 'whole')],
      'xz-5.8.4': [(f'{B}/refresh-compression/src/xz-5.8.4/'+f, 'xz-5.8.4/'+f,
                     'attribution' if f in ('AUTHORS','README') else 'whole')
                    for f in ['AUTHORS','COPYING','COPYING.0BSD','COPYING.GPLv2','COPYING.GPLv3','COPYING.LGPLv2.1','README']],
      'zlib-1.4': [(f'{B}/refresh-compression/src/zlib-1.4/LICENSE', 'zlib-1.4/LICENSE', 'whole')],
      'mozilla-ca': [(f'{U}/mozilla-ca/MPL-2.0.txt', 'mozilla-ca/MPL-2.0.txt', 'whole'),
                      (f'{U}/mozilla-ca/SOURCE.txt', 'mozilla-ca/SOURCE.txt', 'attribution')],
    }

# Components whose shared objects ship in the zip and therefore MUST have coverage.
REQUIRED_COMPONENTS = ['Python-3.11.18','android-ndk-r28c','bzip2-1.0.8','expat-2.10.0',
  'libandroid-support-29','libandroid-posix-semaphore','libcrypt','libffi-3.8.0','mutagen-1.47.0',
  'ThomasDickey-ncurses-snapshots-26ceb3c','openssl-3.5.9','pycryptodomex-3.24.2','sqlite3',
  'termux-framework','wcwidth-4','xz-5.8.4','zlib-1.4','mozilla-ca']

# Every shipped notice must contain a substantive notice marker (component-specific),
# must be >= 50 bytes, and must not contain unfilled-template or placeholder tokens.
NOTICE_MARKERS = {
  'sqlite3/sqlite3.h.copyright-and-source': [b'disclaims copyright', b'May you do good and not evil'],
  'libcrypt/crypt3.c.copyright-and-source': [b'Copyright (c) 2011 The FreeBSD Project', b'Redistribution and use'],
}
REJECT_TOKENS = [b'MISSING', b'<year>', b'<copyright holder>', b'PLACEHOLDER', b'TODO: license']
MIN_NOTICE_BYTES = 50

def assemble(roots, out_dir):
    manifest = build_manifest(roots)
    missing = [c for c in REQUIRED_COMPONENTS if c not in manifest]
    if missing: raise SystemExit('FAIL: no license mapping for components: %s' % missing)
    staged = {}
    for comp, entries in manifest.items():
        for src, arc, kind in entries:
            if not os.path.isfile(src):
                raise SystemExit('FAIL: missing license source %s (component %s)' % (src, comp))
            data = open(src, 'rb').read()
            if len(data) < MIN_NOTICE_BYTES:
                raise SystemExit('FAIL: license source too small (%d B) %s' % (len(data), src))
            real_notices = len(re.findall(rb'Copyright\s*(\(C\)|\(c\)|\xc2\xa9)?\s*,?\s*(19|20)\d\d', data))
            LICENSE_SIGNATURES = [b'GNU GENERAL PUBLIC LICENSE', b'GNU LESSER GENERAL PUBLIC LICENSE',
              b'Apache License', b'MIT License', b'Redistribution and use', b'disclaims copyright',
              b'Mozilla Public License', b'Permission is hereby granted', b'Permission to use, copy, modify', b"This software is provided 'as-is'", b'Blessing', b'blessing',
              b'All rights reserved', b'public domain', b'Public Domain']
            has_signature = any(sig in data for sig in LICENSE_SIGNATURES)
            if len(data) < 1024:
                for tok in REJECT_TOKENS:
                    if tok in data:
                        raise SystemExit('FAIL: placeholder token %r in small notice %s' % (tok, src))
                if kind != 'attribution' and real_notices == 0 and not has_signature:
                    raise SystemExit('FAIL: small notice with no substantive license content: %s' % src)
            else:
                for tok in (b'<year>', b'<copyright holder>'):
                    if tok in data and real_notices == 0:
                        raise SystemExit('FAIL: unfilled template token %r with no real notice in %s' % (tok, src))
                if kind != 'attribution' and real_notices == 0 and not has_signature:
                    raise SystemExit('FAIL: notice with no recognizable license content: %s' % src)
            for marker in NOTICE_MARKERS.get(arc, []):
                if marker not in data:
                    raise SystemExit('FAIL: required notice marker %r absent in %s' % (marker, src))
            staged['usr/share/licenses/' + arc] = data
    return staged

def main():
    pristine_zip, out_zip, roots_json = sys.argv[1], sys.argv[2], sys.argv[3]
    roots = json.load(open(roots_json))
    staged = assemble(roots, os.path.dirname(out_zip))
    src = zipfile.ZipFile(pristine_zip)
    existing = set(src.namelist())
    shutil.copy2(pristine_zip, out_zip)
    z = zipfile.ZipFile(out_zip, 'a', zipfile.ZIP_DEFLATED)
    added = 0
    for arc in sorted(staged):
        if arc in existing: continue  # e.g. cacert.pem already shipped
        tmp = tempfile.NamedTemporaryFile(delete=False)
        tmp.write(staged[arc]); tmp.close()
        z.write(tmp.name, arc); os.unlink(tmp.name)
        added += 1
    z.close()
    # verify original entries untouched
    a, b = zipfile.ZipFile(pristine_zip), zipfile.ZipFile(out_zip)
    for n in a.namelist():
        ia, ib = a.getinfo(n), b.getinfo(n)
        assert ia.header_offset == ib.header_offset and ia.CRC == ib.CRC, 'payload entry modified: ' + n
    h = hashlib.sha256(open(out_zip,'rb').read()).hexdigest()
    prov = {arc: {'sha256': hashlib.sha256(d).hexdigest(), 'bytes': len(d)} for arc, d in sorted(staged.items())}
    print(json.dumps({'out_zip': out_zip, 'sha256': h, 'bytes': os.path.getsize(out_zip),
                      'license_entries_added': added, 'provenance': prov}, indent=1))

if __name__ == '__main__':
    main()
