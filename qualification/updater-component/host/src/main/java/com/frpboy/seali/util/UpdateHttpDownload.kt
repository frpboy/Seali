package com.frpboy.seali.util

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared HTTP lifecycle only. Caller retains feature/distribution/asset admission and Android verification. */
internal object UpdateHttpDownload {
    fun download(
        client: OkHttpClient,
        targetUrl: String,
        target: File,
        expectedSize: Long,
        verify: (File) -> Unit,
    ): Flow<UpdateUtil.DownloadStatus> {
        val request = Request.Builder().url(targetUrl).build()
        // Own the HTTP response inside collection. Closing/leaving the page before collection cannot leak it.
        return flow {
            val call = client.newCall(request)
            UpdateBlockingCall.own(cancel = { call.cancel() }) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "APK download failed: HTTP ${response.code}" }
                    UpdateDownloadStream.copy(response.body, target, expectedSize).collect { status ->
                        if (status is UpdateUtil.DownloadStatus.Finished) {
                            emit(UpdateUtil.DownloadStatus.Verifying)
                            verify(status.file)
                        }
                        emit(status)
                    }
                }
            }
        }.flowOn(Dispatchers.IO).buffer(0)
    }
}
