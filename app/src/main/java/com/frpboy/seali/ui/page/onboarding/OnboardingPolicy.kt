package com.frpboy.seali.ui.page.onboarding

object OnboardingPolicy {
    /**
     * Onboarding is a first-install experience only. A fresh install has never been
     * updated, so firstInstallTime == lastUpdateTime. Upgraded installs belong to
     * existing users and must never be forced through setup again.
     */
    fun shouldShow(isFreshInstall: Boolean, onboardingDone: Boolean): Boolean =
        isFreshInstall && !onboardingDone
}
