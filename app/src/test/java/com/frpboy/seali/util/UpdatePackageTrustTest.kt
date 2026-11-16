package com.frpboy.seali.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource

class UpdatePackageTrustTest {

    @Test
    fun `update must be strictly newer by versionCode`() {
        assertTrue(UpdateUtil.isUpgrade(archiveVersionCode = 200_000_173, currentVersionCode = 200_000_172))
        assertFalse(UpdateUtil.isUpgrade(archiveVersionCode = 200_000_172, currentVersionCode = 200_000_172))
        assertFalse(UpdateUtil.isUpgrade(archiveVersionCode = 200_000_171, currentVersionCode = 200_000_172))
    }

    @Test
    fun `update must support the device api level`() {
        assertTrue(UpdateUtil.supportsDevice(minSdkVersion = 26, deviceSdk = 26))
        assertTrue(UpdateUtil.supportsDevice(minSdkVersion = 26, deviceSdk = 35))
        assertFalse(UpdateUtil.supportsDevice(minSdkVersion = 34, deviceSdk = 26))
    }

    @Test
    fun `startup cleanup preserves fresh downloads and only reclaims strictly stale ones`() {
        val now = System.currentTimeMillis()
        // fresh download: never reclaimed, a pending install may still read it
        assertFalse(UpdateUtil.isStaleDownload(lastModifiedMs = now, nowMs = now))
        // inside the window: retained
        assertFalse(
            UpdateUtil.isStaleDownload(
                lastModifiedMs = now - UpdateUtil.STALE_DOWNLOAD_MAX_AGE_MS + 1,
                nowMs = now,
            )
        )
        // exactly at the boundary: retained, boundary age may still be a live handoff
        assertFalse(
            UpdateUtil.isStaleDownload(
                lastModifiedMs = now - UpdateUtil.STALE_DOWNLOAD_MAX_AGE_MS,
                nowMs = now,
            )
        )
        // strictly past the window: reclaimed, bounding cache accumulation
        assertTrue(
            UpdateUtil.isStaleDownload(
                lastModifiedMs = now - UpdateUtil.STALE_DOWNLOAD_MAX_AGE_MS - 1,
                nowMs = now,
            )
        )
    }

    @Test
    fun `each download gets its own file so overlapping operations cannot substitute packages`() {
        val dir = java.nio.file.Files.createTempDirectory("dl").toFile()
        val first = UpdateUtil.newApkDownloadFile(dir)
        val second = UpdateUtil.newApkDownloadFile(dir)
        assertTrue(first.absolutePath != second.absolutePath)
        assertEquals(dir, first.parentFile)
        assertEquals(dir, second.parentFile)
    }

    @Test
    fun `signer lineage requires the installed certificates to continue in the update`() {
        val certA = "a".repeat(64)
        val certB = "b".repeat(64)
        val certC = "c".repeat(64)
        // identical signer
        assertTrue(UpdateUtil.isSignerLineageContinuous(setOf(certA), setOf(certA)))
        // rotation: installed certificates remain in the update's history
        assertTrue(UpdateUtil.isSignerLineageContinuous(setOf(certA), setOf(certA, certB)))
        // different signer fails closed
        assertFalse(UpdateUtil.isSignerLineageContinuous(setOf(certA), setOf(certB)))
        // partial overlap is not continuity
        assertFalse(
            UpdateUtil.isSignerLineageContinuous(setOf(certA, certC), setOf(certA, certB))
        )
        // empty histories never qualify
        assertFalse(UpdateUtil.isSignerLineageContinuous(emptySet(), setOf(certA)))
        assertFalse(UpdateUtil.isSignerLineageContinuous(setOf(certA), emptySet()))
    }

    @Test
    fun `published checksum entry is parsed exactly`() {
        val hash = "a".repeat(64)
        assertEquals(
            hash,
            UpdateUtil.parseExpectedSha256(
                "$hash  Seali-main-arm64-v8a.apk\n",
                "Seali-main-arm64-v8a.apk",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing published checksum entry fails closed`() {
        UpdateUtil.parseExpectedSha256(
            "${"a".repeat(64)}  another.apk\n",
            "Seali-main-arm64-v8a.apk",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed published checksum fails closed`() {
        UpdateUtil.parseExpectedSha256(
            "not-a-sha256  Seali-main-arm64-v8a.apk\n",
            "Seali-main-arm64-v8a.apk",
        )
    }

    @Test
    fun `unknown content length is downloaded without inventing a percentage`() = runBlocking {
        val bytes = "complete-apk-payload".toByteArray()
        val body =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = Buffer().write(bytes)
            }
        val dir = java.nio.file.Files.createTempDirectory("unknown-length").toFile()
        val file = File(dir, "update.apk")

        val statuses = UpdateUtil.run { body.downloadFileWithProgress(file) }.toList()

        assertEquals(bytes.toList(), file.readBytes().toList())
        assertTrue(
            statuses.any {
                it is UpdateUtil.DownloadStatus.Progress && it.percent == null
            }
        )
        assertTrue(statuses.last() is UpdateUtil.DownloadStatus.Finished)
        file.delete()
        dir.delete()
    }

    @Test
    fun `empty unknown length response is rejected and its partial file is removed`() = runBlocking {
        val body =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = Buffer()
            }
        val dir = java.nio.file.Files.createTempDirectory("empty-download").toFile()
        val file = File(dir, "update.apk")
        var failed = false

        try {
            UpdateUtil.run { body.downloadFileWithProgress(file) }.toList()
        } catch (_: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(file.exists())
        dir.delete()
    }

    @Test
    fun `download urls stay on github hosts over https`() {
        assertTrue(
            UpdateUtil.isTrustedDownloadUrl(
                "https://github.com/frpboy/Seali/releases/download/v2.0.0-alpha.5/Seali-main-arm64-v8a.apk"
            )
        )
        assertTrue(
            UpdateUtil.isTrustedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/example"
            )
        )
        assertTrue(UpdateUtil.isTrustedDownloadUrl("https://api.github.com/repos/frpboy/Seali/releases/assets/1"))
        assertFalse(UpdateUtil.isTrustedDownloadUrl("http://github.com/frpboy/Seali/x.apk"))
        assertFalse(UpdateUtil.isTrustedDownloadUrl("https://evil.example.com/github.com/x.apk"))
        assertFalse(UpdateUtil.isTrustedDownloadUrl("https://github.com.evil.example.com/x.apk"))
        assertFalse(UpdateUtil.isTrustedDownloadUrl("not a url"))
    }

    @Test
    fun `abi asset selector is boundary-exact`() {
        // Real v2.0.0-alpha.5 asset names
        val assets =
            listOf(
                "Seali-main-arm64-v8a.apk",
                "Seali-main-armeabi-v7a.apk",
                "Seali-main-universal.apk",
                "Seali-main-x86.apk",
                "Seali-main-x86_64.apk",
            )
        fun selected(abi: String) = assets.firstOrNull { UpdateUtil.assetMatchesAbi(it, abi) }
        assertEquals("Seali-main-arm64-v8a.apk", selected("arm64-v8a"))
        assertEquals("Seali-main-armeabi-v7a.apk", selected("armeabi-v7a"))
        // x86 must not match the x86_64 asset and vice versa
        assertEquals("Seali-main-x86.apk", selected("x86"))
        assertEquals("Seali-main-x86_64.apk", selected("x86_64"))
        // universal is never an ABI match
        assertFalse(UpdateUtil.assetMatchesAbi("Seali-main-universal.apk", "arm64-v8a"))
        assertFalse(UpdateUtil.assetMatchesAbi(null, "arm64-v8a"))
    }
}
