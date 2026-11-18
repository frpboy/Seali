package com.frpboy.seali.util

import java.io.File

/**
 * Exports the WebView cookie jar to the cookies file lazily instead of at every app start.
 *
 * The previous behaviour wrote the whole jar (all sites) into cacheDir at startup even when
 * cookies were never used. Now the jar is exported only the first time a cookie-using task
 * needs the file in this process, or when the user explicitly refreshes it (cookie dialog or
 * cookie page). An explicit write counts as a refresh, so it is never overwritten by the lazy
 * export. Writes go through a temporary file and a rename so concurrent tasks never read a
 * partial file. Existing files are never deleted here.
 */
class CookieFileRefresher(private val exportJar: () -> String?) {
    private val lock = Any()
    @Volatile private var refreshedThisProcess = false

    /** Called by cookie-using tasks before they pass the file to the downloader. */
    fun ensureRefreshed(target: File) {
        if (refreshedThisProcess) return
        synchronized(lock) {
            if (refreshedThisProcess) return
            val content = exportJar() ?: return // nothing to export yet: retry on the next task
            writeAtomically(content, target)
            refreshedThisProcess = true
        }
    }

    /** Called when the user explicitly writes the file (cookie dialog or cookie page). */
    fun writeExplicit(content: String, target: File) {
        synchronized(lock) {
            writeAtomically(content, target)
            refreshedThisProcess = true
        }
    }

    private fun writeAtomically(content: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }
}
