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

    private fun base(taskId: String) = sha256(taskId)

    fun fileFor(taskId: String): File = File(dir, base(taskId) + ".txt")

    private fun metaFor(taskId: String): File = File(dir, base(taskId) + ".meta")

    /** Removes this task's records. Called before each run starts. */
    fun clear(taskId: String) {
        fileFor(taskId).delete()
        metaFor(taskId).delete()
    }

    /**
     * Deletes every record except those of [keepTaskIds]. Only called after the queue
     * backup was durably written, so a record is never deleted before the persisted
     * state no longer needs it.
     */
    fun sweepExcept(keepTaskIds: Set<String>, notModifiedAfterMs: Long = Long.MAX_VALUE) {
        val keep = keepTaskIds.map { base(it) }.toSet()
        // Records written after the sweep decision was taken belong to a run that started
        // meanwhile and are never touched.
        dir.listFiles()?.forEach {
            if (it.isFile && it.name.substringBefore('.') !in keep && it.lastModified() <= notModifiedAfterMs)
                it.delete()
        }
    }

    fun prepare(taskId: String): File {
        dir.mkdirs()
        clear(taskId)
        return fileFor(taskId)
    }

    /**
     * Binds the record to its task and to the destination folder the run was configured
     * with. A recovered path must lie inside that folder and the meta record must name
     * the same task id.
     */
    fun recordDestination(taskId: String, destinationRoot: String) {
        dir.mkdirs()
        metaFor(taskId).writeText(taskId + "\n" + destinationRoot + "\n")
    }

    fun read(taskId: String): Entry? {
        val file = fileFor(taskId)
        val meta = metaFor(taskId)
        if (!file.isFile || !meta.isFile) return null
        val metaLines = meta.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (metaLines.size != 2 || metaLines[0] != taskId) return null
        val root = metaLines[1]
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val path = lines.singleOrNull() ?: return null
        if (!isInside(root, path)) return null
        return Entry(path = path, writtenAtMs = file.lastModified())
    }

    private fun isInside(root: String, path: String): Boolean {
        if (root.isBlank()) return false
        val canonRoot = runCatching { File(root).canonicalFile }.getOrNull() ?: return false
        val canonPath = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return canonPath != canonRoot && canonPath.toPath().startsWith(canonRoot.toPath())
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
