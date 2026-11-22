# Update presentation fixture

Separate Android application, package `com.frpboy.seali.updaterfixture`, using a plain framework Application.
The Gradle Sync tasks copy only the actual UpdateProgressPage.kt and UpdateProgressPageTest.kt from the app.
No Seali Application, engine, native payload, internet permission, installation permission, signing key,
release distribution or user data is included. Android's generated debug key signs only this fixture app.

This qualifies presentation and button callback boundaries only. It does not qualify GitHub retrieval,
PackageManager signing extraction, actual updater coroutine wiring, settings permissions or installation.

Normal CI compiles the host and instrumentation sources without executing them. Owner-triggered
android_ci.yml `run_history_ui=true` also schedules the independent updater-ui job. Supply the exact
reviewed 40-hex branch head in `expected_qualification_sha`; keep every release/publication flag false.
The original history-ui job is unchanged, has its own outcome and is not a prerequisite for updater-ui.

Expected output is five PNGs and the instrumentation result. No render has been performed until those
outputs actually exist. Do not label successful compilation as visual qualification.
