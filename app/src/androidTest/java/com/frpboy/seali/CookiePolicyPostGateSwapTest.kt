package com.frpboy.seali

import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.util.CookiePolicyException
import com.frpboy.seali.util.CookiePolicyGate
import com.frpboy.seali.util.CookiePolicyPlugin
import com.frpboy.seali.util.cookiePolicyExpectation
import com.yausername.youtubedl_android.EngineChangedException
import com.yausername.youtubedl_android.EngineGuard
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Qualification-only regression for the launch window: the engine or the plugin folder changes AFTER the cookie
 * gate approved it and BEFORE the launch. The library must refuse before it starts a child, and the gate must
 * then measure the changed state again. Each case restores what it changed. No production hook is used: the
 * test edits the installed files directly and calls the public launch API with the digests the gate returned.
 *
 * Covers: change detected at launch, refusal before any child, gate repair or fail-closed on re-measure.
 * Does NOT cover: cancellation at registration, the updater racing a launch, snapshot cleanup after exit, or the
 * one-re-gate loop in executeWithCookiePolicy (that loop is exercised only by unit tests).
 * Loopback proxy port 1 and an unused cookie path: a refused launch never opens a socket.
 */
class CookiePolicyPostGateSwapTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineRoot get() = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")
    private val engineFile get() = File(engineRoot, "yt-dlp")

    private fun waitForInit() {
        val status = File(context.filesDir, "seali-init-status")
        val expected = "OK pid=${android.os.Process.myPid()}"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline && runCatching { status.readText() }.getOrDefault("") != expected) Thread.sleep(100)
        assertEquals(expected, runCatching { status.readText() }.getOrDefault(""))
    }

    private fun cookieRequest() =
        YoutubeDLRequest("https://example.test/v")
            .addOption("--cookies", "/unused")
            .addOption("--proxy", "http://127.0.0.1:1")

    @Test fun pluginChangedAfterGateIsRefusedAtLaunchThenGateRestoresIt() {
        waitForInit()
        val approved = cookieRequest().cookiePolicyExpectation(context)!!
        val plugin = File(engineRoot, CookiePolicyPlugin.RELATIVE_FILE)
        assertTrue(plugin.isFile)
        val original = plugin.readBytes()
        try {
            EngineGuard.exclusive { plugin.setWritable(true); plugin.writeText("raise RuntimeError('swapped after gate')\n") }
            // Measuring again (what a re-gate starts from) must show the change.
            val now = YoutubeDL.getInstance().measureInstalledEngine()
            assertNotEquals(approved.pluginFiles, now.pluginFiles)
            // The launch bound to the old digests is refused. The test builds the request without the gate's
            // plugin repair by calling the library directly.
            try {
                YoutubeDL.getInstance().execute(cookieRequest(), "swap-plugin-1", null, approved, null)
                fail("a launch bound to stale digests must be refused")
            } catch (e: EngineChangedException) {
            }
        } finally {
            EngineGuard.exclusive { plugin.setWritable(true); plugin.writeBytes(original) }
        }
        // The gate repairs the plugin and approves the same digests again.
        val again = cookieRequest().cookiePolicyExpectation(context)!!
        assertEquals(approved.engineSha256, again.engineSha256)
        assertEquals(approved.pluginFiles, again.pluginFiles)
        assertEquals(CookiePolicyPlugin.SHA256, CookiePolicyPlugin.sha256(plugin))
    }

    @Test fun extraPluginFileAfterGateIsRefusedAtLaunchAndFailsTheGateClosed() {
        waitForInit()
        val approved = cookieRequest().cookiePolicyExpectation(context)!!
        val extra = File(File(engineRoot, CookiePolicyPlugin.PLUGIN_ROOT), "extra-after-gate.txt")
        try {
            EngineGuard.exclusive { extra.writeText("x") }
            try {
                YoutubeDL.getInstance().execute(cookieRequest(), "swap-extra-1", null, approved, null)
                fail("a launch bound to stale digests must be refused")
            } catch (e: EngineChangedException) {
            }
            try {
                cookieRequest().cookiePolicyExpectation(context)
                fail("the gate must refuse a plugin folder that is not exactly the cookie rule")
            } catch (e: CookiePolicyException) {
            }
        } finally {
            EngineGuard.exclusive { extra.delete() }
        }
        val again = cookieRequest().cookiePolicyExpectation(context)!!
        assertEquals(approved.pluginFiles, again.pluginFiles)
    }

    @Test fun engineChangedAfterGateIsRefusedAtLaunchAndRestoredByteForByte() {
        waitForInit()
        val approved = cookieRequest().cookiePolicyExpectation(context)!!
        val engine = engineFile
        assertTrue(engine.isFile)
        val original = engine.readBytes()
        val wasWritable = engine.canWrite()
        try {
            EngineGuard.exclusive {
                engine.setWritable(true)
                engine.writeBytes(original + byteArrayOf(0))
            }
            assertNotEquals(approved.engineSha256, YoutubeDL.getInstance().measureInstalledEngine().engineSha256)
            try {
                YoutubeDL.getInstance().execute(cookieRequest(), "swap-engine-1", null, approved, null)
                fail("a launch bound to stale digests must be refused")
            } catch (e: EngineChangedException) {
            }
        } finally {
            EngineGuard.exclusive {
                engine.setWritable(true)
                engine.writeBytes(original)
                if (!wasWritable) engine.setWritable(false)
            }
        }
        val restored = YoutubeDL.getInstance().measureInstalledEngine()
        assertEquals(approved.engineSha256, restored.engineSha256)
        assertEquals(approved.pluginFiles, restored.pluginFiles)
    }
}
