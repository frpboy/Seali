package com.frpboy.seali.util

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.ResponseBody

/** Copy only the published byte count. Content-Length may be absent (chunked); SHA256 verification follows. */
internal object UpdateDownloadStream {
    fun copy(body: ResponseBody, target: File, expectedSize: Long): Flow<UpdateUtil.DownloadStatus> = flow {
        require(expectedSize > 0) { "Missing published APK size" }
        var complete = false
        try {
            body.use {
                val declared = body.contentLength()
                check(declared < 0 || declared == expectedSize) { "Unexpected APK Content-Length" }
                var lastPercent = 0
                emit(UpdateUtil.DownloadStatus.Progress(lastPercent))
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val bytes = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            check(count.toLong() <= expectedSize - copied) { "APK exceeds published size" }
                            output.write(bytes, 0, count)
                            copied += count
                            val percent = ((copied.toDouble() / expectedSize) * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                emit(UpdateUtil.DownloadStatus.Progress(percent))
                            }
                        }
                        check(copied == expectedSize) { "Incomplete APK download" }
                    }
                }
            }
            // Keep cleanup armed while the collector performs verification of Finished.
            emit(UpdateUtil.DownloadStatus.Finished(target))
            complete = true
        } finally {
            if (!complete) target.delete()
        }
    }
}
