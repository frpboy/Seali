#!/usr/bin/env python3
"""Invariant-based RELRO/LOAD layout validator (layout only, never executes code).
Models the Bionic linker: each PT_LOAD is mapped on [page_start(vaddr), page_end(vaddr+memsz));
PT_GNU_RELRO is mprotect'ed PROT_READ on [page_start(start), page_end(start+memsz)).
Strict canonical alignment is kept as a FINDING (strict_findings), separate from errors."""
import struct
PT_LOAD, PT_RELRO, PT_STACK = 1, 0x6474e552, 0x6474e551
def ps(a, P): return a // P * P
def pe(a, P): return (a + P - 1) // P * P

def parse(data):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01': raise ValueError('not ELF64 LE')
    machine = struct.unpack_from('<H', data, 18)[0]
    off = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    if size < 56 or count == 0 or off + size * count > len(data): raise ValueError('bad phdr bounds')
    loads, relros, stacks = [], [], []
    for i in range(count):
        t, f, o, v, _, fs, ms, al = struct.unpack_from('<IIQQQQQQ', data, off + i * size)
        d = dict(flags=f, off=o, vaddr=v, filesz=fs, memsz=ms, align=al)
        if t == PT_LOAD: loads.append(d)
        elif t == PT_RELRO: relros.append(d)
        elif t == PT_STACK: stacks.append(d)
    return machine, loads, relros, stacks, len(data)

def check(data, P):
    """Return (errors, strict_findings) for page size P."""
    machine, loads, relros, stacks, n = parse(data)
    E, F = [], []
    if machine != 183: E.append('not AArch64')
    if not loads: E.append('no LOAD')
    for s in stacks:
        if s['flags'] & 1: E.append('executable GNU_STACK')
    for l in loads:
        a = l['align']
        if a < P or a & (a - 1): E.append('LOAD align below page or not power of two')
        if l['off'] % P != l['vaddr'] % P: E.append('LOAD offset/vaddr not page congruent')
        if l['filesz'] > l['memsz'] or l['off'] + l['filesz'] > n: E.append('invalid LOAD bounds')
        if l['flags'] & 3 == 3 and l['flags'] & 1: E.append('writable executable LOAD')
    ext = [(ps(l['vaddr'], P), pe(l['vaddr'] + l['memsz'], P)) for l in loads]
    for i in range(len(loads)):
        for j in range(i + 1, len(loads)):
            if max(ext[i][0], ext[j][0]) < min(ext[i][1], ext[j][1]):
                E.append('two LOADs share a page at P=%d' % P)
    for r in relros:
        s, e = r['vaddr'], r['vaddr'] + r['memsz']
        own = [k for k, l in enumerate(loads) if l['vaddr'] <= s and e <= l['vaddr'] + l['memsz'] and l['flags'] & 2]
        if len(own) != 1:
            E.append('RELRO not enclosed by exactly one writable LOAD'); continue
        k = own[0]; l = loads[k]; lo, hi = ps(s, P), pe(e, P)
        for j, (a, b) in enumerate(ext):
            if j != k and max(lo, a) < min(hi, b):
                E.append('rounded RELRO range overlaps another LOAD page (%s) at P=%d' % ('exec' if loads[j]['flags'] & 1 else 'data', P))
        lend = l['vaddr'] + l['memsz']
        # bytes of the RELRO's own LOAD that would be wrongly made read-only
        if s > l['vaddr'] and lo < s and max(lo, l['vaddr']) < s:
            E.append('non-RELRO bytes of own LOAD inside rounded start page at P=%d' % P)
        if e < lend and hi > e:
            E.append('non-RELRO bytes of own LOAD inside rounded end page at P=%d' % P)
        if s % P or e % P:
            F.append('noncanonical RELRO alignment P=%d start%%P=%#x end%%P=%#x' % (P, s % P, e % P))
    return sorted(set(E)), F

def audit(data, pages=(4096, 16384)):
    errs, finds = [], []
    for P in pages:
        e, f = check(data, P); errs += e; finds += f
    return sorted(set(errs)), finds
