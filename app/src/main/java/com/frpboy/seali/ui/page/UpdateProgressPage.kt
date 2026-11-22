package com.frpboy.seali.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** What the update page is showing. Stateless so it can be rendered from fixtures. */
sealed class UpdateUiState {
    object Available : UpdateUiState()
    data class Downloading(val percent: Int) : UpdateUiState()
    object Verifying : UpdateUiState()
    /** The file passed every check; Android now asks the user to confirm the install. */
    object AwaitingSystemConfirmation : UpdateUiState()
    data class Refused(val message: String) : UpdateUiState()
}

object UpdateProgressTags {
    const val STATUS = "update-status"
    const val PROGRESS = "update-progress"
}

/**
 * "Installing the update" layout: status, progress, version / size / date cards, then the changelog.
 * Shared by both app update entry points and the isolated visual fixture host. The app-update gate stays off.
 */
@Composable
fun UpdateProgressContent(
    versionName: String,
    sizeLabel: String?,
    dateLabel: String?,
    changelog: String,
    state: UpdateUiState,
    modifier: Modifier = Modifier,
    onUpdate: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when (state) {
                is UpdateUiState.Refused -> "Update not installed"
                UpdateUiState.Available -> "Update available"
                else -> "Installing the update"
            },
            style = MaterialTheme.typography.headlineLarge,
        )
        val statusText =
            when (state) {
                UpdateUiState.Available -> "Tap Update to download this version"
                is UpdateUiState.Downloading -> "Downloading the update, ${state.percent}%"
                UpdateUiState.Verifying -> "Checking the download and its signing key"
                UpdateUiState.AwaitingSystemConfirmation -> "Verified. Confirm the install in the Android prompt"
                is UpdateUiState.Refused -> state.message
            }
        when (state) {
            is UpdateUiState.Downloading ->
                LinearProgressIndicator(
                    progress = { state.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().testTag(UpdateProgressTags.PROGRESS),
                )
            UpdateUiState.Verifying ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(UpdateProgressTags.PROGRESS))
            else -> {}
        }
        Text(statusText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(UpdateProgressTags.STATUS))
        InfoCard("Seali version", versionName)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCard("Update size", sizeLabel ?: "Unknown", Modifier.weight(1f))
            InfoCard("Date", dateLabel ?: "Unknown", Modifier.weight(1f))
        }
        if (onUpdate != null || onClose != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onUpdate != null) Button(
                    onClick = onUpdate,
                    enabled = state is UpdateUiState.Available || state is UpdateUiState.Refused,
                ) { Text(if (state is UpdateUiState.Refused) "Retry" else "Update") }
                if (onClose != null) TextButton(onClick = onClose) { Text("Close") }
            }
        }
        Text("Changelog", style = MaterialTheme.typography.headlineMedium)
        Text(changelog.ifBlank { "No release notes were published." }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}
