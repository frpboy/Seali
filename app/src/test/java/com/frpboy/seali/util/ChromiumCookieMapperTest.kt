package com.frpboy.seali.util

import com.frpboy.seali.ui.page.settings.network.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumCookieMapperTest {
    private fun line(c: Cookie) = c.toNetscapeCookieString().split("\t")

    @Test
    fun hostOnlyCookieKeepsItsHostAndIsNotWidenedToSubdomains() {
        val c = chromiumRowToCookie("example.test", 0, "/", "sid", "SYNTH", true)!!
        assertEquals("example.test", c.domain)
        assertFalse(c.includeSubdomains)
        assertEquals(listOf("example.test", "FALSE", "/", "TRUE", "0", "sid", "SYNTH"), line(c))
    }

    @Test
    fun domainCookieKeepsItsLeadingDotAndSubdomainFlag() {
        val c = chromiumRowToCookie(".example.test", 5L, "/a", "sid", "SYNTH", false)!!
        assertEquals(".example.test", c.domain)
        assertTrue(c.includeSubdomains)
        assertEquals(listOf(".example.test", "TRUE", "/a", "FALSE", "5", "sid", "SYNTH"), line(c))
    }

    @Test
    fun pathSecureAndExpiryArePreserved() {
        val c = chromiumRowToCookie("a.test", 123456789L, "/private/x", "n", "v", true)!!
        assertEquals("/private/x", c.path)
        assertTrue(c.secure)
        assertEquals(123456789L, c.expiry)
    }

    @Test
    fun hostOnlyAndDomainCookiesForTheSameHostStayDistinct() {
        val host = chromiumRowToCookie("a.test", 0, "/", "n", "1", false)!!
        val domain = chromiumRowToCookie(".a.test", 0, "/", "n", "2", false)!!
        assertEquals("FALSE", line(host)[1])
        assertEquals("TRUE", line(domain)[1])
    }

    @Test
    fun malformedRowsAreSkippedNotWidened() {
        assertNull(chromiumRowToCookie(null, 0, "/", "n", "v", false))
        assertNull(chromiumRowToCookie("", 0, "/", "n", "v", false))
        assertNull(chromiumRowToCookie("  ", 0, "/", "n", "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, null, "n", "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, "", "n", "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, "/", null, "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, "/", "", "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, "/", "n", null, false))
        assertNull(chromiumRowToCookie("a.test", 0, "/", "n", "", false))
        assertNull(chromiumRowToCookie("#HttpOnly_a.test", 0, "/", "n", "v", false))
    }

    @Test
    fun fieldsThatCouldBreakTheLineFormatAreSkipped() {
        assertNull(chromiumRowToCookie("a.test", 0, "/", "n\tx", "v", false))
        assertNull(chromiumRowToCookie("a.test", 0, "/", "n", "v\nw", false))
        assertNull(chromiumRowToCookie("a.test", 0, "/p\rq", "n", "v", false))
        assertNull(chromiumRowToCookie("a.\ttest", 0, "/", "n", "v", false))
    }

    @Test
    fun everyWrittenLineHasSevenFields() {
        val rows =
            listOf(
                chromiumRowToCookie("a.test", 0, "/", "n", "v", false),
                chromiumRowToCookie(".b.test", 9, "/x", "m", "w", true),
            )
        rows.forEach { assertEquals(7, line(it!!).size) }
    }
}
