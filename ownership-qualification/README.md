# Ownership fixture proposal, source-only

This root is separate from the existing presentation fixture and every production app build.
No workflow references it. No APK has been assembled, no device/emulator started, and no screenshots
have been captured for it. It is not a ready-to-run or qualified route.

Exact copied production behavior: UpdateCoordinator, UpdateCoordinatorHost, UpdateEnvironment,
UpdateDialogImpl, UpdateProgressContent, session state, file store, blocking-call guard, stream helper,
APK verifier and Android archive reader. The Sync allowlist excludes ProductionUpdateEnvironment,
Seali App, native payloads, production resource graphs and signing secrets.

Injected boundaries are conspicuous:
- FixtureEnvironment.enabled=true is only an injected fixture capability, not a changed production gate.
- Discovery returns invented release information. Download writes three non-APK bytes. It does NOT
  exercise production GitHub release selection, HTTP, archive/signature validation or the real engine.
- Permission is an explicit fixture Boolean. Host permission launch callback increments a counter.
  Continue still uses the real coordinator/session transition, but this is not Android settings proof.
- Installer-intent creation and launch are substituted. No real package installer opens. This does NOT
  prove same-signer upgrade, production signing, URI access by an external installer or actual install.
- The fixture UpdateUtil is a named DTO/label adapter, not copied production update machinery.

Proposed instrumentation contracts cover automatic/manual contention, activity recreation retaining
one owner, once-only effect after recreation, denial/stale Continue, explicit maintenance warning,
real internal FileProvider URI read/refusal paths. Additional tests still needed for cleanup errors,
ViewModel clearing during blocked IO, process death, and production Android permission/install behavior.
Screenshots must come from actual Android execution, not static renders, and be labelled fixture data.

No native ARM64 inputs are needed for these ownership contracts; engine/runtime qualification is separate.
No signer-upgrade test exists here. Adding a pair of fixture-signed APKs later would prove only the fixture
signing family, not a Seali upgrade. Production identifiers/signers/gates remain unchanged.

Production source delta extracts an explicit effects interface, supplies its real implementation from
AppEntry, and allows host launch callbacks with unchanged real defaults. Exact independent review must
verify production behavior is preserved. Targeted compilation against existing app classes is below the
full Gradle assembly/test bar. This separate root's source/test dependency graph has not been built.

## Local compile correction

Production up-to-date text remains resource-backed through ProductionUpdateEnvironment.upToDateMessage();
the fixture returns its own labelled invented text. This separate root has android.useAndroidX=true.
Catalog/plugin references and its own main/androidTest dependency graph were resolved by the actual
compileDebugAndroidTestKotlin Gradle task. This task compiles test sources only; no APK assemble/install,
emulator, instrumentation execution or screenshots are implied. Initial offline resolution lacked
core/okio transitive versions, which were fetched from the declared Google/Maven repositories on the
successful compile-only invocation. No version substitution or production dependency change was made.
