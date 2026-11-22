package com.frpboy.seali.util

/** GitHub, preview and F-Droid editions: no source boundary, no rights prompt. */
internal object SourcePolicy {
    const val REQUIRES_RIGHTS_CONFIRMATION = false

    /** Value for yt-dlp's --use-extractors, or null to leave the engine unchanged. */
    val extractorFilter: String? = null

    /** Null when [url] may be downloaded, else a user-facing reason. */
    fun blockedReason(url: String): String? = null
}
