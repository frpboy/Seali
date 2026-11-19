package com.frpboy.seali.util

import com.frpboy.seali.ui.page.settings.network.Cookie

/**
 * Maps one row of the WebView (Chromium) cookie store to a [Cookie] for the Netscape cookie file.
 *
 * Chromium stores a host-only cookie with a host_key that has no leading dot, and a domain
 * cookie with a leading dot. The Netscape file keeps that difference in its include-subdomains
 * flag: FALSE for host-only, TRUE for domain cookies. Domain, path and secure are kept as stored.
 * The expiry value is passed through unchanged, as before.
 *
 * Fail closed: a row that cannot be written as one well-formed line is skipped (null) instead of
 * being widened or written in a shape that shifts the tab-separated fields. That covers a blank
 * host, name or path, an empty value, and any tab, newline or carriage return in a field.
 */
internal fun chromiumRowToCookie(
    hostKey: String?,
    expiry: Long,
    path: String?,
    name: String?,
    value: String?,
    secure: Boolean,
): Cookie? {
    if (hostKey.isNullOrBlank() || name.isNullOrBlank() || path.isNullOrBlank()) return null
    if (value.isNullOrEmpty()) return null
    if (hostKey.startsWith("#")) return null
    if (listOf(hostKey, name, path, value).any { field -> field.any { it == '\t' || it == '\n' || it == '\r' } }) {
        return null
    }
    return Cookie(
        domain = hostKey,
        name = name,
        value = value,
        includeSubdomains = hostKey.startsWith("."),
        path = path,
        secure = secure,
        expiry = expiry,
    )
}
