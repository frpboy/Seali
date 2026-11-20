package com.frpboy.seali

import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.util.CookiePolicyException
import com.frpboy.seali.util.CookiePolicyGate
import com.frpboy.seali.util.CookiePolicyPlugin
import com.frpboy.seali.util.enforceCookiePolicy
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Real in-app probe through the exact embedded launcher. Loopback proxy and synthetic cookies only:
 * no internet, no login, no real cookies. Needs an ARM64 device. CI only compiles it.
 * Per-case PASS/FAIL lines are in logcat under the tag CookiePolicyProbe.
 * The negative tests prove the probe itself detects a leak on the device, so a pass is not vacuous.
 */
class CookiePolicyLauncherProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engineRoot get() = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")

    private fun waitForInit() {
        val status = File(context.filesDir, "seali-init-status")
        val expected = "OK pid=${android.os.Process.myPid()}"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline && runCatching { status.readText() }.getOrDefault("") != expected) Thread.sleep(100)
        assertEquals(expected, runCatching { status.readText() }.getOrDefault(""))
    }

    private fun cookieRequest() = YoutubeDLRequest("https://example.test/v").addOption("--cookies", "/unused")

    @Test fun probePassesThroughExactLauncherAndGateAllowsCookies() {
        waitForInit()
        synchronized(CookiePolicyGate.engineLock) { CookiePolicyPlugin.install(engineRoot) }
        val failures = CookiePolicyGate.runProbe(context)
        assertEquals("probe failures: $failures", emptyList<String>(), failures)
        cookieRequest().enforceCookiePolicy(context)
    }

    @Test fun probeDetectsLeakWhenPluginIsMissing() {
        waitForInit()
        synchronized(CookiePolicyGate.engineLock) {
            val file = CookiePolicyPlugin.install(engineRoot)
            try {
                assertTrue(file.delete())
                val failures = CookiePolicyGate.runProbe(context)
                assertTrue("a missing plugin must be detected, got: $failures", failures.isNotEmpty())
            } finally {
                CookiePolicyPlugin.install(engineRoot)
            }
        }
    }

    @Test fun probeDetectsLeakWhenPluginIsBroken() {
        waitForInit()
        synchronized(CookiePolicyGate.engineLock) {
            val file = CookiePolicyPlugin.install(engineRoot)
            try {
                file.writeText("raise RuntimeError('broken')\n")
                val failures = CookiePolicyGate.runProbe(context)
                assertTrue("a broken plugin must be detected, got: $failures", failures.isNotEmpty())
            } finally {
                CookiePolicyPlugin.install(engineRoot)
            }
        }
    }

    @Test fun gateRepairsTamperedPluginThenPasses() {
        waitForInit()
        val file = synchronized(CookiePolicyGate.engineLock) { CookiePolicyPlugin.install(engineRoot) }
        file.writeText("raise RuntimeError('broken')\n")
        cookieRequest().enforceCookiePolicy(context)
        assertEquals(CookiePolicyPlugin.SHA256, CookiePolicyPlugin.sha256(file))
    }

    @Test fun pluginDisablingFlagsAreRefusedBeforeLaunch() {
        waitForInit()
        for (flag in listOf("--no-plugin-dirs", "--plugin-dirs=/x", "--plugin-dir=/x")) {
            val request = cookieRequest().addCommands(listOf(flag))
            try { request.enforceCookiePolicy(context); fail("must refuse $flag") } catch (e: CookiePolicyException) {}
        }
    }
}
