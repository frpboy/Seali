package com.frpboy.seali.ui.page

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.frpboy.seali.util.UpdateUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared manual/automatic entry: one collector, one permission flow, one verified install hand-off. */
@Composable
fun UpdateDialog(onDismissRequest: () -> Unit, release: UpdateUtil.Release) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(release) { mutableStateOf<UpdateUtil.DownloadStatus>(UpdateUtil.DownloadStatus.NotYet) }
    var failure by remember(release) { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun install() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { UpdateUtil.installLatestApk(release, context) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = UpdateUtil.failureMessage(context, e) }
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) install()
        else failure = "Allow updates from Seali in Android settings before installing."
    }
    fun requestInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } else install()
    }

    UpdateDialogImpl(
        onDismissRequest = { job?.cancel(); onDismissRequest() },
        title = release.name ?: release.tagName.orEmpty(),
        onConfirmUpdate = {
            if (job?.isActive != true) {
                failure = null
                job = scope.launch {
                    try {
                        // Files/network/verification stay off the main thread; UI and launcher callbacks stay on it.
                        val flow = withContext(Dispatchers.IO) { UpdateUtil.downloadApk(context, release) }
                        flow.collect {
                            status = it
                            if (it is UpdateUtil.DownloadStatus.Finished) requestInstall()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = UpdateUtil.failureMessage(context, e)
                        status = UpdateUtil.DownloadStatus.NotYet
                    }
                }
            }
        },
        releaseNote = release.body.orEmpty(),
        downloadStatus = status,
        sizeLabel = UpdateUtil.updateSizeLabel(release),
        dateLabel = UpdateUtil.releaseDateLabel(release),
        failureMessage = failure,
    )
}

@Composable
fun UpdateDialogImpl(
    onDismissRequest: () -> Unit,
    title: String,
    onConfirmUpdate: () -> Unit,
    releaseNote: String,
    downloadStatus: UpdateUtil.DownloadStatus,
    sizeLabel: String? = null,
    dateLabel: String? = null,
    failureMessage: String? = null,
) {
    val state = failureMessage?.let { UpdateUiState.Refused(it) } ?: when (downloadStatus) {
        UpdateUtil.DownloadStatus.NotYet -> UpdateUiState.Available
        is UpdateUtil.DownloadStatus.Progress -> UpdateUiState.Downloading(downloadStatus.percent)
        UpdateUtil.DownloadStatus.Verifying -> UpdateUiState.Verifying
        is UpdateUtil.DownloadStatus.Finished -> UpdateUiState.AwaitingSystemConfirmation
    }
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface {
            UpdateProgressContent(title, sizeLabel, dateLabel, releaseNote, state,
                onUpdate = onConfirmUpdate, onClose = onDismissRequest)
        }
    }
}
