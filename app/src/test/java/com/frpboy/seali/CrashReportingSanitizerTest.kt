package com.frpboy.seali

import com.frpboy.seali.util.CrashReporting
import com.frpboy.seali.util.FixedEventTransport
import com.frpboy.seali.util.applyPrivacyDefaults
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.NoOpLogger
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeHeader
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.protocol.SentryId
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.hints.BlockingFlushHint
import io.sentry.protocol.Device
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Boundary proof for the fixed-generic Sentry payload, executed against
 * the real SDK classes. An event deliberately stuffed with hostile
 * content is passed through the boundary; the outgoing payload is then
 * verified to be exactly the fixed generic event (trusted build
 * constants plus fresh id/time) with no byte of the input carried over.
 */
class CrashReportingSanitizerTest {

    @Before
    fun resetTransportState() {
        // The transport's rate-limit/in-flight state is shared and static;
        // isolate every test from the previous test's sends.
        FixedEventTransport.resetForTests()
    }

    private val hostileStrings =
        listOf(
            "rahul.muraleedharan@example.com",
            "https://example.com/private/path?token=FIXTURE-NOT-A-SECRET",
            "Authorization: Bearer FIXTURE-NOT-A-SECRET",
            "/sdcard/Download/secret-file.txt",
            "user-supplied free text title",
            "192.168.10.77",
            "hostile-marker-7f3a",
        )

    /** An event stuffed with hostile content in every writable slot. */
    private fun hostileEvent(): SentryEvent {
        val e = SentryEvent()
        e.level = SentryLevel.FATAL
        e.logger = "hostile-marker-7f3a"
        e.release = "https://example.com/private/path?token=FIXTURE-NOT-A-SECRET"
        e.dist = "Authorization: Bearer FIXTURE-NOT-A-SECRET"
        e.environment = "user-supplied free text title"
        e.message = io.sentry.protocol.Message().apply { message = "hostile-marker-7f3a" }
        e.serverName = "192.168.10.77"
        e.user = User().apply { email = "rahul.muraleedharan@example.com" }
        e.request = Request().apply { url = "https://example.com/private/path?token=FIXTURE-NOT-A-SECRET" }
        e.breadcrumbs = listOf(Breadcrumb("user-supplied free text title"))
        e.exceptions =
            listOf(
                SentryException().apply {
                    type = "hostile-marker-7f3a"
                    value = "/sdcard/Download/secret-file.txt"
                    module = "rahul.muraleedharan@example.com"
                }
            )
        e.setTag("k", "hostile-marker-7f3a")
        e.setExtra("k", "Authorization: Bearer FIXTURE-NOT-A-SECRET")
        e.contexts.setDevice(
            Device().apply {
                name = "rahul's personal phone"
                model = "hostile-marker-7f3a"
            }
        )
        return e
    }

    private fun serialize(event: SentryEvent): String {
        val options = SentryOptions()
        val serializer = JsonSerializer(options)
        val writer = StringWriter()
        serializer.serialize(event, writer)
        return writer.toString()
    }

    @Test
    fun `beforeSend replaces a hostile event with the fixed generic event`() {
        val options = SentryOptions()
        options.applyPrivacyDefaults()
        options.beforeSend =
            SentryOptions.BeforeSendCallback { _, _ -> CrashReporting.buildGenericEvent() }
        val hint = Hint.withAttachment(Attachment("hostile-marker-7f3a".toByteArray(), "a.txt"))
        val out = options.beforeSend.execute(hostileEvent(), hint)!!
        val payload = serialize(out)
        for (hostile in hostileStrings) {
            assertFalse("payload leaked: $hostile", payload.contains(hostile))
        }
        assertEquals(SentryLevel.ERROR, out.level)
        assertEquals("app", out.logger)
        assertEquals("production", out.environment)
        assertEquals("app error - details withheld by privacy boundary", out.message?.message)
        assertEquals("AppError", out.exceptions?.single()?.type)
        assertNull(out.user)
        assertNull(out.request)
        assertNull(out.breadcrumbs)
        assertNull(out.serverName)
    }

    @Test
    fun `each event gets a fresh id and timestamp`() {
        val a = CrashReporting.buildGenericEvent()
        val b = CrashReporting.buildGenericEvent()
        assertNotEquals(a.eventId, b.eventId)
    }

