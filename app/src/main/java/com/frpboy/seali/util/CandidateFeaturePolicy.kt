package com.frpboy.seali.util

import com.frpboy.seali.BuildConfig

/** Restricted source-built candidate only. Do not copy these gates to production silently. */
object CandidateFeaturePolicy {
    const val CUSTOM_COMMANDS_AVAILABLE = false
    const val APP_UPDATES_AVAILABLE = false
    const val UPDATE_UNAVAILABLE = "App self-update is unavailable in this source-built alpha candidate. Release selection, package, signing certificate and version-code compatibility are not qualified."

    fun appUpdatesEnabled(flavor: String): Boolean {
        val github = flavor == "generic" || flavor == "githubPreview"
        val actualGitHub = BuildConfig.FLAVOR == "generic" || BuildConfig.FLAVOR == "githubPreview"
        val qualification = BuildConfig.DEBUG && BuildConfig.QUALIFY_GITHUB_UPDATER
        return github && actualGitHub && (APP_UPDATES_AVAILABLE || qualification)
    }

    fun customCommandsEnabled(savedPreference: Boolean): Boolean =
        CUSTOM_COMMANDS_AVAILABLE && savedPreference

    fun requireAppUpdatesAvailable() {
        check(appUpdatesEnabled(BuildConfig.FLAVOR)) { UPDATE_UNAVAILABLE }
    }
}
