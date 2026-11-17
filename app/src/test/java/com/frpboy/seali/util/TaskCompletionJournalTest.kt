package com.frpboy.seali.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskCompletionJournalTest {
    private fun journal(): TaskCompletionJournal =
        TaskCompletionJournal(Files.createTempDirectory("journal").toFile().resolve("j"))

    @Test
    fun singlePathIsReadBackWithItsWriteTime() {
        val j = journal()
        val f = j.prepare("task-a")
        f.writeText("/storage/emulated/0/Seali/video [abc].mp4\n")
        val e = j.read("task-a")!!
        assertEquals("/storage/emulated/0/Seali/video [abc].mp4", e.path)
        assertEquals(f.lastModified(), e.writtenAtMs)
    }

    @Test
    fun missingEmptyOrMultiLineRecordsAreIgnored() {
        val j = journal()
        assertNull(j.read("none"))
        j.prepare("empty").writeText("\n")
        assertNull(j.read("empty"))
        j.prepare("multi").writeText("/a.mp4\n/b.mp4\n")
        assertNull(j.read("multi"))
    }

    @Test
    fun prepareClearsAPreviousRunsRecord() {
        val j = journal()
        j.prepare("t").writeText("/old.mp4\n")
        j.prepare("t")
        assertNull(j.read("t"))
    }

    @Test
    fun recordsAreKeyedPerTask() {
        val j = journal()
        assertNotEquals(j.fileFor("a"), j.fileFor("b"))
        j.prepare("a").writeText("/a.mp4\n")
        assertNull(j.read("b"))
    }
}
