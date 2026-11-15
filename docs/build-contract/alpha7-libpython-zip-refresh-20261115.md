# alpha7 lock: libpython.zip.so refresh (2026-11-15)

## What changed

One lock entry in `scripts/build-contract/alpha7-input-lock.json`:

- `app/src/main/jniLibs/arm64-v8a/libpython.zip.so`
  - sha256 `38f6e236ba307217895ffd180569b6cacb7c854cc43ad8c9eac461c3430638df` -> `83ce8864368b24a5ceb86005c6ae869c5c592469206eed9d9f66f012447f9d62`
  - bytes `20587205` -> `20285721`

Nothing else in the lock changed. `libpython.so` (`9726de75...`) is
bit-identical to the existing custody pin; the archive, custody, tracked
files and maven lock blocks are untouched, and the original custody
archive/release are preserved as-is (not overwritten).

## Why

The owned-source native build (Python 3.11.18 + Cryptodome 3.24.2 refresh)
was executed end-to-end and its zip output was reviewed twice
independently: hash, 1188-entry file ledger, 128-ELF audit and version
evidence all match the receipts. The earlier strict RELRO finding on three
retained stage libraries (libcrypto/libssl/libexpat) was determined by two
independent reviews plus an invariant proof to be an over-restrictive
static layout finding, not a runtime defect; the strict findings are
preserved in the receipts, and the invariant validator + fixtures now live
in `scripts/build-contract/relro_invariants.py` and
`scripts/build-contract/test_relro.py`.

The zip is a rebuilt output, NOT bit-reproduced against alpha7 custody;
only the launcher is an exact custody byte match.

## Gate state (fail closed until resolved)

The lock's `archive` and `custody` blocks still reference the original
alpha7 custody archive, which contains the OLD zip. The staging chain
(`stage-alpha7-inputs.py`) verifies every archive member against this lock
and will therefore fail closed if an owner-triggered staging lane is run
before the remaining custody step lands. That is the intended behavior.

Remaining deliberate step, not part of this change:

1. Produce the new inputs archive from the reviewed candidate (same member
   set, new zip).
2. Register it as a NEW unpublished custody release (originals preserved).
3. Update the lock's `archive` and `custody` fields to the new archive in a
   separate reviewed change.

Until then: no owner-triggered release workflow, no publish.
