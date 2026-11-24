package com.frpboy.seali.fixture

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.frpboy.seali.ui.page.*
import com.frpboy.seali.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** Substituted boundaries are explicit, never a production gate bypass. */
internal class FixtureEnvironment : UpdateEnvironment {
    var permission = false
    var discoveries = 0
    var downloads = 0
    override fun upToDateMessage() = "Fixture is up to date (invented result)"
    override fun enabled() = true // fixture only; production CandidateFeaturePolicy is not included/changed
    override fun hasInstallPermission() = permission
    override suspend fun discover(): UpdateUtil.Release { discoveries++; return UpdateUtil.Release(name = "Ownership fixture", body = "Invented update data. Nothing installs.") }
    override fun asset(release: UpdateUtil.Release) = UpdateUtil.AssetsItem("sha256:" + ApkUpdateVerifier.sha256("abc".toByteArray()),3)
    override suspend fun download(release: UpdateUtil.Release, target: File): Flow<UpdateUtil.DownloadStatus> = flow {
        downloads++
        emit(UpdateUtil.DownloadStatus.Progress(0))
        target.writeText("abc") // invented non-APK; package/signer validation explicitly NOT exercised
        emit(UpdateUtil.DownloadStatus.Verifying)
        emit(UpdateUtil.DownloadStatus.Finished(target))
    }
    override fun installerIntent(release: UpdateUtil.Release, file: File) = Intent("fixture.NO_INSTALL")
}

class OwnershipFixtureActivity : ComponentActivity() {
    internal lateinit var owner: UpdateCoordinator
    internal lateinit var environment: FixtureEnvironment
    var permissionLaunches = 0
    var installerLaunches = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val holder = ViewModelProvider(this)[EnvironmentHolder::class.java]
        environment = holder.environment
        owner = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return UpdateCoordinator(application, environment) as T
            }
        })[UpdateCoordinator::class.java]
        setContent {
            MaterialTheme {
                Column {
                    Text("SOURCE-ONLY FIXTURE: fake discovery/download/install boundaries")
                    Button(onClick = { owner.check(true) }) { Text("Manual fixture check") }
                    Button(onClick = { owner.check(false) }) { Text("Automatic fixture check") }
                    Button(onClick = owner::requestMaintenance) { Text("Manage retained fixture files") }
                }
                UpdateCoordinatorHost(owner,
                    launchPermission = { permissionLaunches++ },
                    launchInstaller = { installerLaunches++ })
            }
        }
    }
    class EnvironmentHolder : ViewModel() {
        internal val environment = FixtureEnvironment()
    }
}
