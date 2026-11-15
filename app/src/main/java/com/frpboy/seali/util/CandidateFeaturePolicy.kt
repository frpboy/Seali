package com.frpboy.seali.util

/** Restricted source-built candidate only. Do not copy these gates to production silently. */
object CandidateFeaturePolicy {
    const val CUSTOM_COMMANDS_AVAILABLE = false
    const val UPDATE_UNAVAILABLE =
        "App self-update is disabled for this distribution channel. Play builds update through Google Play and F-Droid builds through the F-Droid client; preview and debug builds are not update targets."

    fun customCommandsEnabled(savedPreference: Boolean): Boolean =
        CUSTOM_COMMANDS_AVAILABLE && savedPreference

    /**
     * App self-update is qualified only for the generic direct-distribution release
     * build: the GitHub Releases lookup, ABI-matched asset selection, version
     * comparison, and package trust checks in [UpdateUtil] apply there. Play builds
     * must update through Google Play and F-Droid builds through the F-Droid client;
     * preview builds carry a different application id than release artifacts and debug
     * builds are signed differently, so none of them are update targets.
     */
    fun appUpdatesAvailableForChannel(flavor: String, isDebug: Boolean): Boolean =
        flavor == "generic" && !isDebug

    fun requireAppUpdatesAvailable(flavor: String, isDebug: Boolean) {
        check(appUpdatesAvailableForChannel(flavor = flavor, isDebug = isDebug)) {
            UPDATE_UNAVAILABLE
        }
    }
}
