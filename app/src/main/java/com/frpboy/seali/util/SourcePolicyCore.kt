package com.frpboy.seali.util

import java.net.URI

/**
 * Pure, flavor-independent matching for the Play edition's source boundary. The Play flavor
 * supplies the list in its own SourcePolicy; every other flavor ships an unrestricted policy.
 */
internal object SourcePolicyCore {
    /** True when [url]'s host equals one of [domains] or is a subdomain of one. */
    fun isBlockedHost(url: String, domains: Collection<String>): Boolean {
        val host = hostOf(url) ?: return false
        return domains.any { d -> host == d || host.endsWith(".$d") }
    }

    /** Lower-case host of an http(s) URL, without port or trailing dot; null if there is none. */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        return host.lowercase().trimEnd('.').ifEmpty { null }
    }
}
