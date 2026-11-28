package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.frpboy.seali.ui.page.onboarding.OnboardingDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests for the first-launch tour dialog. Page SELECTION (which pages exist for
 * a given API level and flavor) is covered by OnboardingEligibilityTest on the JVM;
 * these only exercise the host the device provides. Navigation uses "Not now" past the
 * battery/storage pages: their action buttons open real system settings screens, which
 * must not be triggered from a test.
 */
class OnboardingDialogUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun skipOnWelcomeDeclines() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText("Welcome to Seali").assertIsDisplayed()
        compose.onNodeWithText("Skip tour").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(false, finished)
    }

    @Test
    fun nextThroughAllPagesCompletes() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText("Welcome to Seali").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed().performClick()
        // Remaining pages: advance with "Not now" (never the settings-opening buttons).
        repeat(3) {
            compose.waitForIdle()
            val notNow = compose.onAllNodesWithText("Not now")
            if (notNow.fetchSemanticsNodes().isNotEmpty()) {
                notNow[0].performClick()
            }
        }
        compose.waitForIdle()
        assertEquals(true, finished)
    }
}
