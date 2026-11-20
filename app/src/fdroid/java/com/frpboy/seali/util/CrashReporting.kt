package com.frpboy.seali.util

import android.content.Context

/**
 * F-Droid flavor: no crash-reporting SDK is compiled in. This keeps the
 * call sites in App.kt identical while nothing is initialised, captured
 * or sent. The reporting implementation lives in app/src/sentry and is
 * attached only to the non-F-Droid flavors in app/build.gradle.kts.
 */
object CrashReporting {
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun capture(throwable: Throwable) = Unit
}
