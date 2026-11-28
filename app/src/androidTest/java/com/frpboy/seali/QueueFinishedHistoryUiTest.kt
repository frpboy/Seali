package com.frpboy.seali

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.database.objects.DownloadedVideoInfo
import com.frpboy.seali.download.Task
import com.frpboy.seali.ui.page.downloadv2.DownloadPageImplV2
import com.frpboy.seali.ui.page.downloadv2.SheetContent
import com.frpboy.seali.util.synthesizeHistoryEntry
import org.junit.Rule
import org.junit.Test

/**
 * Fixture-level Compose UI checks for Room-backed finished-history entries in
 * the queue: the task map is seeded directly with synthesized entries (the
 * state a restart would produce), so these verify presentation, counts and
 * action boundaries only. They do NOT exercise Room, the DAO, the history
 * collector, an actual process restart, or remove() wiring. Execution needs
 * an emulator route; PR CI compiles but does not run them. Screenshots are
 * captured into filesDir for a runtime lane to pull as artifacts.
 */
class QueueFinishedHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    private val TAG = "QueueFinishedHistoryUiTest"

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
    fun historyEntriesFillFinishedLaneAndCounts() {
        // Fixture stands in for the post-restart state: no session tasks, only
        // Room-backed entries in the map.
        val states = mutableStateMapOf<Task, Task.State>()
        states += synthesizeHistoryEntry(row(1, "First saved download"))
        states += synthesizeHistoryEntry(row(2, "Second saved download"))
        compose.setContent {
            MaterialTheme {
                DownloadPageImplV2(taskDownloadStateMap = states, onActionPost = { _, _ -> })
            }
        }
        // Diagnostics added after the first device execution (FTL matrix-2f38mbviiohff)
        // failed at the original first assertion with "Assert failed: The component is
        // not displayed!". Per compose ui-test semantics (Assertions.kt: the node is
        // fetched first; a missing node fails with "Failed: assertExists" instead), that
        // message means the "All (2)" node EXISTED but was not composed-and-placed with
        // visible bounds at query time, while logcat showed the host activity's first
        // frame completing only at teardown. The bounded wait + bounds/semantics/pixel
        // evidence below distinguishes never-appeared from late-placed vs clipped. The
        // exact count and display assertions are unchanged; the wait is a diagnostic
        // boundary, not a fix, and the two W DocumentFile NPEs (no document provider in
        // the bare host for the entries' file-existence checks) remain expected noise.
        try {
            val waitStart = System.currentTimeMillis()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose
                    .onAllNodesWithText("All (2)", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // Elapsed ms to existence: the number that separates late placement from
            // never-appearing. Any pass of this test is wait-assisted and must be
            // reported as such, not as a clean reproduction of the original check.
            Log.i(TAG, "All(2) existed after " + (System.currentTimeMillis() - waitStart) + " ms (wait-assisted pass)")
            val all2 = compose.onNodeWithText("All (2)")
            // ui-test 1.8 dropped assertExists(); fetchSemanticsNode() is the same
            // existence gate: it throws "Failed: assertExists." when absent.
            val node = all2.fetchSemanticsNode()
            Log.i(TAG, "root boundsInRoot=" + compose.onRoot().fetchSemanticsNode().boundsInRoot)
            Log.i(
                TAG,
                "All(2) boundsInRoot=" + node.boundsInRoot +
                    " boundsInWindow=" + node.boundsInWindow +
                    " positionInRoot=" + node.positionInRoot,
            )
            compose.onRoot().printToLog(TAG)
            capture("queue-history-preassert.png")
            compose.onNodeWithText("All (2)").assertIsDisplayed()
            compose.onNodeWithText("Finished (2)").assertIsDisplayed().performClick()
            compose.onNodeWithText("First saved download").assertIsDisplayed()
            compose.onNodeWithText("Second saved download").assertIsDisplayed()
        } catch (t: Throwable) {
            runCatching { compose.onRoot().printToLog(TAG) }
            runCatching { capture("queue-history-failure.png") }
            throw t
        }
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
    fun liveCompletedEntryOffersQueueRemovalNotFileDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task =
            Task(
                url = "https://example.com/live",
                preferences = com.frpboy.seali.util.DownloadUtil.DownloadPreferences.EMPTY,
                id = "live-completed-1",
            )
        val state =
            Task.State(
                downloadState = Task.DownloadState.Completed("/downloads/live.mp4"),
                videoInfo = null,
                viewState = Task.ViewState(title = "Live completed download"),
            )
        compose.setContent {
            MaterialTheme {
                SheetContent(
                    task = task,
                    viewState = state.viewState,
                    downloadState = state.downloadState,
                    onDismissRequest = {},
                    onActionPost = { _, _ -> },
                )
            }
        }
        compose
            .onNodeWithText(context.getString(R.string.queue_remove_from_queue))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.delete)).assertDoesNotExist()
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
