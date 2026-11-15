package com.frpboy.seali.util

import android.content.Context
import com.frpboy.seali.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace

/**
 * Minimal automatic crash/error reporting. Error and crash capture only:
 * no performance tracing, profiling, logs, session tracking, screenshots,
 * view hierarchy or breadcrumbs. Every event passes the single egress
 * boundary [SentryEventSanitizer], which structurally rebuilds the event
 * from known-safe parts instead of redacting strings.
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
            options.applyPrivacyDefaults()
            // Android-specific capture sources, off at the source.
            options.isEnableUserInteractionBreadcrumbs = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableAppLifecycleBreadcrumbs = false
            options.isEnableActivityLifecycleBreadcrumbs = false
            options.isEnableSystemEventBreadcrumbs = false
            options.isEnableAppComponentBreadcrumbs = false
            options.beforeSend =
                SentryOptions.BeforeSendCallback { event, _ -> SentryEventSanitizer.apply(event) }
        }
        enabled = true
    }

    fun capture(throwable: Throwable) {
        if (enabled) Sentry.captureException(throwable)
    }
}

/** Base-SDK privacy defaults, separated so unit tests can run them on the JVM. */
internal fun SentryOptions.applyPrivacyDefaults() {
    tracesSampleRate = null
    profilesSampleRate = null
    logs.isEnabled = false
    isEnableAutoSessionTracking = false
    isAttachStacktrace = false
    isAttachThreads = false
    sendDefaultPii = false
}

/**
 * Egress boundary: minimal generic reconstruction, not string redaction.
 * Anything that can carry user text or identifiers is dropped by
 * structure, never pattern-matched: exception values, messages,
 * transaction names, breadcrumbs, request data, user, server name,
 * extras, tags, local variables and absolute paths. Kept: exception
 * types, mechanism, source-file-relative frame info (module, function,
 * filename, line), level, release, and allowlisted app/os/device/runtime
 * fields.
 */
internal object SentryEventSanitizer {
    fun apply(event: SentryEvent): SentryEvent {
        event.user = null
        event.request = null
        event.breadcrumbs = null
        event.serverName = null
        event.message = null
        event.transaction = null
        event.tags = null
        event.extras?.clear()
        event.exceptions = event.exceptions?.map { reconstructException(it) }
        val contexts = event.contexts
        contexts.remove("response")
        contexts.remove("trace")
        contexts.operatingSystem?.let { os ->
            contexts.setOperatingSystem(
                OperatingSystem().apply {
                    name = os.name
                    version = os.version
                }
            )
        }
        contexts.device?.let { device ->
            contexts.setDevice(
                Device().apply {
                    model = device.model
                    brand = device.brand
                    manufacturer = device.manufacturer
                }
            )
        }
        contexts.app?.let { app ->
            contexts.setApp(
                App().apply {
                    appIdentifier = app.appIdentifier
                    appVersion = app.appVersion
                    appBuild = app.appBuild
                }
            )
        }
        contexts.runtime?.let { runtime ->
            contexts.setRuntime(
                SentryRuntime().apply {
                    name = runtime.name
                    version = runtime.version
                }
            )
        }
        return event
    }

    private fun reconstructException(source: SentryException): SentryException {
        val out = SentryException()
        out.type = source.type
        out.module = source.module
        // value is free text and can embed URLs, file paths and tokens: dropped.
        out.mechanism =
            source.mechanism?.let { mech ->
                Mechanism().apply {
                    type = mech.type
                    isHandled = mech.isHandled
                }
            }
        out.stacktrace =
            source.stacktrace?.let { trace ->
                SentryStackTrace(
                    trace.frames?.map { frame ->
                        SentryStackFrame().apply {
                            module = frame.module
                            function = frame.function
                            filename = frame.filename
                            lineno = frame.lineno
                            isInApp = frame.isInApp
                            // absPath, vars, context lines and native addresses dropped.
                        }
                    }
                )
            }
        return out
    }
}
