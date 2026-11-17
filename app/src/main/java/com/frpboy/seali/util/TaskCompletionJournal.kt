package com.frpboy.seali.util

import java.io.File
import java.security.MessageDigest

/**
 * A per-task completion record written by yt-dlp itself.
 *
 * The download request asks yt-dlp to print the final file path to a task-owned file
 * after it has finished moving the finished output into place (`after_move:filepath`).
 * That is yt-dlp's own report that this task's output finished, including merging and
 * post-processing, and it survives the app process dying before it could record the
 * result. Nothing here is inferred from a URL, a title or a file merely existing.
 *
 * A journal only counts when it holds exactly one path. Several lines (playlist,
 * chapter split) or none are ambiguous and are ignored.
 */
internal class TaskCompletionJournal(private val dir: File) {
    data class Entry(val path: String, val writtenAtMs: Long)

    fun fileFor(taskId: String): File = File(dir, sha256(taskId) + ".txt")

    /** Called before each run of a task so a previous run's record is never reused. */
    fun clear(taskId: String) {
        fileFor(taskId).delete()
    }

    fun prepare(taskId: String): File {
        dir.mkdirs()
        clear(taskId)
        return fileFor(taskId)
    }

    fun read(taskId: String): Entry? {
        val file = fileFor(taskId)
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val path = lines.singleOrNull() ?: return null
        return Entry(path = path, writtenAtMs = file.lastModified())
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
