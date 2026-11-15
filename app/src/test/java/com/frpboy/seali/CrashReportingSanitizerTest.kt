package com.frpboy.seali

import com.frpboy.seali.util.SentryEventSanitizer
import com.frpboy.seali.util.applyPrivacyDefaults
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile-scope negative proof for the Sentry egress boundary, executed
 * against the real SDK classes. An event deliberately stuffed with user
 * identifiers, URLs, tokens, absolute paths, typed text and device name
 * must leave the sanitizer containing none of them, including in the
 * actual SDK-serialized JSON payload.
 */
class CrashReportingSanitizerTest {

    private val hostileStrings =
        listOf(
            "person@example.com",
            "account-name",
            "203.0.113.10",
            "https://media.example/watch?v=private-item",
            "session=secret",
            "user typed private text",
            "rahus-personal-device",
            "/data/user/0/com.frpboy.seali/files/Download/private video.mp4",
            "token=eyJhbGciOiJIUzI1NiJ9",
            "failed to load https://media.example/watch?v=private-item&token=eyJhbGciOiJIUzI1NiJ9",
        )

    private fun hostileEvent(): SentryEvent {
        val event = SentryEvent(RuntimeException("ignored"))
        event.user =
            User().apply {
                email = "person@example.com"
                username = "account-name"
                ipAddress = "203.0.113.10"
            }
        event.request =
            Request().apply {
                url = "https://media.example/watch?v=private-item"
                cookies = "session=secret"
            }
        event.breadcrumbs = listOf(Breadcrumb().apply { message = "user typed private text" })
        event.serverName = "rahus-personal-device"
        event.message =
            io.sentry.protocol.Message().apply {
                message = "failed to load https://media.example/watch?v=private-item&token=eyJhbGciOiJIUzI1NiJ9"
            }
        event.transaction = "/watch/private-item"
        event.setTag("user_query", "private-item")
        event.setExtra("attempt_url", "https://media.example/watch?v=private-item&token=eyJhbGciOiJIUzI1NiJ9")
        event.contexts.setDevice(
            Device().apply {
                model = "Pixel 8"
                name = "rahus-personal-device"
            }
        )
        event.exceptions =
            listOf(
                SentryException().apply {
                    type = "RuntimeException"
                    module = "com.frpboy.seali"
                    value = "failed to load https://media.example/watch?v=private-item&token=eyJhbGciOiJIUzI1NiJ9"
                    mechanism = Mechanism().apply { type = "UncaughtExceptionHandler" }
                    stacktrace =
                        SentryStackTrace(
                            listOf(
                                SentryStackFrame().apply {
                                    module = "com.frpboy.seali.App"
                                    function = "onCreate"
                                    filename = "App.kt"
                                    lineno = 87
                                    isInApp = true
                                    absPath = "/data/user/0/com.frpboy.seali/files/Download/private video.mp4"
                                }
                            )
                        )
                }
            )
        return event
    }

    @Test
    fun structuralReconstructionKeepsOnlyKnownSafeParts() {
        val event = SentryEventSanitizer.apply(hostileEvent())
        assertNull(event.user)
        assertNull(event.request)
        assertNull(event.breadcrumbs)
        assertNull(event.serverName)
        assertNull(event.message)
        assertNull(event.transaction)
        assertTrue(event.tags == null || event.tags.isEmpty())
        assertTrue(event.extras == null || event.extras.isEmpty())
        assertNull(event.contexts.device?.name)
        assertEquals("Pixel 8", event.contexts.device?.model)
        val exception = event.exceptions!!.single()
        assertEquals("RuntimeException", exception.type)
        assertNull(exception.value)
        assertEquals("UncaughtExceptionHandler", exception.mechanism?.type)
        val frame = exception.stacktrace!!.frames!!.single()
        assertEquals("onCreate", frame.function)
        assertEquals("App.kt", frame.filename)
        assertNull(frame.absPath)
    }

    @Test
    fun actualSdkSerializedPayloadContainsNoHostileString() {
        val sanitized = SentryEventSanitizer.apply(hostileEvent())
        val serializer = JsonSerializer(SentryOptions())
        val writer = StringWriter()
        serializer.serialize(sanitized, writer)
        val payload = writer.toString()
        assertTrue("sanitized payload must keep the exception type", payload.contains("RuntimeException"))
        assertTrue("sanitized payload must keep frame function", payload.contains("onCreate"))
        for (hostile in hostileStrings) {
            assertFalse("payload leaked: $hostile", payload.contains(hostile))
        }
    }

    @Test
    fun privacyDefaultsAreEffectivelyOffOnRealOptions() {
        val options = SentryOptions()
        options.applyPrivacyDefaults()
        assertNull(options.tracesSampleRate)
        assertNull(options.profilesSampleRate)
        assertFalse(options.logs.isEnabled)
        assertFalse(options.isEnableAutoSessionTracking)
        assertFalse(options.isAttachStacktrace)
        assertFalse(options.isAttachThreads)
        assertFalse(options.isSendDefaultPii)
    }
}
