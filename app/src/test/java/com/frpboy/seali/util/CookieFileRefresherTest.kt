package com.frpboy.seali.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CookieFileRefresherTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun nothingIsWrittenUntilATaskNeedsTheFile() {
        var exports = 0
        val target = File(folder.root, "cookies.txt")
        CookieFileRefresher { exports++; "jar" }
        assertEquals(0, exports)
        assertFalse(target.exists())
    }

    @Test
    fun firstTaskExportsOnceAndLaterTasksReuseTheFile() {
        var exports = 0
        val target = File(folder.root, "cookies.txt")
        val refresher = CookieFileRefresher { exports++; "jar-$exports" }
        refresher.ensureRefreshed(target)
        refresher.ensureRefreshed(target)
        assertEquals(1, exports)
        assertEquals("jar-1", target.readText())
        assertFalse(File(folder.root, "cookies.txt.tmp").exists())
    }

    @Test
    fun emptyJarIsRetriedOnTheNextTaskAndKeepsAnExistingFile() {
        var jar: String? = null
        val target = File(folder.root, "cookies.txt").apply { writeText("existing") }
        val refresher = CookieFileRefresher { jar }
        refresher.ensureRefreshed(target)
        assertEquals("existing", target.readText())
        jar = "new"
        refresher.ensureRefreshed(target)
        assertEquals("new", target.readText())
    }

    @Test
    fun explicitWriteIsNeverOverwrittenByTheLazyExport() {
        var exports = 0
        val target = File(folder.root, "cookies.txt")
        val refresher = CookieFileRefresher { exports++; "jar" }
        refresher.writeExplicit("profile", target)
        refresher.ensureRefreshed(target)
        assertEquals(0, exports)
        assertTrue(target.readText() == "profile")
    }
}
