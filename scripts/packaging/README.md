# License bundle packaging stage

`package_license_bundle.py` embeds `usr/share/licenses/*` into the reviewed
`libpython.zip.so` payload. It takes the pristine payload zip (all non-license
entries final) and appends license entries assembled from pinned producer
source trees. Fail-closed on: missing sources, notices under 50 bytes,
placeholder tokens (MISSING / PLACEHOLDER / TODO / unfilled `<year>` /
`<copyright holder>` templates), and missing required notice markers.

Producer-local roots are passed as a JSON file:
`{"build": ..., "termux": ..., "ndk": ..., "upstream": ...}` pointing at the
exact source trees the build used (openssl/expat trees byte-pinned to the
recipe evidence archive hashes).

`test_license_bundle.py` is the coverage contract for the produced zip:
- every required component has license coverage;
- automatic actual-library coverage via `lib_coverage.py`: all shipped `.so`
  entries (soname chains resolved, DT_NEEDED parsed) map to a covered
  component, and external NEEDED entries are limited to Android system libs
  (libc/libm/libdl). Any future new library fails the test until the
  component mapping and license bundle are updated;
- placeholder and template rejection (this catches empty/MISSING notices and
  unfilled termux template LICENSE files).

Run: `python3 scripts/packaging/test_license_bundle.py <path-to-libpython.zip.so>`
