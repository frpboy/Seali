package com.frpboy.seali.util

import android.content.Context
import com.yausername.youtubedl_android.EngineChangedException
import com.yausername.youtubedl_android.EngineExpectation
import com.yausername.youtubedl_android.LaunchCancellation
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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
        val blocked = command.filterNotNull().filter { isBlockedToken(it) }
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
        val BLOCKED_FLAGS = listOf("--no-plugin-dirs", "--plugin-dirs", "--config-locations", "--config-location")

        /**
         * yt-dlp's option parser accepts `--flag=value` and unique abbreviations of long options, so an
         * exact-match list is bypassable. A token is blocked when its name part (before any `=`) is a
         * prefix of a blocked flag. Only option-shaped tokens (`--` plus at least one character) are checked.
         */
        fun isBlockedToken(token: String): Boolean {
            if (!token.startsWith("--")) return false
            val name = token.substringBefore('=').lowercase()
            if (name.length < 3) return false
            return BLOCKED_FLAGS.any { it.startsWith(name) }
        }
    }
}

internal object CookiePolicyGate {
    /**
     * Held while the gate reads the engine/plugin digests and while an engine or plugin install runs, so a
     * probe never overlaps those writers. It is released before the launch. What a launch actually runs is
     * bound separately: the library copies the engine and plugin files into the launch's private folder under
     * its EngineGuard (the guard every engine writer takes, including the runtime updater and first install),
     * and refuses to start unless that copy has exactly the digests the gate approved. A later swap of the
     * installed files cannot reach a running child. Lock order: this lock, then the library's EngineGuard.
     */
    val engineLock = Any()

    private var core: CookiePolicyGateCore? = null

    /** The digests the running probe must be launched with; set under [engineLock] before the gate runs it. */
    @Volatile private var probeExpectation: EngineExpectation? = null

    /** Throws unless cookies are safe; returns the digests the launch must run with. */
    fun require(context: Context, command: List<String?>): EngineExpectation {
        return synchronized(engineLock) {
            val root = File(context.noBackupFilesDir.canonicalFile, "youtubedl-android/yt-dlp")
            val engine = File(root, "yt-dlp")
            if (!engine.isFile) throw CookiePolicyException("Cookies were not used: the download engine is missing.")
            try {
                CookiePolicyPlugin.install(root)
            } catch (e: IllegalStateException) {
                    throw CookiePolicyException("Cookies were not used: the cookie safety rule could not be installed (${e.message}).")
                }
            val gate = core ?: CookiePolicyGateCore { runProbe(context) }.also { core = it }
            // Measured by the library with the same rules a launch snapshot uses; the app keeps no copy of them.
            val measured =
                try {
                    YoutubeDL.getInstance().measureInstalledEngine()
                } catch (e: Exception) {
                    throw CookiePolicyException("Cookies were not used: the download engine could not be checked (${e.javaClass.simpleName}).")
                }
            if (measured.pluginFiles != mapOf(CookiePolicyPlugin.SNAPSHOT_KEY to CookiePolicyPlugin.SHA256)) {
                throw CookiePolicyException("Cookies were not used: the engine plugin folder is not exactly the cookie safety rule.")
            }
            val engineSha = measured.engineSha256
            val pluginSha = CookiePolicyPlugin.SHA256
            val expectation = measured
            probeExpectation = expectation
            gate.require(engineSha, pluginSha, command)
            expectation
        }
    }

