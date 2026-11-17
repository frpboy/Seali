package com.frpboy.seali.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskCompletionJournalTest {
    private val base: File = Files.createTempDirectory("journal-test").toFile()
    private val root: File = File(base, "Seali").apply { mkdirs() }
    private fun journal() = TaskCompletionJournal(File(base, "j"))

    private fun finished(j: TaskCompletionJournal, id: String, path: String, dest: String = root.path) {
        j.prepare(id)
        j.recordDestination(id, dest)
        j.fileFor(id).writeText(path + "\n")
    }

    @Test
    fun singlePathInsideTheTasksDestinationIsReadBackWithItsWriteTime() {
        val j = journal()
        val p = File(root, "video [abc].mp4").path
        finished(j, "task-a", p)
        val e = j.read("task-a")!!
        assertEquals(p, e.path)
        assertEquals(j.fileFor("task-a").lastModified(), e.writtenAtMs)
    }

    @Test
    fun missingEmptyOrMultiLineRecordsAreIgnored() {
        val j = journal()
        assertNull(j.read("none"))
        finished(j, "empty", "")
        assertNull(j.read("empty"))
        j.prepare("multi")
        j.recordDestination("multi", root.path)
        j.fileFor("multi").writeText(File(root, "a.mp4").path + "\n" + File(root, "b.mp4").path + "\n")
        assertNull(j.read("multi"))
    }

    @Test
    fun aPathOutsideTheConfiguredDestinationIsRejected() {
        val j = journal()
        finished(j, "t1", File(base, "elsewhere/x.mp4").path)
        assertNull(j.read("t1"))
        finished(j, "t2", File(root, "../escape.mp4").path)
        assertNull(j.read("t2"))
        finished(j, "t3", root.path)
        assertNull(j.read("t3"))
        finished(j, "t4", File(base, "Seali-other/x.mp4").path)
        assertNull(j.read("t4"))
    }

    @Test
    fun aRecordWithoutOrWithAnotherTasksBindingIsRejected() {
        val j = journal()
        j.prepare("t")
        j.fileFor("t").writeText(File(root, "v.mp4").path + "\n")
        assertNull(j.read("t"))
        j.recordDestination("other", root.path)
        // Forge a meta record that names a different task id under this task's file name.
        val metaOfT = File(base, "j").listFiles()!!.first { it.name.endsWith(".meta") && it.readText().startsWith("other") }
        val forged = File(base, "j/" + j.fileFor("t").name.removeSuffix(".txt") + ".meta")
        forged.writeText(metaOfT.readText())
        assertNull(j.read("t"))
    }

    @Test
    fun prepareClearsAPreviousRunsRecordAndClearAllRemovesEverything() {
        val j = journal()
        finished(j, "t", File(root, "old.mp4").path)
        j.prepare("t")
        assertNull(j.read("t"))
        finished(j, "a", File(root, "a.mp4").path)
        finished(j, "b", File(root, "b.mp4").path)
        j.clearAll()
        assertNull(j.read("a"))
        assertNull(j.read("b"))
        assertFalse(File(base, "j").listFiles()!!.any { it.isFile })
    }

    @Test
    fun clearRemovesRecordsOfThatTaskOnly() {
        val j = journal()
        finished(j, "a", File(root, "a.mp4").path)
        finished(j, "b", File(root, "b.mp4").path)
        j.clear("a")
        assertNull(j.read("a"))
        assertEquals(File(root, "b.mp4").path, j.read("b")?.path)
        assertNotEquals(j.fileFor("a"), j.fileFor("b"))
    }
}
