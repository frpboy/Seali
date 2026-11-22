package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.ui.page.UpdateProgressContent
import com.frpboy.seali.ui.page.UpdateProgressTags
import com.frpboy.seali.ui.page.UpdateUiState
import org.junit.Rule
import org.junit.Test

/**
 * Fixture-level render of the update page states. Verifies presentation only: nothing is downloaded, verified or
 * installed, and the page is not reachable in the app (update gate off). Screenshots go to filesDir for a runtime
 * lane to pull as artifacts.
 */
class UpdateProgressPageTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        val out = java.io.File(inst.targetContext.filesDir, "update-$name.png")
        out.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun everyStateRendersItsStatusAndTheFixtureFacts() {
        var state by mutableStateOf<UpdateUiState>(UpdateUiState.Available)
        var updates = 0
        var closes = 0
        compose.setContent {
            MaterialTheme {
                UpdateProgressContent(
                    versionName = "2.0.1",
                    sizeLabel = "14.4 MB",
                    dateLabel = "2026-11-19",
                    changelog = "Fixes after 2.0.0\n- Fixture changelog line one\n- Fixture changelog line two",
                    state = state,
                    onUpdate = { updates++ },
                    onClose = { closes++ },
                )
            }
        }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("2.0.1").assertIsDisplayed()
        compose.onNodeWithText("14.4 MB").assertIsDisplayed()
        compose.onNodeWithText("2026-11-19").assertIsDisplayed()
        compose.onNodeWithText("Changelog").assertIsDisplayed()
        compose.onNodeWithText("Update").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, updates) }
        shot("available")

        compose.runOnIdle { state = UpdateUiState.Downloading(42) }
        compose.onNodeWithText("Installing the update").assertIsDisplayed()
        compose.onNodeWithTag(UpdateProgressTags.PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("Downloading the update, 42%").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsNotEnabled()
        shot("downloading")

        compose.runOnIdle { state = UpdateUiState.Verifying }
        compose.onNodeWithText("Checking the download and its signing key").assertIsDisplayed()
        shot("verifying")

        compose.runOnIdle { state = UpdateUiState.AwaitingSystemConfirmation }
        compose.onNodeWithText("Verified. Confirm the install in the Android prompt").assertIsDisplayed()
        shot("confirm")

        compose.runOnIdle { state = UpdateUiState.Refused("it is not signed by the same key as the installed app") }
        compose.onNodeWithText("Update not installed").assertIsDisplayed()
        compose.onNodeWithTag(UpdateProgressTags.STATUS).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(2, updates)
            org.junit.Assert.assertEquals(1, closes)
        }
        shot("refused")
    }
}
