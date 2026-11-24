package com.frpboy.seali.util

/** Restricted source-built candidate only. Do not copy these gates to production silently. */
object CandidateFeaturePolicy {
    const val CUSTOM_COMMANDS_AVAILABLE = false
    const val APP_UPDATES_AVAILABLE = false
    const val UPDATE_UNAVAILABLE = "App self-update is unavailable in this source-built alpha candidate. Release selection, package, signing certificate and version-code compatibility are not qualified."

    fun appUpdatesEnabled(flavor: String): Boolean =
        APP_UPDATES_AVAILABLE && (flavor == "generic" || flavor == "githubPreview")

    fun customCommandsEnabled(savedPreference: Boolean): Boolean =
        CUSTOM_COMMANDS_AVAILABLE && savedPreference

    fun requireAppUpdatesAvailable() {
        check(APP_UPDATES_AVAILABLE) { UPDATE_UNAVAILABLE }
    }
}
