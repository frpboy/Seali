package com.frpboy.seali.util

import android.content.Context
import com.frpboy.seali.BuildConfig
import io.sentry.AsyncHttpTransportFactory
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.JsonSerializer
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryId
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Contexts
import io.sentry.transport.ITransport
import java.util.Date

/**
 * Minimal automatic crash/error reporting. Error and crash capture only:
 * no performance tracing, profiling, logs, session tracking, screenshots,
 * view hierarchy or breadcrumbs.
 *
 * Boundary: nothing the SDK captured is forwarded. Every captured event,
 * hint and envelope item is discarded and replaced by ONE fixed generic
 * event built only from trusted build constants plus a fresh random id
 * and timestamp ([buildGenericEvent]). The transport wrapper
 * ([FixedEventTransport]) rebuilds the final envelope the same way at
 * the last boundary, so anything added after beforeSend - attachments,
 * extra items, integrations - is dropped as well. There is no content
 * scanning or charset filtering: the input is never read.
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
                SentryOptions.BeforeSendCallback { _, _ -> buildGenericEvent() }
            val inner = AsyncHttpTransportFactory()
            options.setTransportFactory(ITransportFactory { opts, details ->
                FixedEventTransport(opts, inner.create(opts, details))
            })
        }
        enabled = true
    }

    fun capture(throwable: Throwable) {
        if (enabled) Sentry.captureException(throwable)
    }

    /**
     * The only payload ever sent: a fixed generic event built from
     * trusted build constants (version name/code from BuildConfig,
     * constant level/logger/environment) with a fresh random id and
     * current timestamp. No field, hint, attachment, frame, thread,
     * context, tag, extra or any other value from the captured input is
     * read or carried over.
     */
    fun buildGenericEvent(): SentryEvent {
        val out = SentryEvent(SentryId(), Date())
        out.level = SentryLevel.ERROR
        out.logger = "app"
        out.release = BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE
        out.dist = BuildConfig.VERSION_CODE.toString()
        out.environment = "production"
        out.contexts = Contexts()
        return out
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
 * Final boundary: whatever envelope the SDK built is discarded and
 * replaced by one envelope containing exactly one item - the fixed
 * generic event - under a fresh header. The incoming envelope, hint and
 * item headers are never forwarded.
 */
internal class FixedEventTransport(
    private val options: SentryOptions,
    private val inner: ITransport,
) : ITransport {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        val event = CrashReporting.buildGenericEvent()
        val serializer = options.serializer ?: JsonSerializer(options)
        val item = SentryEnvelopeItem.fromEvent(serializer, event)
        inner.send(SentryEnvelope(SentryEnvelopeHeader(event.eventId), listOf(item)), Hint())
    }

    override fun isHealthy(): Boolean = inner.isHealthy

    override fun flush(timeoutMillis: Long) = inner.flush(timeoutMillis)

    override fun close() = inner.close()
}
