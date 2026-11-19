package com.frpboy.seali.util

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Cookie use was refused because the cookie safety check did not pass. Fail closed, never silent. */
internal class CookiePolicyException(message: String) : Exception(message)

/** One request the probe server saw: the Host it was addressed to and the cookies it carried. */
internal data class ProbeObservation(val host: String, val cookies: Set<String>)

/** A probe step: fetch [url] through the exact launcher and require exactly [expected]. */
internal data class ProbeCase(val name: String, val url: String, val expected: Set<ProbeObservation>)

/**
 * Probe definition and its pure evaluation. The synthetic cookie file has a host-only cookie for
 * example.test, a domain cookie for .example.test, a path cookie and a secure cookie. Requests go
 * through a loopback proxy, so no DNS and no hosts change are needed.
 */
internal object CookieProbeSpec {
    const val VERSION = "probe-v1"
    const val HOSTONLY = "hostonly=SYNTH_H"
    const val DOMAIN = "domaincookie=SYNTH_D"
    const val PATH = "pathcookie=SYNTH_P"
    const val SECURE = "seccookie=SYNTH_S"

    val COOKIE_FILE: String =
        "# Netscape HTTP Cookie File\n" +
            listOf(
                    listOf("example.test", "FALSE", "/", "FALSE", "0", "hostonly", "SYNTH_H"),
                    listOf(".example.test", "TRUE", "/", "FALSE", "0", "domaincookie", "SYNTH_D"),
                    listOf("example.test", "FALSE", "/private", "FALSE", "0", "pathcookie", "SYNTH_P"),
                    listOf("example.test", "FALSE", "/", "TRUE", "0", "seccookie", "SYNTH_S"),
                )
                .joinToString("\n") { it.joinToString("\t") } +
            "\n"

    private fun obs(host: String, vararg cookies: String) = ProbeObservation(host, cookies.toSet())

    val CASES: List<ProbeCase> =
        listOf(
            ProbeCase("exact host", "http://example.test/ok.mp4", setOf(obs("example.test", HOSTONLY, DOMAIN))),
            ProbeCase("child subdomain", "http://sub.example.test/ok.mp4", setOf(obs("sub.example.test", DOMAIN))),
            ProbeCase("unrelated host", "http://other.test/ok.mp4", setOf(obs("other.test"))),
            ProbeCase("matching path", "http://example.test/private/x.mp4", setOf(obs("example.test", HOSTONLY, DOMAIN, PATH))),
            ProbeCase(
                "redirect parent to child",
                "http://example.test/redir?to=sub.example.test",
                setOf(obs("example.test", HOSTONLY, DOMAIN), obs("sub.example.test", DOMAIN)),
            ),
            ProbeCase(
                "redirect child to parent",
                "http://sub.example.test/redir?to=example.test",
                setOf(obs("sub.example.test", DOMAIN), obs("example.test", HOSTONLY, DOMAIN)),
            ),
        )

    /** Empty list means the observations match the case exactly. Otherwise one reason per mismatch. */
    fun evaluate(case: ProbeCase, observed: Collection<ProbeObservation>): List<String> {
        val seen = observed.toSet()
        if (seen.isEmpty()) return listOf("${case.name}: no request reached the probe")
        val failures = mutableListOf<String>()
        for (want in case.expected) {
            if (want !in seen) failures += "${case.name}: expected ${want.host} with ${want.cookies.sorted()}"
        }
        for (got in seen) {
            if (got !in case.expected) failures += "${case.name}: unexpected ${got.host} with ${got.cookies.sorted()}"
        }
        return failures
    }
}

/**
 * Decides whether a request that uses cookies may run. The decision rests on a probe through the
 * exact launcher, never on file presence or a hash. A pass is remembered only for the exact key
 * (engine bytes, plugin bytes, probe version); any change re-probes, and a failure is never cached.
 */
internal class CookiePolicyGateCore(private val probe: () -> List<String>) {
    private var verifiedKey: String? = null
    var probeRuns = 0
        private set

