package com.frpboy.seali

import com.frpboy.seali.util.CookiePolicyException
import com.frpboy.seali.util.CookiePolicyGateCore
import com.frpboy.seali.util.CookiePolicyPlugin
import com.frpboy.seali.util.CookieProbeSpec
import com.frpboy.seali.util.ProbeObservation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CookiePolicyGateTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fails(block: () -> Unit): CookiePolicyException {
        try { block() } catch (e: CookiePolicyException) { return e }
        fail("expected CookiePolicyException"); throw AssertionError()
    }
    private val cookieCommand = listOf("--cookies", "/x", "https://example.test/v")

    @Test fun passingProbeAllowsAndIsCachedForSameKey() {
        val gate = CookiePolicyGateCore { emptyList() }
        gate.require("e1", "p1", cookieCommand)
        gate.require("e1", "p1", cookieCommand)
        assertEquals(1, gate.probeRuns)
    }
    @Test fun engineChangeForcesReprobe() {
        val gate = CookiePolicyGateCore { emptyList() }
        gate.require("e1", "p1", cookieCommand)
        gate.require("e2", "p1", cookieCommand)
        assertEquals(2, gate.probeRuns)
    }
    @Test fun pluginChangeForcesReprobe() {
        val gate = CookiePolicyGateCore { emptyList() }
        gate.require("e1", "p1", cookieCommand)
        gate.require("e1", "p2", cookieCommand)
        assertEquals(2, gate.probeRuns)
    }
    @Test fun failingProbeBlocksAndIsNeverCached() {
        var leaky = true
        val gate = CookiePolicyGateCore { if (leaky) listOf("exact host: leak") else emptyList() }
        fails { gate.require("e1", "p1", cookieCommand) }
        fails { gate.require("e1", "p1", cookieCommand) }
        assertEquals(2, gate.probeRuns)
        leaky = false
        gate.require("e1", "p1", cookieCommand)
    }
    @Test fun laterFailureAfterPassDoesNotKeepOldPass() {
        var failing = false
        val gate = CookiePolicyGateCore { if (failing) listOf("broken") else emptyList() }
        gate.require("e1", "p1", cookieCommand)
        failing = true
        fails { gate.require("e2", "p1", cookieCommand) }
        fails { gate.require("e1", "p1", cookieCommand) }
    }
    @Test fun pluginDisablingFlagsAreRejectedWithoutProbing() {
        for (flag in listOf("--no-plugin-dirs", "--plugin-dirs", "--config-locations")) {
            val gate = CookiePolicyGateCore { emptyList() }
            fails { gate.require("e1", "p1", cookieCommand + flag) }
            assertEquals(0, gate.probeRuns)
        }
    }
    @Test fun equalsFormAndAbbreviatedOverridesAreRejected() {
        val bypasses = listOf(
            "--plugin-dirs=/tmp/evil", "--no-plugin-dirs=", "--config-locations=/tmp/c", "--config-location=/tmp/c",
            "--PLUGIN-DIRS=/x", "--plugin-dir=/x", "--no-plugin", "--plugin-d", "--config-loc=/x",
        )
        for (flag in bypasses) {
            val gate = CookiePolicyGateCore { emptyList() }
            fails { gate.require("e1", "p1", cookieCommand + flag) }
            assertEquals(flag, 0, gate.probeRuns)
        }
    }
    @Test fun ordinaryOptionsAndValuesAreNotBlocked() {
        val gate = CookiePolicyGateCore { emptyList() }
        gate.require("e1", "p1", listOf("--cookies", "/x", "--no-playlist", "--output", "--plugin-dirs-note.mp4", "-f", "best", "--", "https://example.test/--plugin-dirs=x"))
    }
    @Test fun errorMessageIsUserFacingAndNamesTheFallbackChoice() {
        val e = fails { CookiePolicyGateCore { listOf("no request reached the probe") }.require("e", "p", cookieCommand) }
        assertTrue(e.message!!.startsWith("Cookies were not used"))
    }

    private fun obs(host: String, vararg c: String) = ProbeObservation(host, c.toSet())

    @Test fun evaluatorAcceptsExpectedAndRejectsEachFailureMode() {
        val exact = CookieProbeSpec.CASES.first { it.name == "exact host" }
        assertTrue(CookieProbeSpec.evaluate(exact, exact.expected).isEmpty())
        assertTrue(CookieProbeSpec.evaluate(exact, emptyList()).isNotEmpty())
        val sub = CookieProbeSpec.CASES.first { it.name == "child subdomain" }
        // leaky: host-only cookie reached the subdomain
        assertTrue(CookieProbeSpec.evaluate(sub, listOf(obs("sub.example.test", CookieProbeSpec.HOSTONLY, CookieProbeSpec.DOMAIN))).isNotEmpty())
        // over-strict: domain cookie missing on the subdomain
        assertTrue(CookieProbeSpec.evaluate(sub, listOf(obs("sub.example.test"))).isNotEmpty())
        val other = CookieProbeSpec.CASES.first { it.name == "unrelated host" }
        assertTrue(CookieProbeSpec.evaluate(other, listOf(obs("other.test", CookieProbeSpec.DOMAIN))).isNotEmpty())
        val redirect = CookieProbeSpec.CASES.first { it.name == "redirect parent to child" }
        assertTrue(CookieProbeSpec.evaluate(redirect, listOf(obs("example.test", CookieProbeSpec.HOSTONLY, CookieProbeSpec.DOMAIN))).isNotEmpty())
        val back = CookieProbeSpec.CASES.first { it.name == "redirect child to parent" }
        assertTrue(CookieProbeSpec.evaluate(back, back.expected).isEmpty())
    }
    @Test fun probeCoversHostOnlyDomainPathSecureAndRedirects() {
        val names = CookieProbeSpec.CASES.map { it.name }
        assertTrue(names.containsAll(listOf("exact host", "child subdomain", "unrelated host", "matching path", "redirect parent to child", "redirect child to parent")))
        assertTrue(CookieProbeSpec.COOKIE_FILE.contains("\tTRUE\t/\tTRUE\t") .not())
        assertTrue(CookieProbeSpec.COOKIE_FILE.lines().any { it.endsWith("seccookie\tSYNTH_S") && it.split("\t")[3] == "TRUE" })
        // the secure cookie must never be seen over the plain-http probe
        assertTrue(CookieProbeSpec.CASES.none { c -> c.expected.any { CookieProbeSpec.SECURE in it.cookies } })
    }

    @Test fun installerWritesExactBytesAtomicallyAndIsIdempotent() {
        val root = tmp.newFolder("engine")
        val f = CookiePolicyPlugin.install(root)
        assertEquals(CookiePolicyPlugin.SHA256, CookiePolicyPlugin.sha256(f))
        assertTrue(f.canonicalPath.endsWith("yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor/strict_cookie_policy.py"))
        val again = CookiePolicyPlugin.install(root)
        assertEquals(f, again)
        assertEquals(listOf("strict_cookie_policy.py"), f.parentFile.list()!!.toList())
    }
    @Test fun installerRepairsTamperedPlugin() {
        val root = tmp.newFolder("engine")
        val f = CookiePolicyPlugin.install(root)
        f.writeText("raise SystemExit(0)\n")
        CookiePolicyPlugin.install(root)
        assertEquals(CookiePolicyPlugin.SHA256, CookiePolicyPlugin.sha256(f))
    }
    @Test fun installerRefusesForeignPluginAndSymlink() {
        val root = tmp.newFolder("engine")
        CookiePolicyPlugin.install(root)
        File(root, "yt-dlp-plugins/other").mkdirs()
        try { CookiePolicyPlugin.install(root); fail() } catch (e: IllegalStateException) {}
        val root2 = tmp.newFolder("engine2")
        val real = tmp.newFolder("elsewhere")
        java.nio.file.Files.createSymbolicLink(File(root2, "yt-dlp-plugins").toPath(), real.toPath())
        try { CookiePolicyPlugin.install(root2); fail() } catch (e: IllegalStateException) {}
    }
    @Test fun pluginSourceBindsSignatureAndOrsStrictBit() {
        val s = CookiePolicyPlugin.SOURCE
        assertTrue(s.contains("DomainStrictNonDomain"))
        assertTrue(s.contains("bind(self, *args, **kwargs)"))
        assertTrue(s.contains("| _STRICT"))
    }
}
