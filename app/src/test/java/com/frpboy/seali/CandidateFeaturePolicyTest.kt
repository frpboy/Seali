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

    @Test fun `app updates available only on the generic release build`() {
        // generic release: the only qualified self-update channel
        assertTrue(
            CandidateFeaturePolicy.appUpdatesAvailableForChannel(flavor = "generic", isDebug = false)
        )
        // debug builds are signed differently and cannot install release artifacts
        assertFalse(
            CandidateFeaturePolicy.appUpdatesAvailableForChannel(flavor = "generic", isDebug = true)
        )
        // Play builds must update through Google Play; F-Droid builds through the F-Droid
        // client; preview builds carry a different application id than release artifacts
        for (flavor in listOf("play", "fdroid", "githubPreview", "fdroidPreview")) {
            assertFalse(
                "flavor $flavor must not self-update",
                CandidateFeaturePolicy.appUpdatesAvailableForChannel(flavor = flavor, isDebug = false),
            )
            assertFalse(
                CandidateFeaturePolicy.appUpdatesAvailableForChannel(flavor = flavor, isDebug = true)
            )
        }
    }

    @Test fun `update attempt fails explicitly on non-qualified channels`() {
        for ((flavor, isDebug) in
            listOf(
                "play" to false,
                "fdroid" to false,
                "githubPreview" to false,
                "fdroidPreview" to false,
                "generic" to true,
            )
        ) {
            try {
                CandidateFeaturePolicy.requireAppUpdatesAvailable(flavor = flavor, isDebug = isDebug)
                fail("Channel $flavor (debug=$isDebug) must not offer in-app self-update")
            } catch (e: IllegalStateException) {
                assertEquals(CandidateFeaturePolicy.UPDATE_UNAVAILABLE, e.message)
            }
        }
    }

    @Test fun `update gate passes on the generic release channel`() {
        CandidateFeaturePolicy.requireAppUpdatesAvailable(flavor = "generic", isDebug = false)
    }
}
