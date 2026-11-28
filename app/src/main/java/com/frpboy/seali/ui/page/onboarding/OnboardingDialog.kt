package com.frpboy.seali.ui.page.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frpboy.seali.BuildConfig
import com.frpboy.seali.R

/**
 * First-launch tour: feature guide, then one rationale page per optional permission
 * the flavor and Android version actually use. Every system prompt or settings screen
 * is reached only after its rationale page; declining any page never blocks the app.
 * [onFinished] reports true when the last page was completed, false when skipped.
 */
@Composable
fun OnboardingDialog(onFinished: (completed: Boolean) -> Unit) {
    val pages =
        onboardingPages(
            sdkInt = Build.VERSION.SDK_INT,
            isGenericFlavor = BuildConfig.FLAVOR == "generic",
        )
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val isLast = pageIndex == pages.lastIndex
    val advance = {
        if (isLast) onFinished(true) else pageIndex += 1
    }

    AlertDialog(
        onDismissRequest = { onFinished(false) },
        title = {
            Text(
                stringResource(
                    when (page) {
                        OnboardingPage.WELCOME -> R.string.onboarding_welcome_title
                        OnboardingPage.NOTIFICATIONS -> R.string.onboarding_notifications_title
                        OnboardingPage.BATTERY -> R.string.onboarding_battery_title
                        OnboardingPage.STORAGE_AND_INSTALL -> R.string.onboarding_storage_title
                    }
                )
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (page) {
                    OnboardingPage.WELCOME -> WelcomeContent()
                    OnboardingPage.NOTIFICATIONS -> Text(stringResource(R.string.onboarding_notifications_desc))
                    OnboardingPage.BATTERY -> Text(stringResource(R.string.onboarding_battery_desc))
                    OnboardingPage.STORAGE_AND_INSTALL -> Text(stringResource(R.string.onboarding_storage_desc))
                }
            }
        },
        confirmButton = { PageAction(page, advance) },
        dismissButton = {
            if (page == OnboardingPage.WELCOME) {
                // Only an explicit Skip on the first page counts as declined.
                TextButton(onClick = { onFinished(false) }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            } else {
                TextButton(onClick = advance) { Text(stringResource(R.string.onboarding_not_now)) }
            }
        },
    )
}

/** The confirm action of each page: a permission prompt or settings link where the
 * page has one, otherwise plain navigation. The system prompt always comes after the
 * rationale, never before it. */
@Composable
private fun PageAction(page: OnboardingPage, advance: () -> Unit) {
    val context = LocalContext.current
    when (page) {
        OnboardingPage.WELCOME ->
            TextButton(onClick = advance) { Text(stringResource(R.string.onboarding_next)) }
        OnboardingPage.NOTIFICATIONS -> {
            val launcher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    advance()
                }
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        advance()
                    }
                }
            ) {
                Text(stringResource(R.string.onboarding_allow_notifications))
            }
        }
        OnboardingPage.BATTERY ->
            TextButton(
                onClick = {
                    runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    advance()
                }
            ) {
                Text(stringResource(R.string.open_settings))
            }
        OnboardingPage.STORAGE_AND_INSTALL ->
            Column {
                if (Build.VERSION.SDK_INT >= 30) {
                    TextButton(
                        onClick = {
                            runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:" + context.packageName),
                                        )
                                    )
                                }
                            advance()
                        }
                    ) {
                        Text(stringResource(R.string.onboarding_allow_files))
                    }
                }
                TextButton(
                    onClick = {
                        runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + context.packageName),
                                    )
                                )
                            }
                        advance()
                    }
                ) {
                    Text(stringResource(R.string.onboarding_allow_updates))
                }
            }
    }
}

@Composable
private fun WelcomeContent() {
    Column {
        IconDescription(Icons.Outlined.ContentPaste, stringResource(R.string.paste_desc))
        IconDescription(Icons.Outlined.FileDownload, stringResource(R.string.download_desc))
        IconDescription(Icons.Outlined.Subscriptions, stringResource(R.string.download_history_desc))
        IconDescription(Icons.Outlined.Downloading, stringResource(R.string.battery_settings_desc))
        IconDescription(Icons.Outlined.SettingsSuggest, stringResource(R.string.check_download_settings_desc))
    }
}

@Composable
private fun IconDescription(icon: ImageVector, description: String) {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(modifier = Modifier.size(24.dp), imageVector = icon, contentDescription = null)
        Text(modifier = Modifier.padding(start = 12.dp), text = description)
    }
}
