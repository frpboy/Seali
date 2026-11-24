package com.frpboy.seali

import com.frpboy.seali.util.UpdateDownloadStream
import com.frpboy.seali.util.UpdateUtil
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadStreamTest {
    @Test fun exactFileSurvivesSuccessfulCollection() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            val states = UpdateDownloadStream.copy("abc".toResponseBody(), f, 3).toList()
            assertEquals("abc", f.readText())
            assertTrue(states.last() is UpdateUtil.DownloadStatus.Finished)
        } finally { f.delete() }
    }
    @Test fun declaredSizeMismatchDeletesPriorFile() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            try { UpdateDownloadStream.copy("abc".toResponseBody(), f, 4).collect(); fail() }
            catch (_: IllegalStateException) { }
            assertFalse(f.exists())
        } finally { f.delete() }
    }
    @Test fun collectorCancellationDeletesPartialFile() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            UpdateDownloadStream.copy("abc".toResponseBody(), f, 3).take(1).collect()
            assertFalse(f.exists())
        } finally { f.delete() }
    }
    @Test fun verificationFailureFromFinishedCollectorDeletesFile() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            try {
                UpdateDownloadStream.copy("abc".toResponseBody(), f, 3).collect {
                    if (it is UpdateUtil.DownloadStatus.Finished) error("fixture verification refusal")
                }
                fail()
            } catch (_: IllegalStateException) { }
            assertFalse(f.exists())
        } finally { f.delete() }
    }
    private fun unknownLengthBody(text: String) = object : okhttp3.ResponseBody() {
        private val bytes = okio.Buffer().writeUtf8(text)
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength() = -1L
        override fun source(): okio.BufferedSource = bytes
    }
    @Test fun chunkedBodyUsesPublishedSize() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            UpdateDownloadStream.copy(unknownLengthBody("abc"), f, 3).collect()
            assertEquals("abc", f.readText())
        } finally { f.delete() }
    }
    @Test fun chunkedOverflowAndTruncationAreRefused() = runBlocking {
        for (size in listOf(2L, 4L)) {
            val f = Files.createTempFile("update-stream", ".apk").toFile()
            try {
                try { UpdateDownloadStream.copy(unknownLengthBody("abc"), f, size).collect(); fail() }
                catch (_: IllegalStateException) { }
                assertFalse(f.exists())
            } finally { f.delete() }
        }
    }

    @Test fun progressEmitsOnlyChangedPercentages() = runBlocking {
        val f = Files.createTempFile("update-stream", ".apk").toFile()
        try {
            val content = "a".repeat(2_000_000)
            val states = UpdateDownloadStream.copy(content.toResponseBody(), f, content.length.toLong()).toList()
            val percents = states.filterIsInstance<UpdateUtil.DownloadStatus.Progress>().map { it.percent }
            assertEquals(percents.distinct(), percents)
            assertTrue(percents.size <= 101)
            assertEquals(0, percents.first())
            assertEquals(100, percents.last())
        } finally { f.delete() }
    }

}
