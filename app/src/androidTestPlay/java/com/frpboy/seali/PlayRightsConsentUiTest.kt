package com.frpboy.seali

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.ui.page.downloadv2.configure.ActionButtons
import com.frpboy.seali.util.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Play flavor only (androidTestPlay): runs the real ActionButtons composable that the V2 download
 * dialog uses, with the approved checkbox sentence and one-checkbox-per-open-dialog semantics.
 * It does not drive the whole dialog or start a download; it checks the consent control itself:
 * wording, checkbox role/state (the TalkBack-relevant semantics), the gate on the action button,
 * and the reset when the dialog is reopened.
 */
class PlayRightsConsentUiTest {
    @get:Rule val compose = createComposeRule()

    private val sentence =
        "I have the right to copy every item selected here because I own it, have permission, or its licence or public-domain status permits copying."

    private var downloads = 0
    private var fetches = 0
    private var open by mutableStateOf(true)

    private fun show(type: DownloadType?, formatSelection: Boolean = false, canProceed: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                if (open) {
                    ActionButtons(
                        canProceed = canProceed,
                        selectedType = type,
                        useFormatSelection = formatSelection,
                        onCancel = {},
                        onFetchInfo = { fetches++ },
                        onDownload = { downloads++ },
                        onTaskStart = {},
                    )
                }
            }
        }
    }

    private fun checkbox() =
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        )

    private fun actionNode(label: String) = compose.onNode(hasClickAction() and hasText(label))

    @Test
    fun wordingMatchesApprovedSentenceAndIsOneCheckboxLabel() {
        val strings = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.rights_confirmation)
        assertEquals(sentence, strings)
        show(DownloadType.Video)
        compose.onNodeWithText(sentence).assertIsDisplayed()
        // Exactly one checkbox-role node; the text is merged into it (single TalkBack label).
        compose.onAllNodesWithText(sentence).assertCountEquals(1)
        checkbox().assertIsOff().assert(hasText(sentence))
    }

    @Test
    fun downloadStaysDisabledUntilChecked_thenFiresOnce() {
        show(DownloadType.Video)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.download)
        actionNode(label).assertIsNotEnabled()
        checkbox().performClick().assertIsOn()
        actionNode(label).assertIsEnabled().performClick()
        assertEquals(1, downloads)
        assertEquals(0, fetches)
    }

    @Test
    fun playlistOrFormatChoiceUsesTheSameSingleCheckboxBeforeFetch() {
        show(DownloadType.Playlist)
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.proceed)
        actionNode(label).assertIsNotEnabled()
        checkbox().performClick()
        actionNode(label).assertIsEnabled().performClick()
        assertEquals(1, fetches)
        assertEquals(0, downloads)
    }

    @Test
    fun checkedStateResetsWhenTheDialogIsReopened() {
        show(DownloadType.Video)
        checkbox().performClick().assertIsOn()
        open = false
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        checkbox().assertIsOff()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.download)
        actionNode(label).assertIsNotEnabled()
    }

    @Test
    fun checkedBoxDoesNotEnableActionWhenCanProceedIsFalse() {
        show(DownloadType.Video, canProceed = false)
        checkbox().performClick().assertIsOn()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.download)
        actionNode(label).assertIsNotEnabled()
    }
}
