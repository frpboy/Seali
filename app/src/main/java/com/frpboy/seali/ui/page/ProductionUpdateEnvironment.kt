package com.frpboy.seali.ui.page

import android.app.Application
import android.content.Intent
import android.os.Build
import com.frpboy.seali.BuildConfig
import com.frpboy.seali.util.*
import kotlinx.coroutines.flow.Flow
import java.io.File

internal class ProductionUpdateEnvironment(private val app: Application) : UpdateEnvironment {
    override fun upToDateMessage() = app.getString(com.frpboy.seali.R.string.app_up_to_date)
    override fun enabled() = CandidateFeaturePolicy.appUpdatesEnabled(BuildConfig.FLAVOR)
    override fun hasInstallPermission() = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()
    override suspend fun discover() = UpdateUtil.checkForUpdate(app)
    override fun asset(release: UpdateUtil.Release) = UpdateUtil.requireDownloadAsset(release, Build.SUPPORTED_ABIS.firstOrNull())
    override suspend fun download(release: UpdateUtil.Release, target: File) = UpdateUtil.downloadApk(app, release, target)
    override fun installerIntent(release: UpdateUtil.Release, file: File) = UpdateUtil.installerIntent(release, file, app)
}
