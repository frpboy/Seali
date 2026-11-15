package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.ui.page.UpdateDialogImpl
import com.frpboy.seali.ui.page.onboarding.OnboardingFlow
import com.frpboy.seali.util.UpdateUtil
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Visual evidence for the first-run onboarding flow and the app update dialog.
 *
 * The flow is composed directly (test-configured state): it does NOT exercise the
 * fresh-install auto-show decision in OnboardingGate, which depends on package
 * install timestamps. All state is synthetic and local to the test app: no
 * network access and no package install is performed or attempted.
 */
class OnboardingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(fileName: String) {
        val bitmap =
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val out = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, fileName)
        out.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun text(resId: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun onboardingAllSixStepsAndNightlyConsent() {
        var finished = false
        compose.setContent { MaterialTheme { OnboardingFlow(onFinish = { finished = true }) } }

        fun next() = compose.onNodeWithText(text(R.string.onboarding_next)).performClick()

        compose.onNodeWithText(text(R.string.onboarding_welcome_title)).assertIsDisplayed()
        capture("onboarding-1-welcome.png")

        next()
        compose.onNodeWithText(text(R.string.onboarding_permissions_title)).assertIsDisplayed()
        capture("onboarding-2-notifications.png")

        next()
        compose.onNodeWithText(text(R.string.onboarding_storage_title)).assertIsDisplayed()
        capture("onboarding-3-storage.png")

        next()
        compose.onNodeWithText(text(R.string.onboarding_appearance_title)).assertIsDisplayed()
        capture("onboarding-4-appearance.png")

        next()
        compose.onNodeWithText(text(R.string.onboarding_engine_title)).assertIsDisplayed()
        capture("onboarding-5-engine-stable.png")

        // Nightly takes effect only through the Allow/Cancel consent dialog.
        compose.onNodeWithText(text(R.string.update_channel_nightly)).performClick()
        compose.onNodeWithText(text(R.string.runtime_nightly_allow)).assertIsDisplayed()
        capture("onboarding-6-nightly-warning.png")

        // Cancel keeps Stable selected.
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText(text(R.string.runtime_nightly_allow)).assertDoesNotExist()

        // Allow selects Nightly (synthetic preference in the test app only).
        compose.onNodeWithText(text(R.string.update_channel_nightly)).performClick()
        compose.onNodeWithText(text(R.string.runtime_nightly_allow)).performClick()
        compose.onNodeWithText(text(R.string.runtime_nightly_allow)).assertDoesNotExist()
        capture("onboarding-7-engine-nightly.png")

        next()
        compose.onNodeWithText(text(R.string.onboarding_finish_title)).assertIsDisplayed()
        capture("onboarding-8-finish.png")

        compose.onNodeWithText(text(R.string.onboarding_get_started)).performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test
    fun updateDialogStatesAreClearlySynthetic() {
        compose.setContent {
            MaterialTheme {
                UpdateDialogImpl(
                    onDismissRequest = {},
                    title = "v9.9.9-synthetic",
                    onConfirmUpdate = {},
                    releaseNote = "Synthetic release notes for UI evidence only.",
                    downloadStatus = UpdateUtil.DownloadStatus.Progress(42),
                )
            }
        }
        compose.onNodeWithText("v9.9.9-synthetic").assertIsDisplayed()
        capture("update-dialog-progress.png")

        compose.setContent {
            MaterialTheme {
                UpdateDialogImpl(
                    onDismissRequest = {},
                    title = "v9.9.9-synthetic",
                    onConfirmUpdate = {},
                    releaseNote = "Synthetic release notes for UI evidence only.",
                    downloadStatus =
                        UpdateUtil.DownloadStatus.Finished(java.io.File("/synthetic-update.apk")),
                )
            }
        }
        compose.onNodeWithText("v9.9.9-synthetic").assertIsDisplayed()
        capture("update-dialog-finished.png")
    }
}
