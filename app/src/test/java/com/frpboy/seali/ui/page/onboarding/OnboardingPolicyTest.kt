package com.frpboy.seali.ui.page.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPolicyTest {

    @Test
    fun `fresh install without completion shows onboarding`() {
        assertEquals(true, OnboardingPolicy.shouldShow(isFreshInstall = true, onboardingDone = false))
    }

    @Test
    fun `upgraded install never shows onboarding`() {
        // Existing users must not be forced through setup after an upgrade.
        assertEquals(
            false,
            OnboardingPolicy.shouldShow(isFreshInstall = false, onboardingDone = false),
        )
    }

    @Test
    fun `completed onboarding never shows again`() {
        assertEquals(false, OnboardingPolicy.shouldShow(isFreshInstall = true, onboardingDone = true))
        assertEquals(
            false,
            OnboardingPolicy.shouldShow(isFreshInstall = false, onboardingDone = true),
        )
    }
}
