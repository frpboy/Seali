package com.frpboy.seali

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the startup crash caught by the Firebase Test Lab
 * robo run matrix-2wkcs6zz3bjp5 (API 26): the sentry-android-core
 * SentryInitProvider auto-initializes the SDK before Application.onCreate
 * and throws FATAL "DSN is required" when the merged manifest carries no
 * io.sentry.dsn meta-data. Seali initializes Sentry explicitly in
 * CrashReporting.init (a no-op without a configured DSN), so the SDK's
 * manifest auto-init must stay disabled.
 *
 * Scope label: this is a source-manifest assertion (JVM unit test), not a
 * merged-manifest or on-device proof. The merged release manifest is
 * produced by the build; the runtime check belongs to a device run.
 */
class SentryAutoInitDisabledTest {

    private val manifest =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `manifest disables sentry auto init`() {
        val entry =
            Regex(
                """<meta-data[^>]*android:name\s*=\s*"io\.sentry\.auto-init"[^>]*android:value\s*=\s*"false"\s*/?>""",
            )
        assertTrue(
            "AndroidManifest.xml must declare io.sentry.auto-init=false; " +
                "without it the SentryInitProvider crashes startup when no DSN is configured",
            entry.containsMatchIn(manifest),
        )
    }

    @Test
    fun `manifest does not configure a sentry dsn`() {
        // A DSN is supplied only via the SEALI_SENTRY_DSN build property
        // (BuildConfig), never as manifest meta-data. This guards the
        // privacy boundary: no endpoint is baked into the manifest.
        val dsnEntry =
            Regex(
                """<meta-data[^>]*android:name\s*=\s*"io\.sentry\.dsn""",
            )
        assertTrue(
            "AndroidManifest.xml must not hard-code an io.sentry.dsn meta-data element",
            !dsnEntry.containsMatchIn(manifest),
        )
    }
}
