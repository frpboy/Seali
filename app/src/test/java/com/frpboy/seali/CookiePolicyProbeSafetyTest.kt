package com.frpboy.seali

import com.frpboy.seali.util.IGNORE_CONFIG
import com.frpboy.seali.util.ProbeProxy
import com.frpboy.seali.util.addCookieRequestOptions
import com.frpboy.seali.util.runWithDeadline
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun deadlineReleasesTheCallerEvenWhenTheBlockIgnoresCancelAndInterrupt() {
        val fired = AtomicBoolean(false)
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            runWithDeadline(150, onTimeout = { fired.set(true) }) {
                // Models a launcher that neither the cancel nor an interrupt can unblock.
                while (release.count > 0) {
                    try { release.await() } catch (e: InterruptedException) { }
                }
            }
            fail("expected a timeout")
        } catch (e: TimeoutException) {
        }
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue(fired.get())
        assertTrue("caller waited $waitedMs ms", waitedMs < 3000)
    }

    @Test
    fun deadlineReturnsTheValueWithoutCancellingWhenTheBlockFinishesInTime() {
        val fired = AtomicBoolean(false)
        assertEquals(7, runWithDeadline(2000, onTimeout = { fired.set(true) }) { 7 })
        Thread.sleep(300)
        assertFalse(fired.get())
    }

    @Test
    fun deadlineRethrowsTheBlocksOwnExceptionWithoutCancelling() {
        val fired = AtomicBoolean(false)
        try {
            runWithDeadline(2000, onTimeout = { fired.set(true) }) { error("boom") }
            fail("expected the block's exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertFalse(fired.get())
    }

    @Test
    fun deadlineInterruptsABlockThatHonoursInterrupts() {
        val interrupted = CountDownLatch(1)
        try {
            runWithDeadline(100, onTimeout = {}) {
                try { Thread.sleep(10_000) } catch (e: InterruptedException) { interrupted.countDown() }
            }
            fail("expected a timeout")
        } catch (e: TimeoutException) {
        }
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
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
