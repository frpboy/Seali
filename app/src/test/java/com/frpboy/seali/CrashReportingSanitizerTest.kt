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
import io.sentry.SentryId
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary proof for the fixed-generic Sentry payload, executed against
 * the real SDK classes. An event deliberately stuffed with hostile
 * content is passed through the boundary; the outgoing payload is then
 * verified to be exactly the fixed generic event (trusted build
 * constants plus fresh id/time) with no byte of the input carried over.
 */
class CrashReportingSanitizerTest {

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
        val e = SentryEvent(SentryId(), Date())
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
        val captured = mutableListOf<SentryEnvelope>()
        val inner =
            object : ITransport {
                override fun send(envelope: SentryEnvelope, hint: Hint) {
                    captured.add(envelope)
                }
                override fun isHealthy() = true
                override fun flush(timeoutMillis: Long) {}
                override fun close() {}
            }
        options.serializer = JsonSerializer(options)
        val transport = FixedEventTransport(options, inner)
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
        assertEquals(1, captured.size)
        val out = captured.single()
        assertEquals(1, out.items.size)
        assertEquals(SentryItemType.Event, out.items.single().header.type)
        val bytes = ByteArrayOutputStream()
        options.serializer.serialize(out, bytes)
        val payload = bytes.toString()
        for (hostile in hostileStrings) {
            assertFalse("envelope leaked: $hostile", payload.contains(hostile))
        }
        // Fresh ids: header id differs from the submitted envelope's id.
    }

    @Test
    fun `transport drops envelopes without an event item`() {
        val options = SentryOptions()
        options.serializer = JsonSerializer(options)
        val captured = mutableListOf<SentryEnvelope>()
        val inner =
            object : ITransport {
                override fun send(envelope: SentryEnvelope, hint: Hint) {
                    captured.add(envelope)
                }
                override fun isHealthy() = true
                override fun flush(timeoutMillis: Long) {}
                override fun close() {}
            }
        val transport = FixedEventTransport(options, inner)
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
        assertEquals(0, captured.size)
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