    @Test
    fun `transport discards a hostile envelope and forwards one fixed event item`() {
        val options = SentryOptions()
        val captured = mutableListOf<ByteArray>()
        options.serializer = JsonSerializer(options)
        val transport = FixedEventTransport(options) { bytes -> captured.add(bytes) }
        // Hostile envelope: hostile event item, an attachment item, hostile
        // hint, original envelope id.
        val hostileItem =
            SentryEnvelopeItem.fromEvent(options.serializer, hostileEvent())
        val attachmentItem =
            SentryEnvelopeItem.fromAttachment(
                options.serializer,
                NoOpLogger.getInstance(),
                Attachment("hostile-marker-7f3a".toByteArray(), "a.txt"),
                60_000,
            )
        val hint = Hint.withAttachment(Attachment("x".toByteArray(), "b.txt"))
        transport.send(
            SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(hostileItem, attachmentItem)),
            hint,
        )
        // The send runs on the bounded worker; wait for actual completion
        // before asserting instead of racing it.
        transport.flush(5_000)
        assertEquals(1, captured.size)
        // Parse the exact final wire bytes back: one envelope, one item.
        val wire = captured.single()
        val parsed = options.serializer.deserializeEnvelope(ByteArrayInputStream(wire))!!
        assertEquals(1, parsed.items.size)
        assertEquals(SentryItemType.Event, parsed.items.single().header.type)
        val payload = String(wire)
        for (hostile in hostileStrings) {
            assertFalse("envelope leaked: $hostile", payload.contains(hostile))
        }
        assertTrue(payload.contains("AppError"))
    }

    @Test
    fun `transport drops envelopes without an event item`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val captured = mutableListOf<ByteArray>()
        val transport = FixedEventTransport(options) { bytes -> captured.add(bytes) }
        // Empty envelope: dropped.
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), emptyList()), Hint())
        // Attachment-only envelope: dropped, never converted into an error.
        val attachmentItem =
            SentryEnvelopeItem.fromAttachment(
                options.serializer,
                NoOpLogger.getInstance(),
                Attachment("x".toByteArray(), "a.txt"),
                60_000,
            )
        transport.send(
            SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(attachmentItem)),
            Hint(),
        )
        transport.flush(5_000)
        assertEquals(0, captured.size)
    }

    @Test
    fun `flush waits for the in-flight send to actually complete`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val finished = CountDownLatch(1)
        val captured = mutableListOf<ByteArray>()
        val transport =
            FixedEventTransport(options) { bytes ->
                Thread.sleep(300) // slow POST still in flight when flush starts
                captured.add(bytes)
                finished.countDown()
                true // delivered
            }
        val item = SentryEnvelopeItem.fromEvent(options.serializer, SentryEvent())
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(item)), Hint())
        transport.flush(5_000)
        // flush returned only after the worker finished the POST.
        assertTrue(finished.await(0, TimeUnit.MILLISECONDS))
        assertEquals(1, captured.size)
    }

    @Test
    fun `a crash flush latch is released only after actual delivery`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val transport = FixedEventTransport(options) { _ -> true } // delivered
        val flushable =
            object : BlockingFlushHint(5_000, NoOpLogger.getInstance()) {
                override fun isFlushable(eventId: SentryId?): Boolean = true
                override fun setFlushable(eventId: SentryId?) {}
            }
        val hint = Hint()
        hint.set("sentry:typeCheckHint", flushable)
        val item = SentryEnvelopeItem.fromEvent(options.serializer, SentryEvent())
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(item)), hint)
        // waitFlush returns true only if the worker called markFlushed
        // after a confirmed delivery - the SDK crash handler contract.
        assertTrue(flushable.waitFlush())
    }

    @Test
    fun `a failed delivery never releases the crash flush latch`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val transport = FixedEventTransport(options) { _ -> false } // failed
        val flushable =
            object : BlockingFlushHint(300, NoOpLogger.getInstance()) {
                override fun isFlushable(eventId: SentryId?): Boolean = true
                override fun setFlushable(eventId: SentryId?) {}
            }
        val hint = Hint()
        hint.set("sentry:typeCheckHint", flushable)
        val item = SentryEnvelopeItem.fromEvent(options.serializer, SentryEvent())
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(item)), hint)
        transport.flush(5_000)
        // Timed out unflushed: the SDK logs a failed delivery, not a false one.
        assertFalse(flushable.waitFlush())
    }

    @Test
    fun `a second send inside the one-minute window is dropped even after completion`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val captured = mutableListOf<ByteArray>()
        val transport = FixedEventTransport(options) { bytes -> captured.add(bytes) }
        val item = SentryEnvelopeItem.fromEvent(options.serializer, SentryEvent())
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(item)), Hint())
        transport.flush(5_000)
        // First send completed; the rate window must still drop the second.
        transport.send(SentryEnvelope(SentryEnvelopeHeader(SentryId()), listOf(item)), Hint())
        transport.flush(5_000)
        assertEquals(1, captured.size)
    }

    @Test
    fun `backoff takes the maximum across all present rate-limit headers`() {
        val now = 1_700_000_000L
        assertEquals(
            120L,
            FixedEventTransport.computeBackoffSeconds("30:a:b, 120:c:d", "90:k", "45", now),
        )
    }

    @Test
    fun `backoff parses legacy singular header and retry-after seconds`() {
        val now = 1_700_000_000L
        assertEquals(90L, FixedEventTransport.computeBackoffSeconds(null, "90:key:scope", null, now))
        assertEquals(45L, FixedEventTransport.computeBackoffSeconds(null, null, "45", now))
    }

    @Test
    fun `backoff parses retry-after http date and fractional seconds round up`() {
        val now = 1_700_000_000L
        val date =
            java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(now + 100), java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        val fromDate = FixedEventTransport.computeBackoffSeconds(null, null, date, now)!!
        assertTrue(fromDate in 95..100)
        assertEquals(2L, FixedEventTransport.computeBackoffSeconds(null, "1.5:k", null, now))
    }

    @Test
    fun `backoff truncates at one hour and ignores unparseable or negative values`() {
        val now = 1_700_000_000L
        assertEquals(3_600L, FixedEventTransport.computeBackoffSeconds("99999999:a", null, null, now))
        assertEquals(null, FixedEventTransport.computeBackoffSeconds("garbage", null, "not-a-date", now))
        assertEquals(null, FixedEventTransport.computeBackoffSeconds(null, "-5", null, now))
        assertEquals(null, FixedEventTransport.computeBackoffSeconds(null, null, null, now))
    }

    @Test
    fun `privacy defaults keep pii sessions stacktraces and logs off`() {
        val options = SentryOptions()
        options.applyPrivacyDefaults()
        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isEnableAutoSessionTracking)
        assertFalse(options.isAttachStacktrace)
        assertFalse(options.isAttachThreads)
        assertNull(options.tracesSampleRate)
        assertNull(options.profilesSampleRate)
        assertFalse(options.logs.isEnabled)
    }
}
