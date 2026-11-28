package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.ui.page.onboarding.OnboardingDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Navigation and dismissal smoke tests for the first-launch tour dialog. Page
 * SELECTION (which pages exist per API level and flavor) is JVM-covered in
 * OnboardingEligibilityTest; permission prompts and settings intents are not exercised
 * here (their buttons open real system UI). "Not now" is used to advance past those
 * pages. This proves navigation and the declined/completed contract only, not the
 * permission flows.
 */
class OnboardingDialogUiTest {
    @get:Rule val compose = createComposeRule()

    private fun s(id: Int) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun skipOnWelcomeDeclines() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText(s(R.string.onboarding_welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onboarding_skip)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(false, finished)
    }

    @Test
    fun backOnWelcomeDeclines() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText(s(R.string.onboarding_welcome_title)).assertIsDisplayed()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(false, finished)
    }

    @Test
    fun backOnLaterPageCompletes() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText(s(R.string.onboarding_next)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(true, finished)
    }

    @Test
    fun nextThroughAllPagesCompletes() {
        var finished: Boolean? = null
        compose.setContent { MaterialTheme { OnboardingDialog(onFinished = { finished = it }) } }
        compose.onNodeWithText(s(R.string.onboarding_welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.onboarding_next)).assertIsDisplayed().performClick()
        // Remaining pages: advance with "Not now" (never the settings-opening buttons).
        repeat(3) {
            compose.waitForIdle()
            val notNow = compose.onAllNodesWithText(s(R.string.onboarding_not_now))
            if (notNow.fetchSemanticsNodes().isNotEmpty()) {
                notNow[0].performClick()
            }
        }
        compose.waitForIdle()
        assertEquals(true, finished)
    }
}
