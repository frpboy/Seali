package com.frpboy.seali.util

import com.frpboy.seali.util.UpdateUtil.Release
import com.frpboy.seali.util.UpdateUtil.toVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseSelectionTest {

    private fun release(name: String, draft: Boolean = false, preRelease: Boolean = true) =
        Release(name = name, draft = draft, preRelease = preRelease)

    @Test
    fun `drafts are never selected`() {
        val releases =
            listOf(
                release("Seali 2.0.0-alpha.8 (alpha prerelease)", draft = true),
                release("Seali 2.0.0-alpha.5 (alpha prerelease)"),
            )
        val selected = UpdateUtil.selectLatestRelease(releases, stableOnly = false)
        assertEquals("Seali 2.0.0-alpha.5 (alpha prerelease)", selected?.name)
    }

    @Test
    fun `all drafts means no update candidate`() {
        val releases = listOf(release("Seali 2.0.0-alpha.8 (alpha prerelease)", draft = true))
        assertNull(UpdateUtil.selectLatestRelease(releases, stableOnly = false))
    }

    @Test
    fun `prerelease channel picks newest prerelease`() {
        val releases =
            listOf(
                release("Seali 2.0.0-alpha.5 (alpha prerelease)"),
                release("Seali 2.0.0-alpha.7 (alpha prerelease)"),
            )
        assertEquals(
            "Seali 2.0.0-alpha.7 (alpha prerelease)",
            UpdateUtil.selectLatestRelease(releases, stableOnly = false)?.name,
        )
    }

    @Test
    fun `stable channel ignores prerelease versions`() {
        val releases =
            listOf(
                release("Seali 2.0.0-alpha.7 (alpha prerelease)"),
                release("Seali 1.4.0", preRelease = false),
            )
        assertEquals(
            "Seali 1.4.0",
            UpdateUtil.selectLatestRelease(releases, stableOnly = true)?.name,
        )
    }

    @Test
    fun `stable channel rejects prerelease flag even when the display name looks stable`() {
        val releases =
            listOf(
                release("Seali 2.0.0", preRelease = true),
                release("Seali 1.4.0", preRelease = false),
            )
        assertEquals(
            "Seali 1.4.0",
            UpdateUtil.selectLatestRelease(releases, stableOnly = true)?.name,
        )
    }

    @Test
    fun `release display names parse to comparable versions`() {
        val alpha5 = "Seali 2.0.0-alpha.5 (alpha prerelease)".toVersion()
        val alpha7 = "2.0.0-alpha.7".toVersion()
        val fdroidVariant = "2.0.0-alpha.7-(F-Droid)".toVersion()
        assertTrue(alpha5 < alpha7)
        // F-Droid versionName decoration must not break parsing or downgrade detection
        assertEquals(0, alpha7.compareTo(fdroidVariant))
    }
}
