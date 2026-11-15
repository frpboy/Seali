#!/usr/bin/env python3
"""Fixture-based execution of the license-bundle coverage contract for CI.

The real payload zip lives in private custody and is never available to CI,
so this builds small synthetic zips exercising every contract rule against
the real test_license_bundle.py + lib_coverage.py code paths. Asserts PASS
on a valid fixture and FAIL on each defect class. Never touches network or
private assets."""
import os, struct, subprocess, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CONTRACT = os.path.join(HERE, 'test_license_bundle.py')

def minimal_elf(needed=(b'libc.so',)):
    """Smallest ELF64 that lib_coverage.dt_needed can parse: one PT_LOAD
    covering the whole file, one PT_DYNAMIC with DT_NEEDED entries."""
    strtab = b'\x00' + b''.join(n + b'\x00' for n in needed)
    phoff, phentsize, phnum = 64, 56, 2
    dyn_off = 64 + phentsize * phnum
    dyn = b''
    off = 1
    for _ in needed:
        dyn += struct.pack('<qQ', 1, off)  # DT_NEEDED, strtab offset
        off += len(_) + 1
    dyn += struct.pack('<qQ', 5, 0)       # DT_STRTAB placeholder, fixed below
    dyn += struct.pack('<qQ', 0, 0)       # DT_NULL
    strtab_off = dyn_off + len(dyn)
    total = strtab_off + len(strtab)
    # fix DT_STRTAB to the strtab vaddr (identity mapping through PT_LOAD 0)
    dyn = dyn[:-32] + struct.pack('<qQ', 5, strtab_off) + struct.pack('<qQ', 0, 0)
    e_ident = b'\x7fELF\x02\x01\x01' + b'\x00' * 9
    hdr = e_ident + struct.pack('<HHIQQQIHHH', 3, 183, 1, 0, phoff, 0, 0, 64, phentsize, phnum)
    hdr = hdr.ljust(64, b'\x00')
    ph1 = struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, total, total, 0x1000)
    ph2 = struct.pack('<IIQQQQQQ', 2, 6, dyn_off, dyn_off, dyn_off, len(dyn), len(dyn), 8)
    return hdr + ph1 + ph2 + dyn + strtab

NOTICE = b'Copyright (c) 2026 Example Project. All rights reserved.\nPermission is hereby granted.\n'
REQUIRED = ['Python-3.11.18','android-ndk-r28c','bzip2-1.0.8','expat-2.10.0',
  'libandroid-support-29','libandroid-posix-semaphore','libcrypt','libffi-3.8.0','mutagen-1.47.0',
  'ThomasDickey-ncurses-snapshots-26ceb3c','openssl-3.5.9','pycryptodomex-3.24.2','sqlite3',
  'termux-framework','wcwidth-4','xz-5.8.4','zlib-1.4','mozilla-ca']
# (component, filename) -> content; filenames match the real marker-checked paths
SPECIAL = {
  ('Python-3.11.18', 'LICENSE'): b'PYTHON SOFTWARE FOUNDATION LICENSE\n' + NOTICE,
  ('sqlite3', 'sqlite3.h.copyright-and-source'): b'The author disclaims copyright to this source code.\nMay you do good and not evil.\n',
  ('libcrypt', 'crypt3.c.copyright-and-source'): b'Copyright (c) 2011 The FreeBSD Project. All rights reserved.\nRedistribution and use in source and binary forms, with or without modification, are permitted.\n',
  ('mozilla-ca', 'MPL-2.0.txt'): b'Mozilla Public License Version 2.0\n' + NOTICE,
}

def build_fixture(path, drop=(), placeholder=None, template=False, extra_lib=None, external_dep=None, strip_marker=None):
    z = zipfile.ZipFile(path, 'w')
    z.writestr('usr/lib/libz.so', 'libz.so.1.4')
    z.writestr('usr/lib/libz.so.1.4', minimal_elf((b'libc.so',) + ((external_dep,) if external_dep else ())))
    if extra_lib:
        z.writestr('usr/lib/' + extra_lib, minimal_elf())
    special_names = {c: n for (c, n) in SPECIAL}
    for comp in REQUIRED:
        if comp in drop: continue
        fname = special_names.get(comp, 'NOTICE')
        content = SPECIAL.get((comp, fname), NOTICE)
        if comp == strip_marker:
            content = NOTICE
        if placeholder == comp:
            content = b'MISSING\n'
        if template == comp:
            content = b'Copyright (c) <year>, <copyright holder>\nAll rights reserved.\n'
        z.writestr('usr/share/licenses/%s/%s' % (comp, fname), content)
    z.close()

def run(path):
    return subprocess.run([sys.executable, CONTRACT, path], capture_output=True, text=True)

def main():
    tmp = tempfile.mkdtemp()
    cases = []
    def fx(name, **kw):
        p = os.path.join(tmp, name + '.zip')
        build_fixture(p, **kw)
        return p
    cases.append(('valid bundle passes', fx('valid'), 0))
    cases.append(('dropped component fails', fx('drop', drop=('xz-5.8.4',)), 1))
    cases.append(('MISSING placeholder fails', fx('placeholder', placeholder='zlib-1.4'), 1))
    cases.append(('unfilled template fails', fx('template', template='libcrypt'), 1))
    cases.append(('stripped required marker fails', fx('marker', strip_marker='sqlite3'), 1))
    cases.append(('unmapped new library fails', fx('unmapped', extra_lib='libmystery.so'), 1))
    cases.append(('unexpected external NEEDED fails', fx('extdep', external_dep=b'liboutside.so'), 1))
    failures = []
    for name, path, want in cases:
        r = run(path)
        got = r.returncode
        ok = (got == want)
        print(('PASS' if ok else 'FAIL'), '-', name, f'(exit {got}, want {want})')
        if not ok:
            failures.append(name)
            print(r.stdout[-500:], r.stderr[-500:])
    if failures:
        print('FIXTURE CONTRACT FAILURES:', failures)
        sys.exit(1)
    print('All license-bundle fixture contract cases behave as required.')

if __name__ == '__main__':
    main()
