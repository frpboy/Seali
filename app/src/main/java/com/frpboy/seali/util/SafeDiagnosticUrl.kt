package com.frpboy.seali.util

import java.net.URI
import java.util.Locale

/** Shareable URL origin only. Media IDs may be private capabilities, not public by default. */
object SafeDiagnosticUrl {
    fun describe(raw: String?): String {
        if (raw.isNullOrBlank()) return "URL unavailable."
        if (raw.length > 8192 || raw.any { it.isISOControl() } || '\\' in raw)
            return "URL withheld: unsafe or unsupported URL."
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return "URL withheld: invalid URL."
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme !in setOf("https", "http") || host.isNullOrBlank() || uri.isOpaque || uri.port !in -1..65535 ||
            !host.matches(Regex("[a-z0-9.-]+")) || host.startsWith('.') || host.endsWith('.'))
            return "URL withheld: unsafe or unsupported URL."
        if (host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}""")))
            return "URL withheld: literal IP address."
        val origin = "$scheme://$host" + if (uri.port in 1..65535) ":${uri.port}" else ""
        return "$origin/ [path, query and fragment withheld; origin may itself be sensitive: inspect before sharing; use existing Copy link locally for original]"
    }
}
