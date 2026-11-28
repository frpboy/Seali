package com.frpboy.seali.util

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.frpboy.seali.ui.page.onboarding.OnboardingResult

/** "Get started" was pressed and the tour ran to its last page. Means the user walked
 * through the guide; never means any permission was granted. */
const val ONBOARDING_DONE = "onboarding_done"

/** "Skip tour" was pressed on page 1. Distinct from completion and interruption. */
const val ONBOARDING_DECLINED = "onboarding_declined"

/** The tour was left via Back. Also suppresses auto-show, without mislabeling the
 * leave as a decline or a completion. */
const val ONBOARDING_INTERRUPTED = "onboarding_interrupted"

/** -1 unknown (never computed), 0 not eligible, 1 eligible. Computed once and persisted
 * so later app updates never change an install's answer. */
const val ONBOARDING_AUTO_SHOW_ELIGIBLE = "onboarding_auto_show_eligible"

/**
 * Fresh-install vs upgrade detection for the first-launch tour.
 *
 * Grounded in PackageManager install evidence, not user-state heuristics:
 * PackageInfo.firstInstallTime is the time the app was first installed and never
 * changes across updates; PackageInfo.lastUpdateTime changes on every update.
 * An install whose lastUpdateTime is (within tolerance of) its firstInstallTime
 * has never been updated, so this launch is its first launch.
 */
object OnboardingEligibility {
    const val UNKNOWN = -1
    const val NOT_ELIGIBLE = 0
    const val ELIGIBLE = 1

    /** Clock slack between install and update timestamps on a fresh install. */
    const val FRESH_INSTALL_TOLERANCE_MS = 60_000L

    fun isFreshInstall(
        firstInstallTimeMs: Long,
        lastUpdateTimeMs: Long,
        toleranceMs: Long = FRESH_INSTALL_TOLERANCE_MS,
    ): Boolean = lastUpdateTimeMs - firstInstallTimeMs <= toleranceMs

    /** Returns the persisted answer when present; only computes (for later persistence)
     * when it has never been computed. Never recomputes a stored answer. */
    fun resolveEligibility(persisted: Int, firstInstallTimeMs: Long, lastUpdateTimeMs: Long): Int =
        when {
            persisted == ELIGIBLE || persisted == NOT_ELIGIBLE -> persisted
            isFreshInstall(firstInstallTimeMs, lastUpdateTimeMs) -> ELIGIBLE
            else -> NOT_ELIGIBLE
        }

    fun shouldAutoShow(done: Boolean, declined: Boolean, interrupted: Boolean, eligible: Int): Boolean =
        eligible == ELIGIBLE && !done && !declined && !interrupted
}

/** Android-glue around the pure eligibility rules.
 *
 * Persisted state (MMKV, restored by Android Auto Backup on a new device and honored
 * exactly like a locally written value):
 *   outcome DONE        -> ONBOARDING_DONE        -> never auto-shows again
 *   outcome SKIPPED     -> ONBOARDING_DECLINED    -> never auto-shows again
 *   outcome INTERRUPTED -> ONBOARDING_INTERRUPTED -> never auto-shows again
 *   no outcome          -> (all false)            -> auto-shows only when ELIGIBLE
 * ONBOARDING_AUTO_SHOW_ELIGIBLE: -1 unknown, 0 no, 1 yes; computed once, never recomputed.
 */
object OnboardingController {

    /** Set by Settings "Replay app tour"; observed and cleared by AppEntry. Replay never
     * rewrites DONE/DECLINED except that finishing a replay marks DONE (the user has now
     * seen the whole tour). */
    val replayRequested = mutableStateOf(false)

    fun shouldAutoShow(context: Context): Boolean =
        OnboardingEligibility.shouldAutoShow(
            done = ONBOARDING_DONE.getBoolean(),
            declined = ONBOARDING_DECLINED.getBoolean(),
            interrupted = ONBOARDING_INTERRUPTED.getBoolean(),
            eligible = resolveAndPersistEligibility(context),
        )

    private fun resolveAndPersistEligibility(context: Context): Int {
        val persisted = ONBOARDING_AUTO_SHOW_ELIGIBLE.getInt()
        if (persisted == OnboardingEligibility.ELIGIBLE ||
            persisted == OnboardingEligibility.NOT_ELIGIBLE
        ) {
            return persisted
        }
        val info =
            runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                .getOrNull()
                ?: return OnboardingEligibility.NOT_ELIGIBLE
        val resolved =
            OnboardingEligibility.resolveEligibility(
                persisted = persisted,
                firstInstallTimeMs = info.firstInstallTime,
                lastUpdateTimeMs = info.lastUpdateTime,
            )
        ONBOARDING_AUTO_SHOW_ELIGIBLE.updateInt(resolved)
        return resolved
    }

    fun finish(result: OnboardingResult) {
        when (result) {
            OnboardingResult.DONE -> ONBOARDING_DONE.updateBoolean(true)
            OnboardingResult.SKIPPED -> ONBOARDING_DECLINED.updateBoolean(true)
            OnboardingResult.INTERRUPTED -> ONBOARDING_INTERRUPTED.updateBoolean(true)
        }
    }
}
