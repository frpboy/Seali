package com.frpboy.seali.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision tests for the legacy "Seal" folder handling. These cover the directory-selection
 * and detection logic only; they do not exercise MMKV, startup, or upgrade behavior on device.
 */
class FileUtilDirectoryTest {

    private fun newTempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "seali-test-$tag-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun privateDir_legacyOnly_keepsServingLegacy() {
        val base = newTempDir("legacyonly")
        val legacy = File(base, ".Seal").apply { mkdirs() }
        File(legacy, "old-download.mp4").createNewFile()
        assertEquals(legacy, FileUtil.selectPrivateDownloadDirectory(base))
    }

    @Test
    fun privateDir_currentOnly_usesCurrent() {
        val base = newTempDir("currentonly")
        val current = File(base, ".Seali").apply { mkdirs() }
        assertEquals(current, FileUtil.selectPrivateDownloadDirectory(base))
    }

    @Test
    fun privateDir_bothExist_prefersCurrent_leavesLegacyUntouched() {
        val base = newTempDir("both")
        val current = File(base, ".Seali").apply { mkdirs() }
        val legacy = File(base, ".Seal").apply { mkdirs() }
        val legacyFile = File(legacy, "keep.me").apply { createNewFile() }
        assertEquals(current, FileUtil.selectPrivateDownloadDirectory(base))
        assertTrue(legacyFile.exists())
    }

    @Test
    fun privateDir_neitherExists_defaultsToCurrent() {
        val base = newTempDir("neither")
        assertEquals(File(base, ".Seali"), FileUtil.selectPrivateDownloadDirectory(base))
    }

    @Test
    fun privateDir_legacyEmptyDirStillServed_whenCurrentAbsent() {
        val base = newTempDir("legacyempty")
        val legacy = File(base, ".Seal").apply { mkdirs() }
        assertEquals(legacy, FileUtil.selectPrivateDownloadDirectory(base))
    }

    @Test
    fun legacyCommandDir_exactLegacyDefault_detected() {
        assertTrue(
            FileUtil.isLegacySealCommandDirectory(
                "/storage/emulated/0/Download/Seal",
                "/storage/emulated/0/Download/Seal",
            )
        )
    }

    @Test
    fun legacyCommandDir_userChosenOtherPath_notDetected() {
        assertFalse(
            FileUtil.isLegacySealCommandDirectory(
                "/storage/emulated/0/Download/MyStuff",
                "/storage/emulated/0/Download/Seal",
            )
        )
    }

    @Test
    fun legacyCommandDir_newSealiDefault_notDetected() {
        assertFalse(
            FileUtil.isLegacySealCommandDirectory(
                "/storage/emulated/0/Download/Seali",
                "/storage/emulated/0/Download/Seal",
            )
        )
    }

    @Test
    fun legacyCommandDir_empty_notDetected() {
        assertFalse(
            FileUtil.isLegacySealCommandDirectory("", "/storage/emulated/0/Download/Seal")
        )
    }
}
