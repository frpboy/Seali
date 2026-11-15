package com.frpboy.seali

import com.frpboy.seali.util.SentryEventSanitizer
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Request
import io.sentry.protocol.Response
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Hostile-scope negative proof for the Sentry egress boundary: an event
 * deliberately stuffed with user identifiers, URLs, cookies and typed
 * text must leave the sanitizer with none of them present.
 */
class CrashReportingSanitizerTest {

    private fun hostileEvent(): SentryEvent {
        val event = SentryEvent()
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
        event.contexts["response"] = Response()
        event.contexts["device"] = mapOf("model" to "Pixel 8")
        return event
    }

    @Test
    fun stripsUserIdentifiersUrlsCookiesBreadcrumbsAndServerName() {
        val event = SentryEventSanitizer.apply(hostileEvent())
        assertNull(event.user)
        assertNull(event.request)
        assertNull(event.breadcrumbs)
        assertNull(event.serverName)
        assertNull(event.contexts["response"])
    }

    @Test
    fun keepsThrowableAndDeviceContext() {
        val event = hostileEvent()
        val failure = RuntimeException("boom")
        event.throwable = failure
        val sanitized = SentryEventSanitizer.apply(event)
        assertSame(failure, sanitized.throwable)
        assertEquals(mapOf("model" to "Pixel 8"), sanitized.contexts["device"])
    }
}
