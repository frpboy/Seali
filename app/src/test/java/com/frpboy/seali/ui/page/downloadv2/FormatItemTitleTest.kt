package com.frpboy.seali.ui.page.downloadv2

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatItemTitleTest {
    @Test
    fun missingNoteHasNoNullText() {
        assertEquals("Audio #1", formatItemTitle("Audio", 1, null))
        assertEquals("Audio #1", formatItemTitle("Audio", 1, "  "))
    }

    @Test
    fun noteIsShownWhenPresent() {
        assertEquals("Video #2: 1080p", formatItemTitle("Video", 2, "1080p"))
    }
}
