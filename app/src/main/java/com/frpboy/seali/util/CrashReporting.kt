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
import io.sentry.hints.DiskFlushNotification
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import io.sentry.util.HintUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.RejectedExecutionException
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
        // Fresh random event id and timestamp come from the public no-arg
        // constructor (verified in sentry-core 8.64.0).
        val out = SentryEvent()
        out.level = SentryLevel.ERROR
        out.logger = "app"
        out.message = Message().apply { message = "app error - details withheld by privacy boundary" }
        out.exceptions = listOf(SentryException().apply { type = "AppError" })
        out.release = BuildConfig.VERSION_NAME + "+" + BuildConfig.VERSION_CODE
        out.dist = BuildConfig.VERSION_CODE.toString()
        out.environment = "production"
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
    private val poster: (ByteArray) -> Boolean = { bytes ->
        // No DSN configured means nothing to send; the report is dropped.
        options.dsn?.let { dsn -> defaultPost(dsn, bytes) } ?: false
    },
) : ITransport {
    override fun send(envelope: SentryEnvelope, hint: Hint) {
        // The SDK's uncaught-exception integration attaches a flush latch
        // (DiskFlushNotification) and blocks the crashing thread on
        // waitFlush() after capture returns. It is released ONLY by
        // markFlushed() after actual delivery - never on a drop, so a
        // suppressed or failed report is logged as failed, not delivered.
        val flushNotification =
            HintUtils.getSentrySdkHint(hint) as? DiskFlushNotification
        // Gate: only an envelope carrying an error/crash event item is
        // worth reporting.
        if (envelope.items.none { it.header.type == SentryItemType.Event }) return
        // Local rate cap: at most one send per minute and no send while a
        // server 429/Retry-After window is open, shared across instances;
        // a burst is dropped, not queued. Fast path only - the authoritative
        // check runs again after the CAS below, so a stale contender that
        // passed this read before another send updated the timestamp is
        // still rejected.
        val now = System.currentTimeMillis()
        if (now - lastSendAt < MIN_INTERVAL_MS || now < rateLimitedUntilMs) return
        // Sanitize BEFORE handoff: only the fixed generic event's bytes
        // ever reach the network path - the raw envelope stays on the
        // caller. The flush latch reference also crosses to the worker,
        // for lifecycle signaling only; it is never read or sent.
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
        // Re-check the rate window after winning the CAS: lastSendAt may
        // have been updated by a concurrent send between the fast-path
        // read and here.
        val won = System.currentTimeMillis()
        if (won - lastSendAt < MIN_INTERVAL_MS || won < rateLimitedUntilMs) {
            inFlight.set(false)
            return
        }
        lastSendAt = won
        try {
            worker.execute {
                val delivered = runCatching { poster(bytes.toByteArray()) }.getOrDefault(false)
                // Release the SDK crash flush latch only after the server
                // accepted the envelope (2xx at the HTTP layer). This is
                // not a server-side storage receipt: a swallowed or
                // truncated response body still counts as accepted, which
                // matches the SDK's own transport semantics. A failed or
                // dropped send stays unflushed, so the SDK waitFlush logs
                // an honest failure instead of a false delivery.
                if (delivered) runCatching { flushNotification?.markFlushed() }
                inFlight.set(false)
                synchronized(idleLock) { idleLock.notifyAll() }
            }
        } catch (_: RejectedExecutionException) {
            // Worker gone (process shutting down): release the flag and drop.
            inFlight.set(false)
        }
    }

    override fun isHealthy(): Boolean = true

    // Rate limiting is enforced inside send() (one send per minute plus the
    // server 429/Retry-After window), so no SDK RateLimiter is exposed.
    override fun getRateLimiter(): RateLimiter? = null

    /** Waits, up to [timeoutMillis], for any in-flight POST to finish. */
    override fun flush(timeoutMillis: Long) {
        waitForIdle(timeoutMillis)
    }

    override fun close(isRestarting: Boolean) {
        // Drain only. The worker is a daemon retained for the process
        // lifetime: the transport is stateless per call, and permanently
        // shutting a static executor would kill later init/send cycles.
        waitForIdle(TIMEOUT_MS.toLong())
    }

    // ITransport extends java.io.Closeable: both close signatures exist.
    override fun close() {
        waitForIdle(TIMEOUT_MS.toLong())
    }

    /** Blocks the caller until no POST is in flight or the deadline passes. */
    private fun waitForIdle(timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        synchronized(idleLock) {
            while (inFlight.get()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return
                runCatching { idleLock.wait(minOf(remaining, 100L)) }
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 60_000L
        private const val TIMEOUT_MS = 10_000
        private const val MAX_BACKOFF_SECONDS = 3_600L
        private const val MAX_DRAIN_BYTES = 8_192
        @Volatile private var lastSendAt = 0L
        @Volatile private var rateLimitedUntilMs = 0L
        private val inFlight = AtomicBoolean(false)
        private val idleLock = Object()
        private val worker =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "seali-sentry-sender").apply { isDaemon = true }
            }

        /** Test-only reset of the shared send state. Call only while idle. */
        internal fun resetForTests() {
            lastSendAt = 0L
            rateLimitedUntilMs = 0L
            inFlight.set(false)
        }

        /**
         * Blocking POST of the final envelope bytes to the exact DSN
         * endpoint: HTTPS only, host/port taken verbatim from the DSN,
         * redirects disabled, connect/read deadlines, no Cookie header
         * sent (this transport never installs a CookieHandler; it cannot
         * strip cookies a globally installed handler would add, and none
         * is installed by this app). The connect/read timeouts bound
         * those phases only: DNS resolution and the request write use the
         * JVM/socket defaults, and there is no single whole-request
         * deadline. Runs only on the worker thread. Returns true only on
         * a 2xx response (accepted at the HTTP layer).
         */
        internal fun defaultPost(dsn: String, bytes: ByteArray): Boolean {
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
                    val code = conn.responseCode // completes the request
                    // Honor every rate-limit signal present on the
                    // response (max wins, 1-hour truncate, 429 floor),
                    // before touching the body.
                    val backoffSeconds =
                        computeBackoffSeconds(
                            conn.getHeaderField("X-Sentry-Rate-Limits"),
                            conn.getHeaderField("X-Sentry-Rate-Limit"),
                            conn.getHeaderField("Retry-After"),
                            System.currentTimeMillis() / 1000,
                        )
                    when {
                        backoffSeconds != null ->
                            rateLimitedUntilMs =
                                System.currentTimeMillis() + backoffSeconds * 1000
                        code == 429 ->
                            rateLimitedUntilMs = System.currentTimeMillis() + MIN_INTERVAL_MS
                    }
                    // Bounded drain: at most MAX_DRAIN_BYTES are read
                    // (bounded memory and a bounded read count; each read
                    // is individually bounded by the read timeout, so a
                    // slow trickle delays at most reads x timeout - there
                    // is no whole-request deadline), then the rest is
                    // cancelled via disconnect().
                    val stream = runCatching { conn.inputStream }.getOrNull() ?: conn.errorStream
                    stream?.let { discardBounded(it) }
                    return code in 200..299
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Reporting must never crash the app or retry: drop.
                return false
            }
        }

        /**
         * Longest server rate-limit window across every present signal:
         * X-Sentry-Rate-Limits entries (plural, comma-separated
         * "seconds:categories:scope"), legacy X-Sentry-Rate-Limit, and
         * Retry-After (delta-seconds or HTTP-date relative to
         * [nowEpochSeconds]). Fractional seconds round up; the result is
         * truncated at the 1-hour cap. Null when no signal parses, so the
         * caller can apply its own 429 floor. Pure and test-visible.
         */
        internal fun computeBackoffSeconds(
            rateLimits: String?,
            rateLimit: String?,
            retryAfter: String?,
            nowEpochSeconds: Long,
        ): Long? {
            val candidates = mutableListOf<Long>()
            rateLimits
                ?.split(',')
                ?.mapNotNull { parseSeconds(it.trim().substringBefore(':')) }
                ?.let { candidates.addAll(it) }
            rateLimit
                ?.let { parseSeconds(it.substringBefore(':').trim()) }
                ?.let { candidates.add(it) }
            retryAfter
                ?.let { h ->
                    parseSeconds(h.trim())
                        ?: runCatching {
                            ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toEpochSecond() - nowEpochSeconds
                        }.getOrNull()
                }
                ?.let { candidates.add(it) }
            return candidates
                .filter { it > 0 }
                .maxOrNull()
                ?.let { minOf(it, MAX_BACKOFF_SECONDS) }
        }

        private fun parseSeconds(raw: String): Long? =
            raw.toDoubleOrNull()?.takeIf { it > 0 }?.let { kotlin.math.ceil(it).toLong() }

        private fun discardBounded(stream: InputStream) {
            runCatching {
                stream.use { s ->
                    val buffer = ByteArray(4096)
                    var remaining = MAX_DRAIN_BYTES
                    while (remaining > 0) {
                        val n = s.read(buffer, 0, minOf(buffer.size, remaining))
                        if (n <= 0) break
                        remaining -= n
                    }
                }
            }
        }
    }
}
