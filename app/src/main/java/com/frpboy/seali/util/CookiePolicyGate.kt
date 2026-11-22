package com.frpboy.seali.util

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import java.util.concurrent.atomic.AtomicBoolean
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
     * Shared with [FunctionalEngine.install] so an engine install and a cookie gate/probe never overlap.
     *
     * Known limits, recorded from source (not a guarantee):
     * - The lock is released when [require] returns, before the launcher starts. A write to the engine in that
     *   window would change the bytes the launch uses compared with the bytes just probed.
     * - [FunctionalEngine.install] writes only the bundled pinned bytes. The runtime updater
     *   (UpdateUtil.updateYtDlp -> YoutubeDL.updateYoutubeDL) writes newly downloaded bytes and does not take this
     *   lock. Play builds never run it (RuntimeUpdatePolicy.mayDownload); generic and F-Droid builds can, with
     *   consent. The next gate call sees the new engine sha and probes again.
     */
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
                    val abandoned = AtomicBoolean(false)
                    runWithDeadline(
                        PROBE_CASE_DEADLINE_MS,
                        onTimeout = {
                            abandoned.set(true)
                            cancelUntilGone(processId)
                        },
                    ) {
                        // The library registers the id only after some file setup, so a cancel that arrives
                        // earlier finds nothing. cancelUntilGone keeps retrying, and this check stops a
                        // helper that has not reached execute yet.
                        if (!abandoned.get()) YoutubeDL.getInstance().execute(request, processId)
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

/** Refuses a request that uses cookies unless the cookie safety rule is proven active. */
internal fun YoutubeDLRequest.enforceCookiePolicy(context: Context): YoutubeDLRequest {
    if (!addCookieRequestOptions()) return this
    CookiePolicyGate.require(context, buildCommand())
    return this
}

internal const val IGNORE_CONFIG = "--ignore-config"
internal const val PROBE_PROCESS_ID = "cookie-policy-probe"
private val probeSequence = AtomicInteger(0)

/** Longest one probe case may run. A healthy case takes tens of seconds on a slow phone. */
internal const val PROBE_CASE_DEADLINE_MS = 120_000L

/** Cancels [id] now and keeps retrying for a while, because the library only knows an id once execute registers it. */
internal fun cancelUntilGone(id: String, attempts: Int = 20, pauseMs: Long = 500) {
    Thread {
        repeat(attempts) {
            if (YoutubeDL.getInstance().destroyProcessById(id)) return@Thread
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) { return@Thread }
        }
    }.apply { isDaemon = true; start() }
}

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
