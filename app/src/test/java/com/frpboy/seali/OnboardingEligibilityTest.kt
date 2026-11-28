package com.frpboy.seali

import com.frpboy.seali.ui.page.onboarding.OnboardingPage
import com.frpboy.seali.ui.page.onboarding.onboardingPages
import com.frpboy.seali.util.OnboardingEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingEligibilityTest {

    @Test
    fun `fresh install has equal install and update timestamps`() {
        assertTrue(OnboardingEligibility.isFreshInstall(1_700_000_000_000L, 1_700_000_000_000L))
    }

    @Test
    fun `fresh install within tolerance still counts`() {
        assertTrue(
            OnboardingEligibility.isFreshInstall(1_700_000_000_000L, 1_700_000_030_000L)
        )
    }

    @Test
    fun `upgraded install is not fresh`() {
        assertFalse(
            OnboardingEligibility.isFreshInstall(1_700_000_000_000L, 1_700_100_000_000L)
        )
    }

    @Test
    fun `persisted answer is never recomputed even if timestamps change later`() {
        // Eligible install later updates: stays eligible (its first launch was still fresh).
        assertEquals(
            OnboardingEligibility.ELIGIBLE,
            OnboardingEligibility.resolveEligibility(
                OnboardingEligibility.ELIGIBLE,
                firstInstallTimeMs = 1_700_000_000_000L,
                lastUpdateTimeMs = 1_700_100_000_000L,
            ),
        )
        // Existing install on a fresh-looking clock (e.g. wiped then reinstalled same day):
        // the stored answer still wins.
        assertEquals(
            OnboardingEligibility.NOT_ELIGIBLE,
            OnboardingEligibility.resolveEligibility(
                OnboardingEligibility.NOT_ELIGIBLE,
                firstInstallTimeMs = 1_700_000_000_000L,
                lastUpdateTimeMs = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun `unknown computes from timestamps once`() {
        assertEquals(
            OnboardingEligibility.ELIGIBLE,
            OnboardingEligibility.resolveEligibility(
                OnboardingEligibility.UNKNOWN,
                firstInstallTimeMs = 1_700_000_000_000L,
                lastUpdateTimeMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(
            OnboardingEligibility.NOT_ELIGIBLE,
            OnboardingEligibility.resolveEligibility(
                OnboardingEligibility.UNKNOWN,
                firstInstallTimeMs = 1_700_000_000_000L,
                lastUpdateTimeMs = 1_700_100_000_000L,
            ),
        )
    }

    @Test
    fun `auto show only for eligible installs that neither finished nor declined`() {
        fun show(done: Boolean, declined: Boolean, eligible: Int) =
            OnboardingEligibility.shouldAutoShow(done, declined, eligible)

        assertTrue(show(done = false, declined = false, eligible = OnboardingEligibility.ELIGIBLE))
        assertFalse(show(done = true, declined = false, eligible = OnboardingEligibility.ELIGIBLE))
        assertFalse(show(done = false, declined = true, eligible = OnboardingEligibility.ELIGIBLE))
        assertFalse(
            show(done = false, declined = false, eligible = OnboardingEligibility.NOT_ELIGIBLE)
        )
        assertFalse(show(done = false, declined = false, eligible = OnboardingEligibility.UNKNOWN))
        // Restored prefs from a backup carry done/declined across reinstalls.
        assertTrue(show(done = true, declined = false, eligible = OnboardingEligibility.ELIGIBLE).not())
    }

    @Test
    fun `restored eligibility answers are honored as persisted answers`() {
        // android:allowBackup is on and the backup rules do not exclude MMKV prefs, so
        // ONBOARDING_AUTO_SHOW_ELIGIBLE can be restored onto a new device. Chosen
        // semantics: a restored answer is honored exactly like a locally computed one
        // (resolveEligibility never recomputes a persisted value). A restored ELIGIBLE
        // with neither done nor declined still auto-shows on the new device; restored
        // done or declined suppresses it. Documented for review.
        assertTrue(
            OnboardingEligibility.shouldAutoShow(
                done = false,
                declined = false,
                eligible = OnboardingEligibility.resolveEligibility(
                    OnboardingEligibility.ELIGIBLE, // restored
                    firstInstallTimeMs = 2_000_000_000_000L,
                    lastUpdateTimeMs = 2_000_100_000_000L, // new device looks upgraded
                ),
            )
        )
        assertFalse(
            OnboardingEligibility.shouldAutoShow(
                done = true, // restored completion
                declined = false,
                eligible = OnboardingEligibility.ELIGIBLE,
            )
        )
        assertFalse(
            OnboardingEligibility.shouldAutoShow(
                done = false,
                declined = true, // restored decline
                eligible = OnboardingEligibility.ELIGIBLE,
            )
        )
    }

    @Test
    fun `play flavor on API 28 shows welcome and battery only`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.BATTERY),
            onboardingPages(sdkInt = 28, isGenericFlavor = false),
        )
    }

    @Test
    fun `play flavor on API 33 adds notifications but never storage`() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.NOTIFICATIONS, OnboardingPage.BATTERY),
            onboardingPages(sdkInt = 33, isGenericFlavor = false),
        )
        assertFalse(onboardingPages(34, false).contains(OnboardingPage.STORAGE_AND_INSTALL))
    }

    @Test
    fun `generic flavor adds storage page last`() {
        assertEquals(
            listOf(
                OnboardingPage.WELCOME,
                OnboardingPage.NOTIFICATIONS,
                OnboardingPage.BATTERY,
                OnboardingPage.STORAGE_AND_INSTALL,
            ),
            onboardingPages(sdkInt = 34, isGenericFlavor = true),
        )
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.BATTERY, OnboardingPage.STORAGE_AND_INSTALL),
            onboardingPages(sdkInt = 28, isGenericFlavor = true),
        )
    }
}
