package com.frpboy.seali.ui.page

import android.app.Application
import android.content.Intent
import android.os.Build
import com.frpboy.seali.util.*
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Explicit effects boundary. The real coordinator/store/session are unchanged by fixture substitution. */
internal interface UpdateEnvironment {
    fun upToDateMessage(): String
    fun enabled(): Boolean
    fun hasInstallPermission(): Boolean
    suspend fun discover(): UpdateUtil.Release?
    fun asset(release: UpdateUtil.Release): UpdateUtil.AssetsItem
    suspend fun download(release: UpdateUtil.Release, target: File): Flow<UpdateUtil.DownloadStatus>
    fun installerIntent(release: UpdateUtil.Release, file: File): Intent
}

