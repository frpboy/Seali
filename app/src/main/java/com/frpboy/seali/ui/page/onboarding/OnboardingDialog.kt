package com.frpboy.seali.ui.page.onboarding

import android.Manifest
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.frpboy.seali.R
import com.frpboy.seali.util.DARK_THEME_VALUE
import com.frpboy.seali.util.DarkThemePreference
import com.frpboy.seali.util.NightlyChannelSelection
import com.frpboy.seali.util.ONBOARDING_DONE
import com.frpboy.seali.util.PreferenceUtil
import com.frpboy.seali.util.PreferenceUtil.getBoolean
import com.frpboy.seali.util.PreferenceUtil.getInt
import com.frpboy.seali.util.UI_STYLE
import com.frpboy.seali.util.UI_STYLE_CLASSIC
import com.frpboy.seali.util.UI_STYLE_V2
import com.frpboy.seali.util.YT_DLP_NIGHTLY
import com.frpboy.seali.util.YT_DLP_STABLE
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_COUNT = 6

@Composable
fun OnboardingGate() {
    val context = LocalContext.current
    var showOnboarding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showOnboarding =
            withContext(Dispatchers.IO) {
                val done = ONBOARDING_DONE.getBoolean()
                val freshInstall =
                    runCatching {
                            val packageManager = context.packageManager
                            val info =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    packageManager.getPackageInfo(
                                        context.packageName,
                                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    packageManager.getPackageInfo(context.packageName, 0)
                                }
                            info.firstInstallTime == info.lastUpdateTime
                        }
                        .getOrDefault(false)
                if (!freshInstall && !done) PreferenceUtil.encodeBoolean(ONBOARDING_DONE, true)
                OnboardingPolicy.shouldShow(isFreshInstall = freshInstall, onboardingDone = done)
            }
    }
    if (showOnboarding) {
        OnboardingFlow(
            onFinish = {
                PreferenceUtil.encodeBoolean(ONBOARDING_DONE, true)
                showOnboarding = false
            }
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingFlow(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    var themeValue by rememberSaveable { mutableIntStateOf(DARK_THEME_VALUE.getInt()) }
    var uiStyle by rememberSaveable { mutableIntStateOf(UI_STYLE.getInt()) }
    var engineChannel by rememberSaveable {
        mutableIntStateOf(PreferenceUtil.effectiveYtdlpUpdateChannel())
    }
    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionState(permission = Manifest.permission.POST_NOTIFICATIONS)
        } else null

    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        0 -> OnboardingWelcomeStep()
                        1 ->
                            OnboardingPermissionStep(
                                notificationsGranted =
                                    notificationPermission?.status?.isGranted ?: true,
                                onRequest = { notificationPermission?.launchPermissionRequest() },
                            )
                        2 ->
                            OnboardingStorageStep(
                                defaultPath =
                                    remember {
                                        File(
                                                Environment.getExternalStoragePublicDirectory(
                                                    Environment.DIRECTORY_DOWNLOADS
                                                ),
                                                "Seali",
                                            )
                                            .absolutePath
                                    }
                            )
                        3 ->
                            OnboardingAppearanceStep(
                                themeValue = themeValue,
                                uiStyle = uiStyle,
                                onThemeSelected = {
                                    themeValue = it
                                    PreferenceUtil.modifyDarkThemePreference(it)
                                },
                                onStyleSelected = {
                                    uiStyle = it
                                    PreferenceUtil.updateUiStyle(it)
                                },
                            )
                        4 ->
                            OnboardingEngineStep(
                                channel = engineChannel,
                                onChannelSelected = {
                                    engineChannel = it
                                    // Selecting Nightly here is the explicit acceptance of
                                    // the warning text shown on this step.
                                    PreferenceUtil.saveYtdlpChannelSelection(
                                        NightlyChannelSelection(
                                            channel = it,
                                            nightlyAllowed = it == YT_DLP_NIGHTLY,
                                            dontWarnAgain = false,
                                        )
                                    )
                                },
                            )
                        else -> OnboardingFinishStep()
                    }
                }
                HorizontalPagerIndicator(
                    pagerState = pagerState,
                    pageCount = PAGE_COUNT,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.outline,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pagerState.currentPage > 0) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.onboarding_back))
                            }
                        }
                        Button(
                            onClick = {
                                if (pagerState.currentPage == PAGE_COUNT - 1) onFinish()
                                else
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (pagerState.currentPage == PAGE_COUNT - 1)
                                        R.string.onboarding_get_started
                                    else R.string.onboarding_next
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepLayout(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        content()
    }
}

@Composable
fun OnboardingWelcomeStep() {
    OnboardingStepLayout(
        icon = Icons.Outlined.DownloadForOffline,
        title = stringResource(R.string.onboarding_welcome_title),
        description = stringResource(R.string.onboarding_welcome_desc),
    )
}

@Composable
fun OnboardingPermissionStep(notificationsGranted: Boolean, onRequest: () -> Unit) {
    OnboardingStepLayout(
        icon = Icons.Outlined.Notifications,
        title = stringResource(R.string.onboarding_permissions_title),
        description = stringResource(R.string.onboarding_notifications_desc),
    ) {
        if (notificationsGranted) {
            Text(
                text = stringResource(R.string.onboarding_notifications_granted),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            OutlinedButton(onClick = onRequest) {
                Text(stringResource(R.string.onboarding_notifications_allow))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_notifications_skip_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun OnboardingStorageStep(defaultPath: String) {
    OnboardingStepLayout(
        icon = Icons.Outlined.FolderOpen,
        title = stringResource(R.string.onboarding_storage_title),
        description = stringResource(R.string.onboarding_storage_desc, defaultPath),
    )
}

@Composable
fun OnboardingAppearanceStep(
    themeValue: Int,
    uiStyle: Int,
    onThemeSelected: (Int) -> Unit,
    onStyleSelected: (Int) -> Unit,
) {
    OnboardingStepLayout(
        icon = Icons.Outlined.Palette,
        title = stringResource(R.string.onboarding_appearance_title),
        description = stringResource(R.string.onboarding_appearance_desc),
    ) {
        OnboardingChoiceGroup(
            label = stringResource(R.string.onboarding_theme_label),
            options =
                listOf(
                    DarkThemePreference.FOLLOW_SYSTEM to stringResource(R.string.follow_system),
                    DarkThemePreference.OFF to stringResource(R.string.off),
                    DarkThemePreference.ON to stringResource(R.string.on),
                ),
            selected = themeValue,
            onSelected = onThemeSelected,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OnboardingChoiceGroup(
            label = stringResource(R.string.onboarding_interface_style),
            options =
                listOf(
                    UI_STYLE_V2 to stringResource(R.string.onboarding_style_v2),
                    UI_STYLE_CLASSIC to stringResource(R.string.onboarding_style_classic),
                ),
            selected = uiStyle,
            onSelected = onStyleSelected,
        )
    }
}

@Composable
fun OnboardingEngineStep(channel: Int, onChannelSelected: (Int) -> Unit) {
    OnboardingStepLayout(
        icon = Icons.Outlined.SystemUpdate,
        title = stringResource(R.string.onboarding_engine_title),
        description = stringResource(R.string.onboarding_engine_desc),
    ) {
        OnboardingChoiceGroup(
            label = stringResource(R.string.update_channel),
            options =
                listOf(
                    YT_DLP_STABLE to stringResource(R.string.update_channel_stable),
                    YT_DLP_NIGHTLY to stringResource(R.string.update_channel_nightly),
                ),
            selected = channel,
            onSelected = onChannelSelected,
        )
        if (channel == YT_DLP_NIGHTLY) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.runtime_nightly_warning_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun OnboardingFinishStep() {
    OnboardingStepLayout(
        icon = Icons.Outlined.CheckCircle,
        title = stringResource(R.string.onboarding_finish_title),
        description = stringResource(R.string.onboarding_finish_desc),
    )
}

@Composable
private fun OnboardingChoiceGroup(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        options.forEach { (value, text) ->
            androidx.compose.material3.FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(text) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewWelcomeStep() {
    OnboardingWelcomeStep()
}

@Preview
@Composable
private fun PreviewPermissionStep() {
    OnboardingPermissionStep(notificationsGranted = false, onRequest = {})
}

@Preview
@Composable
private fun PreviewStorageStep() {
    OnboardingStorageStep(defaultPath = "/storage/emulated/0/Download/Seali")
}

@Preview
@Composable
private fun PreviewAppearanceStep() {
    OnboardingAppearanceStep(
        themeValue = DarkThemePreference.FOLLOW_SYSTEM,
        uiStyle = UI_STYLE_V2,
        onThemeSelected = {},
        onStyleSelected = {},
    )
}

@Preview
@Composable
private fun PreviewEngineStep() {
    OnboardingEngineStep(channel = YT_DLP_STABLE, onChannelSelected = {})
}

@Preview
@Composable
private fun PreviewFinishStep() {
    OnboardingFinishStep()
}
