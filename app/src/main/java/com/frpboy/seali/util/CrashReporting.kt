package com.frpboy.seali.util

import android.content.Context
import com.frpboy.seali.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid

/**
 * Minimal automatic crash/error reporting. Error and crash capture only:
 * no performance tracing, profiling, logs, session tracking, screenshots,
 * view hierarchy or user-interaction breadcrumbs. The single egress
 * boundary is [SentryEventSanitizer], which every event passes through.
 *
 * Java/Kotlin crashes only: native (C/C++) crashes are NOT captured
 * because the NDK artifact is deliberately not included, keeping the APK
 * native library set byte-identical to the verified set. That gap is
 * labeled, not silently shipped.
 */
object CrashReporting {
    @Volatile private var enabled = false

    fun init(context: Context) {
        val dsn = BuildConfig.SEALI_SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.isDebug = false
            options.tracesSampleRate = null
            options.profilesSampleRate = null
            options.logs.isEnabled = false
            options.isEnableAutoSessionTracking = false
            options.sendDefaultPii = false
            options.isEnableUserInteractionBreadcrumbs = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.beforeSend =
                SentryOptions.BeforeSendCallback { event, _ -> SentryEventSanitizer.apply(event) }
        }
        enabled = true
    }

    fun capture(throwable: Throwable) {
        if (enabled) Sentry.captureException(throwable)
    }
}

/**
 * Allowlist egress filter. Keeps exception, stack trace, tags and the
 * app/device/os/runtime contexts. Strips anything that can carry user
 * text or identifiers: user, request (URLs, cookies), all breadcrumbs,
 * response context and server name.
 */
internal object SentryEventSanitizer {
    fun apply(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.breadcrumbs = null
        event.serverName = null
        event.contexts.remove("response")
        return event
    }
}
