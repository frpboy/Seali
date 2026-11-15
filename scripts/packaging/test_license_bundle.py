#!/usr/bin/env python3
"""Coverage-contract test for the packaged license bundle. Fail-closed."""
import zipfile, sys, re, json

REQUIRED_COMPONENTS = ['Python-3.11.18','android-ndk-r28c','bzip2-1.0.8','expat-2.10.0',
  'libandroid-support-29','libandroid-posix-semaphore','libcrypt','libffi-3.8.0','mutagen-1.47.0',
  'ThomasDickey-ncurses-snapshots-26ceb3c','openssl-3.5.9','pycryptodomex-3.24.2','sqlite3',
  'termux-framework','wcwidth-4','xz-5.8.4','zlib-1.4','mozilla-ca']
REJECT_TOKENS = [b'MISSING', b'<year>', b'<copyright holder>', b'PLACEHOLDER', b'TODO: license']
MIN_NOTICE_BYTES = 50
NOTICE_MARKERS = {
  'usr/share/licenses/sqlite3/sqlite3.h.copyright-and-source': [b'disclaims copyright', b'May you do good and not evil'],
  'usr/share/licenses/libcrypt/crypt3.c.copyright-and-source': [b'Copyright (c) 2011 The FreeBSD Project', b'Redistribution and use'],
  'usr/share/licenses/mozilla-ca/MPL-2.0.txt': [b'Mozilla Public License'],
  'usr/share/licenses/Python-3.11.18/LICENSE': [b'PYTHON SOFTWARE FOUNDATION LICENSE'],
}

def main(zip_path):
    # automatic actual-library coverage: derive shipped libs from the zip itself
    sys.path.insert(0, __file__.rsplit('/', 1)[0])
    from lib_coverage import shipped_library_components, ALLOWED_EXTERNAL
    comps, external = shipped_library_components(zip_path)
    auto_failures = []
    if comps.get(None):
        auto_failures.append('unmapped shipped libraries: %s' % comps[None][:5])
    unexpected = external - ALLOWED_EXTERNAL
    if unexpected:
        auto_failures.append('external NEEDED beyond Android system libs: %s' % sorted(unexpected))
    z0 = zipfile.ZipFile(zip_path)
    lic_dirs = {n[len('usr/share/licenses/'):].split('/')[0] for n in z0.namelist() if n.startswith('usr/share/licenses/') and '/' in n[len('usr/share/licenses/'):]}
    for comp in comps:
        if comp and comp not in lic_dirs:
            auto_failures.append('shipped library component without license coverage: ' + comp)
    if auto_failures:
        print('FAIL (automatic library coverage)'); [print(' -', f) for f in auto_failures]; sys.exit(1)
    print('automatic library coverage PASS: %d components, external NEEDED = %s' % (len([c for c in comps if c]), sorted(external)))

    z = zipfile.ZipFile(zip_path)
    failures = []
    lic = [n for n in z.namelist() if n.startswith('usr/share/licenses/') and not n.endswith('/')]
    # component coverage
    covered = set()
    for n in lic:
        rel = n[len('usr/share/licenses/'):]
        covered.add(rel.split('/')[0])
    for comp in REQUIRED_COMPONENTS:
        if comp not in covered:
            failures.append('uncovered component: ' + comp)
    # per-entry content rules
    for n in lic:
        data = z.read(n)
        if len(data) < MIN_NOTICE_BYTES:
            failures.append('notice too small (%d B): %s' % (len(data), n))
        real_notices = len(re.findall(rb'Copyright\s*(\(C\)|\(c\)|\xc2\xa9)?\s*,?\s*(19|20)\d\d', data))
        if len(data) < 1024:
            for tok in REJECT_TOKENS:
                if tok in data:
                    failures.append('placeholder token %r in small notice %s' % (tok, n))
        else:
            for tok in (b'<year>', b'<copyright holder>'):
                if tok in data and real_notices == 0:
                    failures.append('unfilled template token %r in %s' % (tok, n))
        for marker in NOTICE_MARKERS.get(n, []):
            if marker not in data:
                failures.append('required marker %r absent in %s' % (marker, n))
    # no template-only LICENSE files (unfilled termux templates)
    for n in lic:
        data = z.read(n)
        if b'<year>' in data and len(re.findall(rb'Copyright\s*(\(C\)|\(c\))?\s*,?\s*(19|20)\d\d', data)) == 0:
            failures.append('template-only notice shipped: ' + n)
    if failures:
        print('FAIL'); [print(' -', f) for f in failures]; sys.exit(1)
    print('PASS: %d license entries, %d components covered' % (len(lic), len(covered)))

if __name__ == '__main__':
    main(sys.argv[1])