    /** Runs every probe case through the exact launcher. Returns failure reasons, empty on pass. */
    internal fun runProbe(context: Context): List<String> {
        val cookieFile = File(context.cacheDir, "cookie-policy-probe.txt")
        val server = ProbeProxy()
        return try {
            cookieFile.writeText(CookieProbeSpec.COOKIE_FILE)
            val failures = mutableListOf<String>()
            for (case in CookieProbeSpec.CASES) {
                server.reset()
                // One id per case, so a late cancel can only ever reach the case it was made for.
                val processId = "$PROBE_PROCESS_ID-${probeSequence.incrementAndGet()}"
                try {
                    val request =
                        YoutubeDLRequest(case.url)
                            .addOption("--proxy", "http://127.0.0.1:${server.port}")
                            .addOption("--cookies", cookieFile.absolutePath)
                            .addOption("--simulate")
                            .addOption("--socket-timeout", "10")
                            .addOption("-R", "0")
                            .addOption(IGNORE_CONFIG)
                    // A stalled child never opens a socket, so the socket timeout cannot stop it. The case runs on
                    // a helper thread: if it has not finished in time the invocation is cancelled by id and this
                    // call returns anyway, so the gate denies cookies and releases the engine lock even when
                    // the cancel does not unblock the launcher.
                    // The token remembers a cancel that arrives before execute has registered or started,
                    // so no retry loop is needed and a late launch cannot start.
                    val token = LaunchCancellation()
                    runWithDeadline(
                        PROBE_CASE_DEADLINE_MS,
                        onTimeout = {
                            token.cancel()
                            YoutubeDL.getInstance().destroyProcessById(processId)
                        },
                    ) {
                        // Launched with the digests the gate approved, so a swap since then fails closed.
                        YoutubeDL.getInstance().execute(request, processId, null, probeExpectation, token)
                    }
                } catch (e: TimeoutException) {
                    failures += "${case.name}: launcher did not finish in time"
                    break
                } catch (e: Exception) {
                    failures += "${case.name}: launcher failed (${e.javaClass.simpleName})"
                    continue
                }
                if (server.workerFailures() > 0) {
                    failures += "${case.name}: probe connection failed"
                    continue
                }
                val observed = server.observations()
                val caseFailures = CookieProbeSpec.evaluate(case, observed)
                android.util.Log.i(
                    "CookiePolicyProbe",
                    "case=${case.name} result=${if (caseFailures.isEmpty()) "PASS" else "FAIL"} " +
                        "observed=${observed.map { it.host + "[" + it.cookies.sorted().joinToString(",") + "]" }}",
                )
                failures += caseFailures
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
    private val failures = AtomicInteger(0)

    /** Read timeout for one connection. Tests shorten it. */
    @Volatile internal var socketTimeoutMs: Int = 10000
    val port: Int = server.localPort

    private val acceptor =
        Thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    Thread {
                            // An exception escaping a raw thread ends the app process. Count it so the gate denies cookies.
                            try { handle(socket) } catch (e: Exception) { failures.incrementAndGet() }
                        }
                        .apply { isDaemon = true }
                        .start()
                }
            }
            .apply {
                isDaemon = true
                start()
            }

    fun reset() {
        seen.clear()
        failures.set(0)
    }

    /** Connections whose handler threw since the last [reset]. */
    fun workerFailures(): Int = failures.get()

    fun observations(): List<ProbeObservation> = synchronized(seen) { seen.toList() }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = socketTimeoutMs
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

/**
 * Refuses a request that uses cookies unless the cookie safety rule is proven active. Returns the digests the
 * launch must run with, or null when the request does not use cookies.
 */
internal fun YoutubeDLRequest.cookiePolicyExpectation(context: Context): EngineExpectation? {
    if (!addCookieRequestOptions()) return null
    return CookiePolicyGate.require(context, buildCommand())
}

/**
 * Runs [request] with the cookie gate. The launch is bound to the digests the gate approved; if the engine
 * or plugin changed in between, the library refuses before starting and the gate runs once more. After a
 * second change the request fails closed.
 */
internal fun YoutubeDL.executeWithCookiePolicy(
    context: Context,
    request: YoutubeDLRequest,
    processId: String? = null,
    callback: ((Float, Long, String) -> Unit)? = null,
): YoutubeDLResponse {
    repeat(2) {
        val expectation = request.cookiePolicyExpectation(context)
        try {
            return execute(request, processId, callback, expectation, null)
        } catch (e: EngineChangedException) {
            if (expectation == null) throw e
        }
    }
    throw CookiePolicyException("Cookies were not used: the download engine changed while the download was starting. Try again.")
}

internal const val IGNORE_CONFIG = "--ignore-config"
internal const val PROBE_PROCESS_ID = "cookie-policy-probe"
private val probeSequence = AtomicInteger(0)

/** Longest one probe case may run. A healthy case takes tens of seconds on a slow phone. */
internal const val PROBE_CASE_DEADLINE_MS = 120_000L

/**
 * Runs [block] on a helper thread and waits at most [timeoutMs]. On timeout it calls [onTimeout], interrupts the
 * helper and throws [TimeoutException] without waiting for the helper, so the caller is released even if
 * [block] ignores both. Other exceptions from [block] are rethrown as they were.
 */
internal fun <T> runWithDeadline(timeoutMs: Long, onTimeout: () -> Unit, block: () -> T): T {
    val task = FutureTask(Callable { block() })
    Thread(task).apply {
        isDaemon = true
        start()
    }
    try {
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } catch (e: TimeoutException) {
        try { onTimeout() } finally { task.cancel(true) }
        throw e
    }
}

/**
 * For a request that sends cookies, adds [IGNORE_CONFIG] so yt-dlp does not read a yt-dlp.conf from the -P
 * directory (a config line could switch the plugin off after the gate). Returns false and changes nothing
 * for a request without cookies.
 */
internal fun YoutubeDLRequest.addCookieRequestOptions(): Boolean {
    if (!buildCommand().contains("--cookies")) return false
    if (!hasOption(IGNORE_CONFIG)) addOption(IGNORE_CONFIG)
    return true
}
