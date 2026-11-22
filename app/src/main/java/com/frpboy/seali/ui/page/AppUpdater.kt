package com.frpboy.seali.ui.page

import androidx.compose.runtime.*
import com.frpboy.seali.BuildConfig
import com.frpboy.seali.util.PreferenceUtil
import com.frpboy.seali.util.UpdateUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppUpdater() {
    // Sideload self-update exists only in the GitHub-distributed variants.
    // Google Play (com.frpboy.seali.google) and F-Droid builds update through
    // their own stores; the two packages never update each other.
    if (BuildConfig.FLAVOR != "generic" && BuildConfig.FLAVOR != "githubPreview") return

    var release by remember { mutableStateOf<UpdateUtil.Release?>(null) }
    LaunchedEffect(Unit) {
        if (!PreferenceUtil.isNetworkAvailableForDownload() || !PreferenceUtil.isAutoUpdateEnabled()) return@LaunchedEffect
        release = withContext(Dispatchers.IO) {
            try { UpdateUtil.checkForUpdate() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
    release?.let { candidate -> UpdateDialog(onDismissRequest = { release = null }, release = candidate) }
}
