package com.frpboy.seali

import com.frpboy.seali.util.CandidateFeaturePolicy
import com.frpboy.seali.util.UpdateUtil
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadPreflightTest {
    private val asset = UpdateUtil.AssetsItem(name = "Seali-arm64-v8a.apk", size = 123,
        digest = "sha256:" + "a".repeat(64), browserDownloadUrl = "https://example.invalid/update.apk")
    private fun release(vararg assets: UpdateUtil.AssetsItem) = UpdateUtil.Release(assets = assets.toList())
    private fun refuses(r: UpdateUtil.Release, arch: String? = "arm64-v8a") {
        assertThrows(IllegalStateException::class.java) { UpdateUtil.requireDownloadAsset(r, arch) }
    }
    @Test fun uniqueAssetWithRequiredMetadataIsSelected() {
        assertEquals(asset, UpdateUtil.requireDownloadAsset(release(asset), "arm64-v8a"))
    }
    @Test fun missingAbiMissingAssetAndAmbiguityAreVisibleFailures() {
        refuses(release(asset), null)
        refuses(release())
        refuses(release(asset), "x86")
        refuses(release(asset, asset.copy(name = "Other_arm64-v8a.apk")))
    }
    @Test fun missingUrlSizeAndDigestRefuseBeforeDownload() {
        refuses(release(asset.copy(browserDownloadUrl = null)))
        refuses(release(asset.copy(browserDownloadUrl = " ")))
        refuses(release(asset.copy(size = null)))
        refuses(release(asset.copy(size = 0)))
        refuses(release(asset.copy(size = -1)))
        refuses(release(asset.copy(digest = null)))
        refuses(release(asset.copy(digest = "md5:abc")))
    }
    @Test fun candidateGateRemainsClosedForEveryDistribution() {
        assertFalse(CandidateFeaturePolicy.APP_UPDATES_AVAILABLE)
        listOf("generic", "githubPreview", "google", "fdroid", "unknown").forEach {
            assertFalse(CandidateFeaturePolicy.appUpdatesEnabled(it))
        }
    }
}
