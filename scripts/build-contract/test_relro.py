import struct, sys
sys.path.insert(0, '.')
from relro_invariants import audit, check
def elf(phs, size=0x100000):
    h = bytearray(64); h[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<HH', h, 16, 3, 183); struct.pack_into('<Q', h, 32, 64)
    struct.pack_into('<HH', h, 54, 56, len(phs))
    b = bytearray(h)
    for t, f, o, v, fs, ms, al in phs: b += struct.pack('<IIQQQQQQ', t, f, o, v, v, fs, ms, al)
    return bytes(b) + bytes(max(0, size - len(b)))
L = lambda f, o, v, fs, ms=None, al=0x4000: (1, f, o, v, fs, fs if ms is None else ms, al)
R = lambda v, ms: (0x6474e552, 4, v, v, ms, ms, 1)
X, RW, RO = 5, 6, 4
cases = {
 # name: (phdrs, expect_errors_substr or None, expect_strict_finding)
 'canonical': ([L(RO,0,0,0x1000), L(X,0x4000,0x4000,0x2000), L(RW,0x8000,0x8000,0x1000,0x4000), R(0x8000,0x4000)], None, False),
 'libcrypto-like padding start (safe)': ([L(RO,0,0,0x1000), L(X,0x4000,0x4000,0x6000), L(RW,0xa180,0x12180,0x1e80,0x1e80), R(0x12180,0x1e80)], None, True),
 'unaligned start with non-RELRO bytes before RELRO in same LOAD': ([L(RO,0,0,0x1000), L(X,0x4000,0x4000,0x2000), L(RW,0x8000,0x8000,0x5000), R(0x9000,0x1000)], 'non-RELRO bytes of own LOAD inside rounded start', True),
 'partial relro end inside writable LOAD': ([L(RO,0,0,0x1000), L(X,0x4000,0x4000,0x2000), L(RW,0x8000,0x8000,0x6000), R(0x8000,0x1800)], 'non-RELRO bytes of own LOAD inside rounded end', True),
 'relro rounds into exec page': ([L(RO,0,0,0x1000), L(X,0x4000,0x4000,0x4800), L(RW,0x9000,0x9000,0x1000), R(0x9000,0x1000)], 'overlaps another LOAD page', True),
 'bad congruence': ([L(RO,0,0,0x1000), L(X,0x4000,0x4010,0x2000)], 'not page congruent', False),
 'two loads share page': ([L(RO,0,0,0x1000), L(X,0x1000,0x1000,0x1000)], 'share a page', False),
 'align below page': ([L(RO,0,0,0x1000,al=0x1000)], 'align below page', False),
 'relro outside writable load': ([L(RO,0,0,0x1000), L(RW,0x4000,0x4000,0x1000), R(0x0,0x1000)], 'not enclosed', False),
 'wx load': ([L(7,0,0,0x1000)], 'writable executable', False),
}
bad = 0
for n, (ph, exp, strict) in cases.items():
    e, f = audit(elf(ph))
    ok = (exp is None and not e) or (exp is not None and any(exp in x for x in e))
    ok = ok and (bool(f) == strict)
    print('PASS' if ok else 'FAIL', n, e[:2], f[:1]); bad += not ok
sys.exit(1 if bad else 0)