    /** Throws [CookiePolicyException] unless cookies are safe to use for this request. */
    fun require(engineSha: String, pluginSha: String, command: List<String?>) {
        val blocked = command.filterNotNull().filter { it in BLOCKED_FLAGS }
        if (blocked.isNotEmpty()) {
            throw CookiePolicyException(
                "Cookies were not used: this download carries options (${blocked.distinct().joinToString()}) " +
                    "that can switch off the cookie safety rule. Remove them or turn off \"Use cookies\"."
            )
        }
        val key = "engine=$engineSha|plugin=$pluginSha|${CookieProbeSpec.VERSION}"
        if (verifiedKey == key) return
        verifiedKey = null
        probeRuns++
        val failures = probe()
        if (failures.isNotEmpty()) {
            throw CookiePolicyException(
                "Cookies were not used: the cookie safety check failed (${failures.first()}). " +
                    "Turn off \"Use cookies\" to download without cookies."
            )
        }
        verifiedKey = key
    }

    companion object {
        val BLOCKED_FLAGS = setOf("--no-plugin-dirs", "--plugin-dirs", "--config-locations", "--config-location")
    }
}

internal object CookiePolicyGate {
    /** Shared with [FunctionalEngine.install] so an engine install and a cookie launch never overlap. */
    val engineLock = Any()

    private var core: CookiePolicyGateCore? = null

    fun require(context: Context, command: List<String?>) {
        synchronized(engineLock) {
            val root = File(context.noBackupFilesDir.canonicalFile, "youtubedl-android/yt-dlp")
            val engine = File(root, "yt-dlp")
            if (!engine.isFile) throw CookiePolicyException("Cookies were not used: the download engine is missing.")
            val pluginFile =
                try {
                    CookiePolicyPlugin.install(root)
                } catch (e: IllegalStateException) {
                    throw CookiePolicyException("Cookies were not used: the cookie safety rule could not be installed (${e.message}).")
                }
            val gate = core ?: CookiePolicyGateCore { runProbe(context) }.also { core = it }
            gate.require(CookiePolicyPlugin.sha256(engine), CookiePolicyPlugin.sha256(pluginFile), command)
        }
    }

    /** Runs every probe case through the exact launcher. Returns failure reasons, empty on pass. */
    private fun runProbe(context: Context): List<String> {
        val cookieFile = File(context.cacheDir, "cookie-policy-probe.txt")
        val server = ProbeProxy()
        return try {
            cookieFile.writeText(CookieProbeSpec.COOKIE_FILE)
            val failures = mutableListOf<String>()
            for (case in CookieProbeSpec.CASES) {
                server.reset()
                try {
                    val request =
                        YoutubeDLRequest(case.url)
                            .addOption("--proxy", "http://127.0.0.1:${server.port}")
                            .addOption("--cookies", cookieFile.absolutePath)
                            .addOption("--simulate")
                            .addOption("--socket-timeout", "10")
                            .addOption("-R", "0")
                    YoutubeDL.getInstance().execute(request)
                } catch (e: Exception) {
                    failures += "${case.name}: launcher failed (${e.javaClass.simpleName})"
                    continue
                }
                failures += CookieProbeSpec.evaluate(case, server.observations())
            }
            failures
        } catch (e: Exception) {
            listOf("probe could not run (${e.javaClass.simpleName})")
        } finally {
            server.close()
            cookieFile.delete()
        }
    }
}

/** Minimal loopback HTTP proxy for the probe: records Host and Cookie, answers with a tiny video file or a redirect. */
internal class ProbeProxy : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val seen = Collections.synchronizedList(mutableListOf<ProbeObservation>())
    val port: Int = server.localPort

    private val acceptor =
        Thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    Thread { handle(socket) }.apply { isDaemon = true }.start()
                }
            }
            .apply {
                isDaemon = true
                start()
            }

    fun reset() = seen.clear()

    fun observations(): List<ProbeObservation> = synchronized(seen) { seen.toList() }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10000
            val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            var host = ""
            var cookie = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "host") host = value.substringBefore(':')
                if (name == "cookie") cookie = value
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val cookies = if (cookie.isEmpty()) emptySet() else cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            seen.add(ProbeObservation(host, cookies))
            val out = s.getOutputStream()
            val redirect = Regex("/redir\\?to=([A-Za-z0-9.-]+)").find(target)?.groupValues?.get(1)
            if (redirect != null) {
                out.write("HTTP/1.1 302 Found\r\nLocation: http://$redirect/landing.mp4\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 4\r\nConnection: close\r\n\r\n".toByteArray())
                if (method == "GET") out.write("abcd".toByteArray())
            }
            out.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}

/** Refuses a request that uses cookies unless the cookie safety rule is proven active. */
internal fun YoutubeDLRequest.enforceCookiePolicy(context: Context): YoutubeDLRequest {
    val command = buildCommand()
    if (!command.contains("--cookies")) return this
    CookiePolicyGate.require(context, command)
    return this
}
