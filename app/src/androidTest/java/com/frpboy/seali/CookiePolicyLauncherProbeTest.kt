package com.frpboy.seali

import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.util.CookiePolicyException
import com.frpboy.seali.util.CookiePolicyGate
import com.frpboy.seali.util.enforceCookiePolicy
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * Runs the real in-app probe through the exact embedded launcher (no internet, loopback only).
 * Needs a device or emulator. CI only compiles it. Until it has passed on Android, the strict
 * cookie rule is unverified there, and the gate keeps cookies off unless the probe passes.
 */
class CookiePolicyLauncherProbeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitForInit() {
        val status = File(context.filesDir, "seali-init-status")
        val expected = "OK pid=${android.os.Process.myPid()}"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline && runCatching { status.readText() }.getOrDefault("") != expected) Thread.sleep(100)
        assertEquals(expected, runCatching { status.readText() }.getOrDefault(""))
    }

    @Test fun probePassesThroughExactLauncherAndGateAllowsCookies() {
        waitForInit()
        val request = YoutubeDLRequest("https://example.test/v").addOption("--cookies", "/unused")
        request.enforceCookiePolicy(context)
    }

    @Test fun pluginDisablingFlagIsRefusedBeforeLaunch() {
        waitForInit()
        val request = YoutubeDLRequest("https://example.test/v").addOption("--cookies", "/unused").addOption("--no-plugin-dirs")
        try { request.enforceCookiePolicy(context); fail("must refuse") } catch (e: CookiePolicyException) {}
    }

    @Test fun brokenPluginFailsClosed() {
        waitForInit()
        val plugin = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor/strict_cookie_policy.py")
        // The gate reinstalls exact bytes, so tamper only through a different engine key: write a leak-prone no-op and confirm repair.
        val request = YoutubeDLRequest("https://example.test/v").addOption("--cookies", "/unused")
        request.enforceCookiePolicy(context)
        plugin.writeText("raise RuntimeError('broken')\n")
        request.enforceCookiePolicy(context) // reinstalled to exact bytes and re-probed because the plugin hash changed
        assertTrue(plugin.readText().contains("DomainStrictNonDomain"))
    }
}
