package com.frpboy.seali.util

/**
 * Google Play edition boundary. Scope (block YouTube, Instagram and TikTok; require a per-download
 * rights confirmation; Play edition only) was put to the owner on 20 Nov 2026 21:59 IST and he
 * answered "Ok" (WhatsApp wamid ...AEhggQUMwQ0VCNzY0MjZFM0ZGMEQ4MEQ3NTY2QUVCN0FCM0IA). That covers
 * scope only; user-visible wording here is a draft awaiting his approval. Two layers: the link host
 * check below, and yt-dlp's own extractor exclusion, which also applies after yt-dlp follows a
 * redirect or shortener. Neither is a guarantee of policy compliance: embeds on other pages and
 * unlisted mirrors are not covered.
 */
internal object SourcePolicy {
    const val REQUIRES_RIGHTS_CONFIRMATION = true

    val blockedDomains: List<String> =
        listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "instagram.com",
            "tiktok.com",
        )

    /** yt-dlp matches these regexes against lower-case extractor names such as "youtube:tab". */
    val extractorFilter: String? = "default,-youtube.*,-instagram.*,-tiktok.*"

    const val NOT_SUPPORTED_MESSAGE = "This service is not supported in the Play edition."

    fun blockedReason(url: String): String? =
        if (SourcePolicyCore.isBlockedHost(url, blockedDomains)) NOT_SUPPORTED_MESSAGE else null
}
