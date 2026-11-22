# Isolated software-emulator updater fixture (QUAL only)

The reviewed app/source lineage remains aa0c95931275489e59be1b3499b3e80d1b7474e6.
This separate qualification branch changes only the owner-trigger render route and diagnostics.
The earlier KVM owner run 45041377097 failed before boot and produced no artifacts.

This route explicitly uses `-accel off` and `ANDROID_I_WANT_MY_TCG=yes`. Current Android documentation
calls TCG unsupported and very slow. Boot success is NOT promised. No KVM probing, host permissions,
udev, sudo or group changes are used. No new run is authorized by preparing this code.

Official command-line documentation:
https://developer.android.com/studio/run/emulator-commandline

SDK image availability verified from Google's SDK package listing on 2026-11-22:
https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml
Package system-images;android-35;google_apis;x86_64, revision 9. If that revision changes, fail for review.
Emulator version is recorded, not claimed to be pinned or reproducible.

The new job builds ONLY the independent debug fixture (not the real Seali Application/native payload),
then allows 900 seconds to boot, 360 seconds for the page test and 240 seconds for extraction.
The Python runner has an 1800-second overall cap; the Actions job has a 50-minute outer limit.
Both fixture test results are separate. Partial PNGs are collected after presentation failure when
possible. Structured result.json is retained even on failure. Raw emulator logs, APKs, auth data and
production/user data are excluded from the artifact allowlist. Artifact retention is 3 days.

The original history-ui job is unchanged and independent; its failure is not hidden or counted as an
updater result. All release/signing/publication flags are required false by the updater job guard.
The owner must use this NEW branch/head and exact SHA after review, not the earlier aa0c9593 instructions.
No update is enabled, no release is made, no actual update is installed and no physical device is used.

## Follow-up after run 45044418306

That exact 5545bcc6 run built the fixture APKs, but a plain `emulator -version` returned127 before boot.
Only result.json survived; the command output was lost, so the root cause cannot be established.
Do not call it a TCG boot timeout, a page failure or an identified missing library.

This follow-up qualification branch preserves both bounded plain-control and headless/no-audio version
outputs, plus `ldd` for SDK emulator binaries that actually exist. The headless version command matches
the later boot backend. All these diagnostics run before any console/app starts; unlike raw emulator
boot logs, they contain no generated console auth material. Each output is capped at8192 characters;
the diagnostics are allowlisted as sdk-preflight.json. No packages or host permissions are changed.
A failing headless preflight stops before boot and retains its output. A GUI-control failure is recorded
but cannot alone disqualify a working headless backend.

A historic analogous report supports a hypothesis, NOT this run's diagnosis:
https://github.com/wix/Detox/issues/3613 (GUI version command with headless config, missing libpulse).
We have not verified which runtime dependency, if any, was missing on this runner's Ubuntu26 image.
No SDK executable or emulator has been run locally for this follow-up. Eight offline mocked tests pass;
they prove orchestration/diagnostic behavior only. A new dispatch needs separate scope, independent review
and new exact-head instructions; the previous approval was consumed and cannot authorize a retry.
