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
    fun privateDir_regularFileNamedLegacy_notTreatedAsDirectory() {
        val base = newTempDir("regfile")
        File(base, ".Seal").createNewFile() // a stray file, not a directory
        assertEquals(File(base, ".Seali"), FileUtil.selectPrivateDownloadDirectory(base))
    }

    @Test
    fun privateDir_repeatedSelectionIsStable_andCreatesNothing() {
        val base = newTempDir("repeat")
        File(base, ".Seal").mkdirs()
        val first = FileUtil.selectPrivateDownloadDirectory(base)
        val second = FileUtil.selectPrivateDownloadDirectory(base)
        assertEquals(first, second)
        // Selection is read-only: no new directories appear as a side effect.
        assertEquals(listOf(".Seal"), base.list()?.toList()?.sorted())
    }

    @Test
    fun privateDir_neitherExist_repeatedSelectionStable_andCreatesNothing() {
        val base = newTempDir("repeatnone")
        val first = FileUtil.selectPrivateDownloadDirectory(base)
        assertEquals(first, FileUtil.selectPrivateDownloadDirectory(base))
        assertTrue(base.list()?.isEmpty() != false)
    }

    @Test
    fun legacyCommandDir_exactMatchIsStable_acrossRepeatedChecks() {
        val stored = "/storage/emulated/0/Download/Seal"
        val legacy = "/storage/emulated/0/Download/Seal"
        // The opt-in hint visibility decision must be deterministic.
        repeat(3) {
            assertTrue(FileUtil.isLegacySealCommandDirectory(stored, legacy))
        }
    }

    @Test
    fun legacyCommandDir_empty_notDetected() {
        assertFalse(
            FileUtil.isLegacySealCommandDirectory("", "/storage/emulated/0/Download/Seal")
        )
    }
}
