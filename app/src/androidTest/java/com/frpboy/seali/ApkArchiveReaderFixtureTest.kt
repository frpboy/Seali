package com.frpboy.seali

import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.util.ApkArchiveReader
import com.frpboy.seali.util.ApkUpdateVerifier
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercises the SAME PackageManager extraction as the updater against the installed fixture APK.
 * The expected installed code is lowered only in memory to test the successful verifier branch.
 * No version is changed on device; no update is downloaded or installed. This is not a same-key staged upgrade.
 */
class ApkArchiveReaderFixtureTest {
    @Test fun installedApkAndArchiveExposeTheSameNonemptyCurrentSigningIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = File(context.applicationInfo.sourceDir)
        val digest = ApkUpdateVerifier.sha256(apk)
        val expected = ApkArchiveReader.expectation(context, apk.length(), "sha256:$digest")
        val archive = ApkArchiveReader.archive(context, apk)
        assertTrue(expected.installedSigningCertSha256.isNotEmpty())
        assertEquals(expected.installedSigningCertSha256, archive.signingCertSha256)
        assertEquals(context.packageName, archive.packageName)
        assertEquals(ApkUpdateVerifier.Result.Refused(ApkUpdateVerifier.Reason.NOT_NEWER),
            ApkUpdateVerifier.verify(expected, archive))
        assertEquals(ApkUpdateVerifier.Result.Ok,
            ApkUpdateVerifier.verify(expected.copy(installedVersionCode = expected.installedVersionCode - 1), archive))
        assertEquals(ApkUpdateVerifier.Result.Refused(ApkUpdateVerifier.Reason.CERTIFICATE_MISMATCH),
            ApkUpdateVerifier.verify(expected.copy(installedVersionCode = expected.installedVersionCode - 1,
                installedSigningCertSha256 = setOf("0".repeat(64))), archive))
    }

    @Test fun unparseableArchiveNeverObtainsPackageCodeOrSigners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("invalid-update", ".apk", context.cacheDir)
        try {
            file.writeText("not an APK")
            val archive = ApkArchiveReader.archive(context, file)
            assertNull(archive.packageName)
            assertNull(archive.versionCode)
            assertTrue(archive.signingCertSha256.isEmpty())
        } finally { file.delete() }
    }
}
