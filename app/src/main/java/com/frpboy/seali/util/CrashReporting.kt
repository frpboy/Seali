package com.frpboy.seali.util

import android.content.Context
import com.frpboy.seali.BuildConfig
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.JsonSerializer
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Contexts
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryId
import io.sentry.transport.ITransport
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal automatic crash/error reporting. Error and crash capture only:
 * no performance tracing, profiling, logs, session tracking, screenshots,
 * view hierarchy or breadcrumbs.
 *
 * Boundary: nothing the SDK captured is forwarded. Every captured event,
 * hint and envelope item is discarded; when (and only when) the SDK
 * submits an envelope that carries an error/crash event item, one fixed
 * generic event built from trusted build constants plus a fresh id and
 * timestamp ([buildGenericEvent]) is sent instead. Envelopes without an
 * event item (session, client-report, transaction, log-only, empty) are
 * dropped and never converted into an error. There is no content
 * scanning: the input is never read.
 *
 * Transport: a bounded explicit direct transport ([FixedEventTransport]),
 * not the SDK's async wrapper - no background queue, no client-report
 * injection, no retry machinery. At most one send per minute and one in
 * flight; the busy flag is held across the actual blocking HTTP POST,
 * with connect/read deadlines, then released. Failures are dropped, not
 * queued or retried.
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
            options.setTransportFactory(ITransportFactory { opts, _ ->
                FixedEventTransport(opts)
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
     * constant level/logger/environment, fixed generic error content)
     * with a fresh random id and current timestamp. No field, hint,
     * attachment, frame, thread, context, tag, extra or any other value
     * from the captured input is read or carried over.
     */
    fun buildGenericEvent(): SentryEvent {
        val out = SentryEvent(SentryId(), Date())
        out.level = SentryLevel.ERROR
        out.logger = "app"
        out.message = Message().apply { message = "app error - details withheld by privacy boundary" }
        out.exceptions = listOf(SentryException().apply { type = "AppError" })
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
 * Bounded explicit direct transport: whatever envelope the SDK built is
 * discarded; when it carried an event item, exactly one envelope - one
 * fixed generic event item under a fresh header - is POSTed to the DSN
 * endpoint with a blocking, deadline-bounded connection. The busy flag
 * spans the actual I/O. No SDK async wrapper, queue, client-report
 * attachment or retry is involved. [poster] is injectable so tests can
 * capture the exact final wire bytes without network.
 */
internal class FixedEventTransport(
    private val options: SentryOptions,
    private val poster: (ByteArray) -> Unit = { bytes -> defaultPost(options.dsn, bytes) },
) : ITransport {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        // Gate: only an envelope carrying an error/crash event item is
        // worth reporting.
        if (envelope.items.none { it.header.type == SentryItemType.Event }) return
        // Local rate cap: at most one send per minute, shared across
        // instances; a burst is dropped, not queued.
        val now = System.currentTimeMillis()
        if (now - lastSendAt < MIN_INTERVAL_MS) return
        // Sanitize BEFORE handoff: only the fixed generic event's bytes
        // ever reach the worker - the raw envelope stays on the caller.
        val event = CrashReporting.buildGenericEvent()
        val serializer = options.serializer ?: JsonSerializer(options)
        val out =
            SentryEnvelope(
                SentryEnvelopeHeader(event.eventId),
                listOf(SentryEnvelopeItem.fromEvent(serializer, event)),
            )
        val bytes = ByteArrayOutputStream()
        serializer.serialize(out, bytes)
        // Single bounded worker: the blocking POST never runs on the
        // capture caller (which can be the Android main thread). One send
        // in flight; busy drops instead of queueing, and the flag spans
        // the actual I/O on the worker.
        if (!inFlight.compareAndSet(false, true)) return
        lastSendAt = now
        worker.execute {
            try {
                poster(bytes.toByteArray())
            } finally {
                inFlight.set(false)
            }
        }
    }

    override fun isHealthy(): Boolean = true

    override fun flush(timeoutMillis: Long) {}

    override fun close() {}

    companion object {
        private const val MIN_INTERVAL_MS = 60_000L
        private const val TIMEOUT_MS = 10_000
        @Volatile private var lastSendAt = 0L
        private val inFlight = AtomicBoolean(false)
        private val worker =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "seali-sentry-sender").apply { isDaemon = true }
            }

        /**
         * Blocking POST of the final envelope bytes to the exact DSN
         * endpoint: HTTPS only, host/port taken verbatim from the DSN,
         * redirects disabled, connect/read deadlines, no cookies. Runs
         * only on the worker thread.
         */
        internal fun defaultPost(dsn: String, bytes: ByteArray) {
            try {
                val uri = URI(dsn)
                require(uri.scheme == "https") { "DSN must be https" }
                val host = uri.host ?: throw IllegalArgumentException("DSN has no host")
                val key = uri.userInfo ?: throw IllegalArgumentException("DSN has no key")
                val port = if (uri.port > 0) ":${uri.port}" else ""
                val endpoint = "https://$host$port/api${uri.path}/envelope/"
                val conn = URI(endpoint).toURL().openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-sentry-envelope")
                    conn.setRequestProperty(
                        "X-Sentry-Auth",
                        "Sentry sentry_version=7, sentry_client=seali-boundary/1, sentry_key=$key",
                    )
                    conn.outputStream.use { it.write(bytes) }
                    conn.responseCode // force completion of the request
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Reporting must never crash the app or retry: drop.
            }
        }
    }
}
