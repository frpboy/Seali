package com.frpboy.seali

import com.frpboy.seali.util.ApkUpdateVerifier
import com.frpboy.seali.util.ApkUpdateVerifier.Reason
import com.frpboy.seali.util.ApkUpdateVerifier.Result
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkUpdateVerifierTest {
    private val cert = "a".repeat(64)
    private val digest = "b".repeat(64)
    private val expected = ApkUpdateVerifier.Expectation("com.frpboy.seali", 100, setOf(cert), 1000L, "sha256:$digest")
    private val good = ApkUpdateVerifier.Archive("com.frpboy.seali", 101, setOf(cert), 1000L, digest)

    private fun refused(r: Result) = (r as Result.Refused).reason

    @Test fun `matching newer same-signer archive is accepted`() {
        assertEquals(Result.Ok, ApkUpdateVerifier.verify(expected, good))
    }
    @Test fun `no published digest or bad digest format refuses before any other check`() {
        assertEquals(Reason.NO_PUBLISHED_DIGEST, refused(ApkUpdateVerifier.verify(expected.copy(assetSha256 = null), good)))
        assertEquals(Reason.NO_PUBLISHED_DIGEST, refused(ApkUpdateVerifier.verify(expected.copy(assetSha256 = "md5:abc"), good)))
        assertEquals(Reason.NO_PUBLISHED_DIGEST, refused(ApkUpdateVerifier.verify(expected.copy(assetSha256 = "sha256:zz"), good)))
    }
    @Test fun `size and digest mismatches are refused`() {
        assertEquals(Reason.SIZE_MISMATCH, refused(ApkUpdateVerifier.verify(expected, good.copy(fileSize = 999))))
        assertEquals(Reason.DIGEST_MISMATCH, refused(ApkUpdateVerifier.verify(expected, good.copy(fileSha256 = "c".repeat(64)))))
    }
    @Test fun `wrong package, same or lower version code, unreadable archive are refused`() {
        assertEquals(Reason.PACKAGE_MISMATCH, refused(ApkUpdateVerifier.verify(expected, good.copy(packageName = "com.frpboy.seali.google"))))
        assertEquals(Reason.NOT_NEWER, refused(ApkUpdateVerifier.verify(expected, good.copy(versionCode = 100))))
        assertEquals(Reason.NOT_NEWER, refused(ApkUpdateVerifier.verify(expected, good.copy(versionCode = 99))))
        assertEquals(Reason.UNREADABLE_ARCHIVE, refused(ApkUpdateVerifier.verify(expected, good.copy(packageName = null))))
        assertEquals(Reason.UNREADABLE_ARCHIVE, refused(ApkUpdateVerifier.verify(expected, good.copy(versionCode = null))))
    }
    @Test fun `signing identity must equal the installed one exactly`() {
        assertEquals(Reason.CERTIFICATE_MISMATCH, refused(ApkUpdateVerifier.verify(expected, good.copy(signingCertSha256 = setOf("d".repeat(64))))))
        assertEquals(Reason.CERTIFICATE_MISMATCH, refused(ApkUpdateVerifier.verify(expected, good.copy(signingCertSha256 = setOf(cert, "d".repeat(64))))))
        assertEquals(Reason.NO_ARCHIVE_CERTIFICATE, refused(ApkUpdateVerifier.verify(expected, good.copy(signingCertSha256 = emptySet()))))
        assertEquals(Reason.NO_INSTALLED_CERTIFICATE, refused(ApkUpdateVerifier.verify(expected.copy(installedSigningCertSha256 = emptySet()), good)))
        assertEquals(Result.Ok, ApkUpdateVerifier.verify(expected, good.copy(signingCertSha256 = setOf(cert.uppercase()))))
    }
    @Test fun `digest normalisation and file hashing`() {
        assertEquals(digest, ApkUpdateVerifier.normalizeDigest(" SHA256:${digest.uppercase()} "))
        assertNull(ApkUpdateVerifier.normalizeDigest("a".repeat(63)))
        val f = Files.createTempFile("apk", ".bin").toFile()
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkUpdateVerifier.sha256(f))
            assertEquals(ApkUpdateVerifier.sha256(f), ApkUpdateVerifier.sha256("abc".toByteArray()))
        } finally { f.delete() }
    }
}
