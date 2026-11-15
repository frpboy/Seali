package com.frpboy.seali

import com.frpboy.seali.util.CandidateFeaturePolicy
import org.junit.Assert.*
import org.junit.Test

class CandidateFeaturePolicyTest {
    @Test fun `source-limited candidate does not enable custom commands`() {
        assertFalse(CandidateFeaturePolicy.CUSTOM_COMMANDS_AVAILABLE)
    }

    @Test fun `saved custom true and false remain stored but cannot disable normal candidate downloads`() {
        for (saved in listOf(false, true)) {
            assertFalse(CandidateFeaturePolicy.customCommandsEnabled(saved))
        }
    }

    @Test fun `app updates available only on direct distribution channels`() {
        // generic / githubPreview: direct distribution, self-update qualified
        assertTrue(CandidateFeaturePolicy.appUpdatesAvailableForChannel(isPlay = false, isFDroid = false))
        // Play builds must update through Google Play
        assertFalse(CandidateFeaturePolicy.appUpdatesAvailableForChannel(isPlay = true, isFDroid = false))
        // F-Droid builds update through the F-Droid client
        assertFalse(CandidateFeaturePolicy.appUpdatesAvailableForChannel(isPlay = false, isFDroid = true))
        assertFalse(CandidateFeaturePolicy.appUpdatesAvailableForChannel(isPlay = true, isFDroid = true))
    }

    @Test fun `update attempt fails explicitly on store-owned channels`() {
        for ((isPlay, isFDroid) in listOf(true to false, false to true, true to true)) {
            try {
                CandidateFeaturePolicy.requireAppUpdatesAvailable(isPlay = isPlay, isFDroid = isFDroid)
                fail("Store-owned channels must not offer in-app self-update")
            } catch (e: IllegalStateException) {
                assertEquals(CandidateFeaturePolicy.UPDATE_UNAVAILABLE, e.message)
            }
        }
    }

    @Test fun `update gate passes on direct channel`() {
        CandidateFeaturePolicy.requireAppUpdatesAvailable(isPlay = false, isFDroid = false)
    }
}
