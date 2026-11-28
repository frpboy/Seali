package com.frpboy.seali.ui.page.onboarding

/** Pages of the first-launch tour, in order. */
enum class OnboardingPage {
    WELCOME,
    NOTIFICATIONS,
    BATTERY,
    STORAGE_AND_INSTALL,
}

/**
 * Pure page selection, kept free of Android types so unit tests cover it directly.
 *
 * Only permissions the flavor and Android version actually use appear, each after its
 * own rationale page. Normal install-time permissions (INTERNET, network state,
 * foreground service) are never shown as runtime prompts. The storage/install page
 * exists only in the generic flavor; the play flavor must not ask for them.
 */
fun onboardingPages(sdkInt: Int, isGenericFlavor: Boolean): List<OnboardingPage> = buildList {
    add(OnboardingPage.WELCOME)
    if (sdkInt >= 33) add(OnboardingPage.NOTIFICATIONS)
    add(OnboardingPage.BATTERY)
    if (isGenericFlavor) add(OnboardingPage.STORAGE_AND_INSTALL)
}
