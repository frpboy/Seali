package com.frpboy.seali.util

import java.io.File

/** FIXTURE DTO adapter ONLY. Not production release lookup/download/verification/install code. */
object UpdateUtil {
    data class Release(val name: String? = null, val tagName: String? = null, val body: String? = null)
    data class AssetsItem(val digest: String?, val size: Long?)
    sealed class DownloadStatus {
        object NotYet : DownloadStatus()
        object Verifying : DownloadStatus()
        data class Progress(val percent: Int) : DownloadStatus()
        data class Finished(val file: File) : DownloadStatus()
    }
    fun updateSizeLabel(release: Release): String = "3 bytes, fixture"
    fun releaseDateLabel(release: Release): String = "2026-11-23"
}
