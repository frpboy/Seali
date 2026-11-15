package com.frpboy.seali.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
