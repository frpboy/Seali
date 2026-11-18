package com.frpboy.seali.ui.page.downloadv2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.frpboy.seali.App
import com.frpboy.seali.R
import com.frpboy.seali.util.DetailedErrorReport
import com.frpboy.seali.util.ToastUtil

/**
 * Shows the exact text first. Nothing is copied until the user taps Copy, and nothing is sent.
 */
@Composable
fun DetailedErrorReportDialog(throwable: Throwable, url: String?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val report = remember(throwable, url) { DetailedErrorReport.build(App.getVersionReport(), throwable, url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detailed_report_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.detailed_report_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SelectionContainer {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(report))
                ToastUtil.makeToast(R.string.error_copied)
                onDismiss()
            }) { Text(stringResource(R.string.detailed_report_copy)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
