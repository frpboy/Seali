package com.frpboy.seali.util

/** Restricted source-built candidate only. Do not copy these gates to production silently. */
object CandidateFeaturePolicy {
    const val CUSTOM_COMMANDS_AVAILABLE = false
    const val UPDATE_UNAVAILABLE =
        "App self-update is disabled for this distribution channel. Play builds update through Google Play and F-Droid builds through the F-Droid client."

    fun customCommandsEnabled(savedPreference: Boolean): Boolean =
        CUSTOM_COMMANDS_AVAILABLE && savedPreference

    /**
     * App self-update is qualified only for direct-distribution builds (generic /
     * githubPreview): the GitHub Releases lookup, ABI-matched asset selection, version
     * comparison, and signature-continuity check in [UpdateUtil] apply there. Play
     * builds must update through Google Play and F-Droid builds through the F-Droid
     * client, so the updater stays disabled on those channels.
     */
    fun appUpdatesAvailableForChannel(isPlay: Boolean, isFDroid: Boolean): Boolean =
        !isPlay && !isFDroid

    fun requireAppUpdatesAvailable(isPlay: Boolean, isFDroid: Boolean) {
        check(appUpdatesAvailableForChannel(isPlay = isPlay, isFDroid = isFDroid)) {
            UPDATE_UNAVAILABLE
        }
    }
}
