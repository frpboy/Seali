package com.frpboy.seali

import com.frpboy.seali.util.IGNORE_CONFIG
import com.frpboy.seali.util.ProbeProxy
import com.frpboy.seali.util.addCookieRequestOptions
import com.frpboy.seali.util.runWithWatchdog
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CookiePolicyProbeSafetyTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun captureUncaught() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun restoreUncaught() {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @Test
    fun stalledConnectionIsCountedNotThrownOutOfTheWorkerThread() {
        ProbeProxy().use { proxy ->
            proxy.socketTimeoutMs = 200
            Socket("127.0.0.1", proxy.port).use {
                // Connect and send nothing: the worker's read times out after 200 ms.
                Thread.sleep(1000)
            }
            assertTrue("worker exception escaped its thread: $uncaught", uncaught.isEmpty())
            assertEquals(1, proxy.workerFailures())
            proxy.reset()
            assertEquals(0, proxy.workerFailures())
        }
    }

    @Test
    fun normalConnectionIsRecordedWithoutFailure() {
        ProbeProxy().use { proxy ->
            Socket("127.0.0.1", proxy.port).use { s ->
                s.getOutputStream().write("GET /x.mp4 HTTP/1.1\r\nHost: example.test\r\nCookie: a=1\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                assertTrue(s.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 200"))
            }
            val seen = proxy.observations()
            assertEquals(1, seen.size)
            assertEquals("example.test", seen.single().host)
            assertEquals(setOf("a=1"), seen.single().cookies)
            assertEquals(0, proxy.workerFailures())
            assertTrue(uncaught.isEmpty())
        }
    }

    @Test
    fun watchdogFiresOnceWhenTheBlockOverruns() {
        val fired = AtomicBoolean(false)
        val result = runWithWatchdog(100, onTimeout = { fired.set(true) }) { Thread.sleep(600); "late" }
        assertTrue(fired.get())
        assertEquals("late", result)
    }

    @Test
    fun watchdogStaysQuietWhenTheBlockFinishesInTime() {
        val fired = AtomicBoolean(false)
        assertEquals(7, runWithWatchdog(400, onTimeout = { fired.set(true) }) { 7 })
        Thread.sleep(700)
        assertFalse(fired.get())
    }

    @Test
    fun watchdogStopsWhenTheBlockThrows() {
        val fired = AtomicBoolean(false)
        try {
            runWithWatchdog(300, onTimeout = { fired.set(true) }) { error("boom") }
        } catch (e: IllegalStateException) {
        }
        Thread.sleep(600)
        assertFalse(fired.get())
    }

    @Test
    fun cookieRequestsGetIgnoreConfigExactlyOnce() {
        val request = YoutubeDLRequest("https://example.test/v").addOption("--cookies", "/tmp/c.txt")
        assertTrue(request.addCookieRequestOptions())
        assertTrue(request.addCookieRequestOptions())
        assertEquals(1, request.buildCommand().count { it == IGNORE_CONFIG })
    }

    @Test
    fun requestsWithoutCookiesAreLeftAlone() {
        val request = YoutubeDLRequest("https://example.test/v").addOption("--no-playlist")
        assertFalse(request.addCookieRequestOptions())
        assertFalse(request.buildCommand().contains(IGNORE_CONFIG))
    }
}
