package com.frpboy.seali

import com.frpboy.seali.util.UpdateUtil
import com.frpboy.seali.util.UpdateUtil.AssetsItem
import com.frpboy.seali.util.UpdateUtil.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetSelectionTest {
    private fun release(vararg names: String, published: String? = "2026-11-19T10:00:00Z") =
        Release(publishedAt = published, assets = names.map { AssetsItem(name = it, size = 14_400_000L, digest = "sha256:" + "a".repeat(64)) })

    @Test fun `picks the apk for the preferred ABI and ignores other files`() {
        val r = release("Seali-1.0.0-arm64-v8a.apk.sha256", "Seali-1.0.0-armeabi-v7a.apk", "Seali-1.0.0-arm64-v8a.apk")
        assertEquals("Seali-1.0.0-arm64-v8a.apk", UpdateUtil.selectAsset(r, "arm64-v8a")?.name)
        assertEquals("Seali-1.0.0-armeabi-v7a.apk", UpdateUtil.selectAsset(r, "armeabi-v7a")?.name)
        assertNull(UpdateUtil.selectAsset(r, "x86_64"))
        assertNull(UpdateUtil.selectAsset(Release(assets = null), "arm64-v8a"))
    }
    @Test fun `release date label is a plain date or nothing`() {
        assertEquals("2026-11-19", UpdateUtil.releaseDateLabel(release("a.apk")))
        assertNull(UpdateUtil.releaseDateLabel(release("a.apk", published = "garbage")))
        assertNull(UpdateUtil.releaseDateLabel(Release()))
    }

    @Test fun `x86 never selects x86_64 and ambiguous assets are refused`() {
        assertNull(UpdateUtil.selectAsset(release("Seali-x86_64.apk"), "x86"))
        assertNull(UpdateUtil.selectAsset(release("Seali-a-arm64-v8a.apk", "Seali-b-arm64-v8a.apk"), "arm64-v8a"))
    }
    @Test fun `stable app channel excludes drafts prereleases and unversioned previews`() {
        val stable = Release(tagName = "v2.0.1", name = "Seali stable", preRelease = false)
        val alpha = Release(tagName = "v2.1.0-alpha.1", preRelease = true)
        val draft = Release(tagName = "v9.0.0", draft = true)
        val preview = Release(name = "Seali UI Preview - 2026-11-10", preRelease = false)
        val disguisedPrerelease = Release(tagName = "v8.0.0", preRelease = true)
        val all = listOf(stable, alpha, draft, preview, disguisedPrerelease)
        assertEquals(stable, UpdateUtil.selectLatestRelease(all, stableOnly = true))
        assertEquals(disguisedPrerelease, UpdateUtil.selectLatestRelease(all, stableOnly = false))
        assertNull(UpdateUtil.selectLatestRelease(listOf(alpha, draft, preview), stableOnly = true))
        assertNull(UpdateUtil.releaseVersionLabel(Release(tagName = "nightly", name = "New nightly")))
    }
}
