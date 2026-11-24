package com.frpboy.seali.ui.page

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frpboy.seali.util.UpdateSessionState

internal val LocalUpdateCoordinator = staticCompositionLocalOf<UpdateCoordinator> { error("No activity updater owner") }

@Composable
internal fun UpdateCoordinatorHost(
    owner: UpdateCoordinator,
    launchPermission: ((Intent) -> Unit)? = null,
    launchInstaller: ((Intent) -> Unit)? = null,
) {
    val ui by owner.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(ui.id, ui.effect) {
        val id = ui.id ?: return@LaunchedEffect
        if (ui.effect == null) return@LaunchedEffect
        val (effect, intent) = owner.consume(id) ?: return@LaunchedEffect
        // MAIN, no suspension between final identity check and launch.
        if (!owner.stillCurrent(id)) return@LaunchedEffect
        try {
            when (effect) {
                UpdateSessionState.Effect.OPEN_PERMISSION -> (launchPermission ?: { context.startActivity(it) })(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                UpdateSessionState.Effect.LAUNCH_INSTALLER -> (launchInstaller ?: { context.startActivity(it) })(checkNotNull(intent))
            }
        } catch (_: Exception) { owner.launchFailed(id) }
    }
    ui.release?.takeUnless { ui.awaitingPermission }?.let { release ->
        val id = ui.id ?: return@let
        UpdateDialogImpl(onDismissRequest = { owner.dismiss(id) }, title = release.name ?: release.tagName.orEmpty(),
            onConfirmUpdate = { owner.download(id) }, releaseNote = release.body.orEmpty(),
            downloadStatus = ui.status, sizeLabel = com.frpboy.seali.util.UpdateUtil.updateSizeLabel(release),
            dateLabel = com.frpboy.seali.util.UpdateUtil.releaseDateLabel(release))
    }
    if (ui.awaitingPermission && ui.id != null) {
        val id = checkNotNull(ui.id)
        AlertDialog(onDismissRequest = { owner.dismiss(id) },
            title = { Text("Allow app updates in Android") },
            text = { Text("After allowing Seali to install apps in Android settings, return here and tap Continue. Nothing installs without Android's confirmation.") },
            confirmButton = { TextButton(onClick = { owner.permissionContinue(id) }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { owner.dismiss(id) }) { Text("Cancel update") } })
    }
    if (ui.confirmMaintenance) {
        AlertDialog(onDismissRequest = owner::cancelMaintenance,
            title = { Text("Clear retained update files?") },
            text = { Text("This removes only Seali's retained updater APKs and temporary update files. It does not remove your downloads or settings. Close any Android install prompt first: clearing these files can make an unfinished install fail. You can download the update again.") },
            confirmButton = { TextButton(onClick = owner::confirmMaintenance) { Text("Clear update files") } },
            dismissButton = { TextButton(onClick = owner::cancelMaintenance) { Text("Keep files") } })
    }
    ui.message?.let { text ->
        AlertDialog(onDismissRequest = owner::closeMessage, text = { Text(text) },
            confirmButton = { TextButton(onClick = owner::closeMessage) { Text("Close") } })
    }
}
