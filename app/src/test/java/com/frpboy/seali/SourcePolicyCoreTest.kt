package com.frpboy.seali

import com.frpboy.seali.util.SourcePolicyCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyCoreTest {
    private val d = listOf("youtube.com", "youtu.be", "tiktok.com")

    @Test fun exactAndSubdomainsAreBlocked() {
        assertTrue(SourcePolicyCore.isBlockedHost("https://youtube.com/watch?v=x", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://www.youtube.com/watch?v=x", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://m.YouTube.com/x", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://youtu.be/abc", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://vm.tiktok.com/Z/", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://youtube.com./x", d))
        assertTrue(SourcePolicyCore.isBlockedHost("https://youtube.com:443/x", d))
    }

    @Test fun lookalikesAndOtherHostsAreNotBlockedByHost() {
        assertFalse(SourcePolicyCore.isBlockedHost("https://notyoutube.com/x", d))
        assertFalse(SourcePolicyCore.isBlockedHost("https://youtube.com.example.org/x", d))
        assertFalse(SourcePolicyCore.isBlockedHost("https://example.org/?u=youtube.com", d))
        assertFalse(SourcePolicyCore.isBlockedHost("https://archive.org/details/x", d))
    }

    @Test fun nonHttpOrMalformedHaveNoHost() {
        assertNull(SourcePolicyCore.hostOf("ftp://youtube.com/x"))
        assertNull(SourcePolicyCore.hostOf("not a url"))
        assertNull(SourcePolicyCore.hostOf(""))
        assertEquals("example.org", SourcePolicyCore.hostOf(" https://Example.org/a "))
    }
}
