package com.frpboy.seali.util

import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Play edition only: also exclude the named platforms' extractors, so a redirect cannot reach them.
 * Other flavors have no filter and the request is unchanged. Every yt-dlp request that carries a
 * user URL goes through this (DownloadUtil: playlist info, single info, download).
 */
internal fun YoutubeDLRequest.applySourcePolicy(): YoutubeDLRequest =
    apply { SourcePolicy.extractorFilter?.let { addOption("--use-extractors", it) } }
