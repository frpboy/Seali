package com.frpboy.seali

import com.frpboy.seali.util.DetailedErrorReport
import org.junit.Assert.*
import org.junit.Test

class DetailedErrorReportTest {
    @Test fun `keeps useful error wording but strips secrets urls paths and tokens`() {
        val msg = "ERROR: [generic] Unable to download webpage: HTTP Error 403: Forbidden " +
            "https://user:pw@cdn.example.org/a/b?token=SECRETVALUE&x=1 Authorization: Bearer ABCDEF\n" +
            "Cookie: sid=COOKIEVALUE /data/user/0/com.frpboy/files/cookies.txt mail me@x.org 10.1.2.3:8080 " +
            "abcdefghijklmnopqrstuvwxyz0123456789"
        val out = DetailedErrorReport.sanitizeMessage(msg)
        assertTrue(out, out.contains("Unable to download webpage"))
        assertTrue(out, out.contains("HTTP Error 403"))
        assertTrue(out, out.contains("cdn.example.org"))
        for (secret in listOf("SECRETVALUE", "pw@", "ABCDEF", "COOKIEVALUE", "/data/user", "cookies.txt", "me@x.org", "10.1.2.3", "abcdefghijklmnopqrstuvwxyz0123"))
            assertFalse(secret + " in " + out, out.contains(secret))
    }

    @Test fun `report has category class name and frames and no raw url path`() {
        val cause = java.net.SocketTimeoutException("Read timed out talking to https://h.example/v?sig=ZZZ")
        val error = IllegalStateException("fetch failed", cause)
        val report = DetailedErrorReport.build("App version: 1", error, "https://h.example/private/path?token=QQQ")
        assertTrue(report, report.contains("Error: java.lang.IllegalStateException"))
        assertTrue(report, report.contains("Caused by: java.net.SocketTimeoutException"))
        assertTrue(report, report.contains("Category: Timeout"))
        assertTrue(report, report.contains("App version: 1"))
        for (secret in listOf("ZZZ", "QQQ", "/private/path")) assertFalse(secret, report.contains(secret))
    }

    @Test fun `unknown class names are withheld and null messages are explicit`() {
        class Local : Exception()
        val report = DetailedErrorReport.build("v", Local())
        assertTrue(report, report.contains("class name withheld"))
        assertTrue(report, report.contains("No error message was provided."))
        assertFalse(report.contains("Local"))
    }

    @Test fun `categories do not depend on the default allowlist phrases`() {
        assertEquals("TLS or certificate", DetailedErrorReport.category(Exception("SSLCertVerificationError: CERTIFICATE_VERIFY_FAILED")))
        assertEquals("Host name lookup", DetailedErrorReport.category(Exception("Temporary failure in name resolution")))
        assertEquals("Storage full", DetailedErrorReport.category(Exception("OSError: [Errno 28] No space left on device")))
        assertEquals("Uncategorized", DetailedErrorReport.category(Exception("something new")))
    }

    @Test fun `cycles and long chains are bounded and long messages truncated`() {
        val a = Exception("a"); val b = Exception("b"); a.initCause(b); b.initCause(a)
        assertTrue(DetailedErrorReport.build("v", a).lines().count { it.startsWith("Caused by:") } <= 1)
        assertTrue(DetailedErrorReport.sanitizeMessage("x ".repeat(1000)).endsWith("[truncated]"))
    }

    @Test fun `ipv6 addresses in every common form are redacted`() {
        val inputs = listOf(
            "connect to 2001:db8:85a3:0:0:8a2e:370:7334 failed",
            "connect to 2001:db8::1 failed",
            "peer fe80::1%wlan0 reset",
            "no route to [2001:db8:85a3::8a2e:370:7334]:443",
            "mapped ::ffff:10.1.2.3 refused",
            "loopback ::1 refused",
            "bad https://[2001:db8::1]:8443/video?token=ZZZ and more",
            "dest [::1]:8080 unreachable",
        )
        for (input in inputs) {
            val out = DetailedErrorReport.sanitizeMessage(input)
            for (leak in listOf("2001", "db8", "fe80", "wlan0", "10.1.2.3", "ffff", "::1", "ZZZ", "8a2e"))
                assertFalse("$leak leaked from <$input> -> <$out>", out.contains(leak))
        }
        assertTrue(DetailedErrorReport.sanitizeMessage("Unable to connect to 2001:db8::1").contains("Unable to connect"))
    }

    @Test fun `ordinary text with colons keeps its meaning`() {
        val out = DetailedErrorReport.sanitizeMessage("ERROR: [generic] Unable to download webpage: HTTP Error 403: Forbidden at 12:34")
        assertTrue(out, out.contains("HTTP Error 403: Forbidden"))
        assertTrue(out, out.contains("[generic]"))
    }
}
