package com.frpboy.seali.ui.page.settings.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.frpboy.seali.BuildConfig
import com.frpboy.seali.R
import com.frpboy.seali.ui.component.BackButton
import com.frpboy.seali.ui.component.PreferenceInfo
import com.frpboy.seali.ui.page.UpdateDialog
import com.frpboy.seali.util.CandidateFeaturePolicy
import com.frpboy.seali.util.UpdateUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatePage(onNavigateBack: () -> Unit) {
    var release by remember { mutableStateOf<UpdateUtil.Release?>(null) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val available = CandidateFeaturePolicy.appUpdatesEnabled(BuildConfig.FLAVOR)
    Scaffold(topBar = {
        LargeTopAppBar(title = { Text(stringResource(R.string.auto_update)) },
            navigationIcon = { BackButton { onNavigateBack() } })
    }) { paddings ->
        Column(Modifier.padding(paddings)) {
            if (!available) {
                PreferenceInfo(text = stringResource(R.string.app_updates_unavailable_candidate))
            } else {
                androidx.compose.material3.Button(
                    enabled = !checking,
                    onClick = {
                        if (!checking) {
                            checking = true
                            result = null
                            scope.launch {
                                try {
                                    release = withContext(Dispatchers.IO) { UpdateUtil.checkForUpdate() }
                                    if (release == null) result = R.string.app_up_to_date
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    result = R.string.app_update_failed
                                } finally { checking = false }
                            }
                        }
                    },
                ) {
                    if (checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else androidx.compose.material3.Icon(Icons.Outlined.Update, contentDescription = null)
                    Text(stringResource(R.string.check_for_updates))
                }
                result?.let { PreferenceInfo(text = stringResource(it)) }
            }
        }
    }
    release?.let { UpdateDialog(onDismissRequest = { release = null }, release = it) }
}

@Composable
fun ProgressIndicatorButton(
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = modifier,
        onClick = onClick,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (isLoading)
            Box(modifier = Modifier.size(18.dp)) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).align(Alignment.Center),
                    strokeWidth = 3.dp,
                )
            }
        else Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}
