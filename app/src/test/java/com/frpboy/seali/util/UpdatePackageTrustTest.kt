package com.frpboy.seali.util

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
}
