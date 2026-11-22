package com.frpboy.seali

import com.frpboy.seali.util.SourcePolicy
import com.frpboy.seali.util.applySourcePolicy
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyPlayTest {
    @Test fun namedPlatformsAreBlockedAndOthersAreNot() {
        listOf(
            "https://www.youtube.com/watch?v=x",
            "https://music.youtube.com/watch?v=x",
            "https://youtu.be/x",
            "https://www.instagram.com/reel/x/",
            "https://vm.tiktok.com/x/",
        ).forEach { assertNotNull(it, SourcePolicy.blockedReason(it)) }
        assertNull(SourcePolicy.blockedReason("https://example.org/video.mp4"))
        assertNull(SourcePolicy.blockedReason("https://archive.org/details/x"))
    }

    @Test fun rightsConfirmationIsRequiredInPlay() {
        assertTrue(SourcePolicy.REQUIRES_RIGHTS_CONFIRMATION)
    }

    @Test fun extractorFilterExcludesExactlyTheNamedFamilies() {
        val parts = SourcePolicy.extractorFilter!!.split(",")
        assertEquals(listOf("default", "-youtube.*", "-instagram.*", "-tiktok.*"), parts)
        val excluded = parts.filter { it.startsWith("-") }.map { Regex(it.drop(1)) }
        listOf("youtube", "youtube:tab", "youtube:music:search", "instagram", "instagram:story", "tiktok", "tiktok:user")
            .forEach { name -> assertTrue(name, excluded.any { it.matches(name) }) }
        listOf("generic", "vimeo", "soundcloud", "twitter").forEach { name ->
            assertTrue(name, excluded.none { it.matches(name) })
        }
    }

    @Test fun applySourcePolicyAddsTheExtractorFilterToTheRequest() {
        val command = YoutubeDLRequest("https://example.org/v").applySourcePolicy().buildCommand()
        val at = command.indexOf("--use-extractors")
        assertTrue(command.toString(), at >= 0)
        assertEquals(SourcePolicy.extractorFilter, command[at + 1])
        assertEquals("https://example.org/v", command.last())
    }
}
