package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.database.objects.DownloadedVideoInfo
import com.frpboy.seali.download.Task
import com.frpboy.seali.ui.page.downloadv2.DownloadPageImplV2
import com.frpboy.seali.ui.page.downloadv2.SheetContent
import com.frpboy.seali.util.synthesizeHistoryEntry
import org.junit.Rule
import org.junit.Test

/**
 * Visual qualification for Room-backed finished-history entries in the queue.
 * Runs on an emulator without network or native engine payloads; captures real
 * composed UI screenshots into filesDir for the lane to pull as artifacts.
 */
class QueueFinishedHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    private fun row(id: Int, title: String) =
        DownloadedVideoInfo(
            id = id,
            videoTitle = title,
            videoAuthor = "author$id",
            videoUrl = "https://example.com/v$id",
            thumbnailUrl = "",
            videoPath = "/downloads/v$id.mp4",
        )

    private fun capture(name: String) {
        val bitmap =
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val out = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name)
        out.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun historyEntriesFillFinishedLaneAndCountsAfterRestart() {
        // The post-restart state: no session tasks, only Room-backed entries.
        val states = mutableStateMapOf<Task, Task.State>()
        states += synthesizeHistoryEntry(row(1, "First saved download"))
        states += synthesizeHistoryEntry(row(2, "Second saved download"))
        compose.setContent {
            MaterialTheme {
                DownloadPageImplV2(taskDownloadStateMap = states, onActionPost = { _, _ -> })
            }
        }
        compose.onNodeWithText("All (2)").assertIsDisplayed()
        compose.onNodeWithText("Finished (2)").assertIsDisplayed().performClick()
        compose.onNodeWithText("First saved download").assertIsDisplayed()
        compose.onNodeWithText("Second saved download").assertIsDisplayed()
        capture("queue-history-restart.png")
    }

    @Test
    fun historyEntryActionsHideDeleteAndHandleMissingFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (task, state) = synthesizeHistoryEntry(row(3, "Saved with missing file"))
        compose.setContent {
            MaterialTheme {
                SheetContent(
                    task = task,
                    viewState = state.viewState,
                    downloadState = state.downloadState,
                    onDismissRequest = {},
                    onActionPost = { _, _ -> },
                    isMissingFile = true,
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.queue_history_file_removed))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.open_file)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.delete)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.resume)).assertDoesNotExist()
        capture("queue-history-missing-file.png")
    }

    @Test
    fun historyEntryActionsShowFileActionsWithoutDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (task, state) = synthesizeHistoryEntry(row(4, "Saved with file present"))
        compose.setContent {
            MaterialTheme {
                SheetContent(
                    task = task,
                    viewState = state.viewState,
                    downloadState = state.downloadState,
                    onDismissRequest = {},
                    onActionPost = { _, _ -> },
                    isMissingFile = false,
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.open_file)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.share)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.delete)).assertDoesNotExist()
        capture("queue-history-actions.png")
    }
}
