package com.frpboy.seali.util

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Optional support report. It is only built when the user opens the preview, and only copied when
 * the user taps Copy there. Nothing is sent anywhere. The default copied report stays the
 * allowlist-only [SafeErrorReport]; this one adds sanitized exception text and frames.
 *
 * Sanitizing is best effort, so the UI shows the exact text first and tells the user to review it.
 * It never reads cookies, request headers, process logs or the full URL: only the throwable chain.
 */
object DetailedErrorReport {
    private const val MAX_CAUSES = 6
    private const val MAX_FRAMES = 12
    private const val MAX_MESSAGE = 400

    // Class names are only printed for known code. App-defined or third-party names might carry data.
    private val SAFE_CLASS_PREFIXES = listOf(
        "java.", "javax.", "android.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "org.json.",
        "com.yausername.", "com.frpboy.seali.",
    )

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://(?:\[[^\]\s]*\])?[^\s'"<>)\]]*""")
    // A quoted absolute path may contain spaces; consume it whole before the whitespace-bounded PATH rule.
    private val QUOTED_PATH = Regex("""(?<=['"])/(?:[^'"\n/]+/)+[^'"\n]*(?=['"])""")
    private val HEADER_LINE = Regex(
        """(?im)\b(authorization|proxy-authorization|cookie|set-cookie|x-[a-z0-9-]*(?:token|key|auth)[a-z0-9-]*)\s*[:=][^\n]*"""
    )
    private val SECRET_PAIR = Regex(
        """(?i)\b([a-z0-9_.-]*(?:token|secret|passw(?:or)?d|apikey|api_key|key|sig|signature|session|auth|cookie|sid|code|credential)[a-z0-9_.-]*)\s*[=:]\s*[^\s,;&]+"""
    )
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    // Full, compressed ("::"), bracketed, zone-id and IPv4-mapped forms. Run before IPV4.
    private val IPV6 = Regex(
        """(?<![A-Za-z0-9:])\[?(?:(?:[0-9A-Fa-f]{1,4}:){2,7}(?:[0-9A-Fa-f]{1,4}|:)|(?:[0-9A-Fa-f]{1,4}:){1,7}:|::(?:[0-9A-Fa-f]{1,4}:){0,6}[0-9A-Fa-f]{1,4}|[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?:\.\d{1,3}){0,3}(?:%[A-Za-z0-9_.\-]+)?\]?(?::\d{1,5})?(?![A-Za-z0-9:])"""
    )
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")
    private val PATH = Regex("""(?<![A-Za-z0-9])(?:/[^\s/:'"<>]+){2,}/?""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-+/=]{24,}""")

    fun build(versionReport: String, throwable: Throwable, url: String? = null): String {
        val lines = mutableListOf<String>()
        lines += versionReport.trimEnd()
        lines += "Report type: detailed diagnostic (previewed by the user before copying)"
        lines += "URL: " + SafeDiagnosticUrl.describe(url)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        var index = 0
        while (current != null && index < MAX_CAUSES && seen.add(current)) {
            lines += ""
            lines += (if (index == 0) "Error" else "Caused by") + ": " + className(current)
            lines += "  Category: " + category(current)
            lines += "  Message: " + sanitizeMessage(current.message)
            if (index < 2) {
                val frames = current.stackTrace.asSequence()
                    .filter { frame -> SAFE_CLASS_PREFIXES.any { frame.className.startsWith(it) } }
                    .take(MAX_FRAMES)
                    .map { "    at ${it.className}.${it.methodName}(${it.fileName ?: "Unknown"}:${it.lineNumber})" }
                    .toList()
                lines += frames
            }
            current = current.cause
            index++
        }
        if (current != null) lines += "Additional causes omitted."
        lines += ""
        lines += "Not included: cookies, request headers, account data, full URLs, file paths, raw yt-dlp output."
        return lines.joinToString("\n")
    }

    fun className(throwable: Throwable): String {
        val name = throwable.javaClass.name
        return if (SAFE_CLASS_PREFIXES.any { name.startsWith(it) } &&
            name.matches(Regex("[A-Za-z][A-Za-z0-9_.$]{0,159}"))) name else "Throwable (app or library class name withheld)"
    }

    /** Broad, substring-based and best effort; wording and wrapping differ between yt-dlp versions. */
    fun category(throwable: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = throwable
        val text = StringBuilder()
        var count = 0
        while (current != null && count++ < MAX_CAUSES && seen.add(current)) {
            text.append(current.javaClass.name).append(' ').append(current.message.orEmpty()).append(' ')
            current = current.cause
        }
        val t = text.toString().lowercase(Locale.ROOT)
        return when {
            "certificate" in t || "ssl" in t || "tls" in t || "handshake" in t -> "TLS or certificate"
            "unable to resolve host" in t || "unknownhost" in t || "name or service not known" in t ||
                "temporary failure in name resolution" in t -> "Host name lookup"
            "timed out" in t || "timeout" in t -> "Timeout"
            "connection reset" in t || "connection refused" in t || "network is unreachable" in t ||
                "broken pipe" in t || "econn" in t -> "Network connection"
            "http error 4" in t || "http error 5" in t -> "HTTP status from server"
            "sign in to confirm" in t || "not a bot" in t || "login required" in t -> "Server asked for sign-in"
            "javascript runtime" in t || "quickjs" in t || "qjs" in t -> "JavaScript runtime"
            "no space left" in t || "enospc" in t -> "Storage full"
            "permission denied" in t || "eacces" in t || "securityexception" in t || "read-only file system" in t -> "File permission"
            "no such file" in t || "enoent" in t || "not found" in t && "file" in t -> "File or path missing"
            "ffmpeg" in t || "ffprobe" in t || "postprocess" in t -> "Media post-processing"
            "unsupported url" in t || "unable to extract" in t || "extractor" in t -> "Extraction"
            "python" in t || "traceback" in t || "modulenotfound" in t || "importerror" in t -> "Python engine"
            "cancel" in t -> "Cancelled"
            "outofmemory" in t -> "Out of memory"
            else -> "Uncategorized"
        }
    }

    fun sanitizeMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return "No error message was provided."
        var s = raw.filter { !it.isISOControl() || it == '\n' || it == '\t' }
        s = HEADER_LINE.replace(s) { "${it.groupValues[1]}: <redacted>" }
        s = QUOTED_PATH.replace(s, "<path>")
        s = BEARER.replace(s) { "${it.groupValues[1]} <redacted>" }
        s = URL.replace(s) { m -> hostOnly(m.value) }
        s = SECRET_PAIR.replace(s) { "${it.groupValues[1]}=<redacted>" }
        s = EMAIL.replace(s, "<email>")
        s = IPV6.replace(s, "<ip>")
        s = IPV4.replace(s, "<ip>")
        s = PATH.replace(s, "<path>")
        s = LONG_TOKEN.replace(s, "<token>")
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s.length > MAX_MESSAGE) s = s.take(MAX_MESSAGE) + " ...[truncated]"
        return s
    }

    private fun hostOnly(url: String): String {
        val scheme = url.substringBefore("://").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return "<$scheme link>"
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        return if (host.matches(Regex("[a-z0-9.-]{1,253}"))) "$scheme://$host/<path-withheld>" else "<url>"
    }
}
