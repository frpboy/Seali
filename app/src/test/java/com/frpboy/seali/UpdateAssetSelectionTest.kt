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
}
