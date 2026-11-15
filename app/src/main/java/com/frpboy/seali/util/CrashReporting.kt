package com.frpboy.seali.util

import android.content.Context
import com.frpboy.seali.BuildConfig
import io.sentry.AsyncHttpTransportFactory
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEvent
import io.sentry.SentryId
import io.sentry.SentryItemType
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
import io.sentry.transport.ITransport
import java.util.Date

/**
 * Minimal automatic crash/error reporting. Error and crash capture only:
 * no performance tracing, profiling, logs, session tracking, screenshots,
 * view hierarchy or breadcrumbs. Every captured event is replaced by a
 * FRESH generic event built from constants and allowlisted fields only
 * ([SentryEventSanitizer]); the transport wrapper drops every envelope
 * item that is not the single event item (attachments can never leave).
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
            val inner = AsyncHttpTransportFactory()
            options.setTransportFactory(ITransportFactory { opts, details ->
                EventOnlyTransport(inner.create(opts, details))
            })
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
    isSendDefaultPii = false
}

/**
 * Final-envelope guard: forwards only the single event item of each
 * envelope under a fresh header. Attachments, replays, profiles or any
 * other item type are dropped here even if something upstream adds them.
 */
internal class EventOnlyTransport(private val inner: ITransport) : ITransport {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        val eventItems =
            envelope.items.filter { it.header.type == SentryItemType.Event }
        if (eventItems.isEmpty()) return
        val eventId = envelope.header.eventId ?: SentryId()
        inner.send(SentryEnvelope(SentryEnvelopeHeader(eventId), eventItems), hint)
    }

    override fun isHealthy(): Boolean = inner.isHealthy

    override fun flush(timeoutMillis: Long) = inner.flush(timeoutMillis)

    override fun close() = inner.close()
}

/**
 * Egress boundary: instead of scrubbing the captured event, build a
 * FRESH generic event and copy only qualified fields. Everything not
 * explicitly copied - user, request, breadcrumbs, message, transaction,
 * tags, extras, threads, server name, debug meta, unknown fields -
 * cannot leak because it never enters the new event. Kept fields pass a
 * structural guard: a value containing transport, path, query or
 * identity characters cannot be a plain code or device identifier, so
 * the field is dropped. Charset constraint, not content matching.
 */
internal object SentryEventSanitizer {
    private const val APP_PACKAGE_PREFIX = "com.frpboy.seali"

    private fun String?.structural(maxLength: Int = 128, allowSpace: Boolean = false): String? {
        if (this == null || this.length > maxLength) return null
        if (any { it == '/' || it == '\\' || it == '@' || it == '?' || it == '=' || it == '&' || it == '%' }) return null
        if (!allowSpace && any { it.isWhitespace() }) return null
        return this
    }

    fun apply(source: SentryEvent): SentryEvent {
        val out = SentryEvent(SentryId(), Date())
        out.level = source.level
        out.logger = source.logger.structural(64)
        // Trusted build metadata only.
        out.release = source.release.structural()
        out.dist = source.dist.structural(64)
        out.environment = source.environment.structural(64)
        out.exceptions = source.exceptions?.map { reconstructException(it) }
        val contexts = out.contexts
        source.contexts.operatingSystem?.let { os ->
            contexts.setOperatingSystem(
                OperatingSystem().apply {
                    name = os.name.structural(64, allowSpace = true)
                    version = os.version.structural(64)
                }
            )
        }
        source.contexts.device?.let { device ->
            contexts.setDevice(
                Device().apply {
                    model = device.model.structural(64, allowSpace = true)
                    brand = device.brand.structural(64, allowSpace = true)
                    manufacturer = device.manufacturer.structural(64, allowSpace = true)
                }
            )
        }
        source.contexts.app?.let { app ->
            contexts.setApp(
                App().apply {
                    appIdentifier = app.appIdentifier.structural(128)
                    appVersion = app.appVersion.structural(64)
                    appBuild = app.appBuild.structural(64)
                }
            )
        }
        source.contexts.runtime?.let { runtime ->
            contexts.setRuntime(
                SentryRuntime().apply {
                    name = runtime.name.structural(64, allowSpace = true)
                    version = runtime.version.structural(64)
                }
            )
        }
        return out
    }

    private fun reconstructException(source: SentryException): SentryException {
        val out = SentryException()
        out.type = source.type.structural()
        out.module = source.module.structural()
        // value is free text and can embed URLs, file paths and tokens: dropped.
        out.mechanism =
            source.mechanism?.let { mech ->
                Mechanism().apply {
                    type = mech.type.structural()
                    isHandled = mech.isHandled
                }
            }
        out.stacktrace =
            source.stacktrace?.let { trace ->
                SentryStackTrace(
                    trace.frames?.map { frame -> reconstructFrame(frame) }
                )
            }
        return out
    }

    /**
     * Frame detail (module, function, filename, line) is kept only for
     * frames inside the app package - those are compile-time identifiers
     * of our own source. Foreign frames keep only the in-app flag.
     * absPath, vars, context lines and native addresses are never copied.
     */
    private fun reconstructFrame(frame: SentryStackFrame): SentryStackFrame {
        val out = SentryStackFrame()
        out.isInApp = frame.isInApp
        val module = frame.module
        if (module != null && module.startsWith(APP_PACKAGE_PREFIX)) {
            out.module = module.structural()
            out.function = frame.function.structural()
            out.filename = frame.filename.structural()
            out.lineno = frame.lineno
        }
        return out
    }
}
