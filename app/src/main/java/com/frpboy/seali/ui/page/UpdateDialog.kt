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
