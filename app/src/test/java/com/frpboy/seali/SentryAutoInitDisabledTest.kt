package com.frpboy.seali

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the startup crash caught by the Firebase Test Lab
 * robo run matrix-2wkcs6zz3bjp5 (API 26): the sentry-android-core
 * SentryInitProvider auto-initializes the SDK before Application.onCreate
 * and throws FATAL "DSN is required" when the merged manifest has no
 * sentry DSN meta-data. Note the provider reads the DSN from manifest
 * meta-data, not from BuildConfig, so a DSN set only via the
 * SEALI_SENTRY_DSN build property would not have prevented that crash
 * either. Seali initializes Sentry explicitly in CrashReporting.init
 * (a no-op without a configured DSN), so the SDK's manifest auto-init
 * must stay disabled and no DSN element may be baked into the manifest.
 *
 * Scope label: the real-manifest assertions below are source-manifest
 * checks (JVM unit test). The merged-manifest output of the manifest
 * merger is verified separately by
 * scripts/release-qualification/verify-merged-manifest-sentry.py in CI,
 * and runtime proof belongs to a device run. The fixture assertions
 * here execute the matcher logic itself (comment stripping, attribute
 * order, value handling) so a matcher regression fails loudly.
 */
class SentryAutoInitDisabledTest {

    private val manifest =
        File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `source manifest disables sentry auto init`() {
        assertTrue(
            "AndroidManifest.xml must declare io.sentry.auto-init=false; " +
                "without it the SentryInitProvider crashes startup when no DSN is configured",
            SentryManifestCheck.autoInitDisabled(manifest),
        )
    }

    @Test
    fun `source manifest does not bake a sentry dsn`() {
        assertFalse(
            "AndroidManifest.xml must not hard-code a sentry DSN meta-data element; " +
                "the DSN only ever comes from the SEALI_SENTRY_DSN build property",
            SentryManifestCheck.hasBakedDsn(manifest),
        )
    }

    @Test
    fun `matcher ignores commented out meta data`() {
        val xml = """
            <application>
                <!-- <meta-data android:name="io.sentry.auto-init" android:value="false" /> -->
            </application>
        """.trimIndent()
        assertFalse(SentryManifestCheck.autoInitDisabled(xml))
    }

    @Test
    fun `matcher accepts either attribute order`() {
        val flipped = """
            <application>
                <meta-data android:value="false" android:name="io.sentry.auto-init" />
            </application>
        """.trimIndent()
        assertTrue(SentryManifestCheck.autoInitDisabled(flipped))
    }

    @Test
    fun `matcher rejects auto init true`() {
        val xml = """
            <application>
                <meta-data android:name="io.sentry.auto-init" android:value="true" />
            </application>
        """.trimIndent()
        assertFalse(SentryManifestCheck.autoInitDisabled(xml))
    }

    @Test
    fun `matcher rejects missing declaration`() {
        assertFalse(SentryManifestCheck.autoInitDisabled("<application/>"))
    }

    @Test
    fun `matcher detects a baked dsn element`() {
        val xml = """
            <application>
                <meta-data android:name="io.sentry.dsn" android:value="https://example.invalid/1" />
            </application>
        """.trimIndent()
        assertTrue(SentryManifestCheck.hasBakedDsn(xml))
    }

    @Test
    fun `matcher ignores dsn mention in a comment`() {
        val xml = """
            <application>
                <!-- io.sentry.dsn stays out of the manifest by design -->
            </application>
        """.trimIndent()
        assertFalse(SentryManifestCheck.hasBakedDsn(xml))
    }
}

/**
 * Manifest meta-data probe used by [SentryAutoInitDisabledTest]. Strips
 * XML comments before matching and accepts either attribute order, so a
 * commented-out declaration or a reordered attribute list cannot produce
 * a false pass. Source-text level: the merged manifest is checked by
 * verify-merged-manifest-sentry.py.
 */
internal object SentryManifestCheck {
    private val comments = Regex("<!--[\\s\\S]*?-->")
    private val metaDataTag = Regex("<meta-data\\b[^>]*/?>")
    private val nameAttr = Regex("""android:name\s*=\s*"([^"]+)"""")
    private val valueAttr = Regex("""android:value\s*=\s*"([^"]+)"""")

    private fun entries(xml: String): List<Pair<String?, String?>> =
        metaDataTag.findAll(comments.replace(xml, "")).map { tag ->
            nameAttr.find(tag.value)?.groupValues?.get(1) to
                valueAttr.find(tag.value)?.groupValues?.get(1)
        }.toList()

    fun autoInitDisabled(xml: String): Boolean =
        entries(xml).any { (name, value) -> name == "io.sentry.auto-init" && value == "false" }

    fun hasBakedDsn(xml: String): Boolean =
        entries(xml).any { (name, _) -> name == "io.sentry.dsn" }
}
