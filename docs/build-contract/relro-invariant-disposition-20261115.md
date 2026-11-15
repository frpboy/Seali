# RELRO audit disposition: invariant validator (2026-11-15)

## Disposition

The strict 16 KiB canonical-alignment audit previously flagged three
retained stage libraries (`libcrypto.so.3`, `libssl.so.3`,
`libexpat.so.1.13.1`) for non-page-aligned RELRO starts. Two independent
reviews against the Bionic linker source (API 24 `android-7.0.0_r1` and
Android 15 `android-15.0.0_r1` `linker/linker_phdr.cpp`) established the
actual runtime contract: the linker rounds the RELRO start DOWN and the end
UP to runtime page boundaries, and the true constraint is that the rounded
mprotect range contains no byte that must stay writable or executable -
not that the start is page-aligned. The strict check is therefore an
over-restrictive static layout finding, not evidence of a runtime defect.
The strict findings are preserved in the build receipts; nothing is
suppressed and no hash allowlist exists.

## What this adds

- `scripts/build-contract/relro_invariants.py`: layout-only invariant
  validator (executes nothing). Checks, for page sizes 4 KiB and 16 KiB:
  LOAD offset/vaddr page congruence, no two LOADs sharing a page, no
  writable+executable LOAD, non-executable GNU_STACK, RELRO enclosed by
  exactly one writable LOAD, rounded RELRO range overlapping no other
  LOAD's pages, and no non-RELRO bytes of the own LOAD inside the rounded
  start/end pages. Noncanonical alignment remains a finding, not an error.
- `scripts/build-contract/test_relro.py`: 10 regression fixtures
  (canonical, safe padding start, own-bytes start/end, exec overlap,
  congruence, page sharing, low align, RELRO outside LOAD, WX LOAD).
  Run: `python3 scripts/build-contract/test_relro.py` (all PASS).

## Current candidate state (context only, no lock change here)

The owned-source Python 3.11.18 + Cryptodome 3.24.2 build produced a
refreshed `libpython.zip.so` (`83ce8864368b24a5ceb86005c6ae869c5c592469206eed9d9f66f012447f9d62`,
20,285,721 bytes). Its full 128-ELF invariant audit is clean at both page
sizes (3 strict findings preserved), and the launcher is bit-identical to
the existing custody pin. The alpha7 lock update for the zip is NOT part of
this change: it waits on a new unpublished custody archive for the
refreshed inputs (originals preserved, never overwritten) so the staging
chain stays coherent instead of failing closed on a lock/archive mismatch.
