package com.frpboy.seali.util

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Inert until wired to the session coordinator. Internal filesDir/update-apks only, never shared latest.apk. */
internal class UpdateFileStore(
    private val root: File,
    private val maxBlobs: Int = 3,
    private val maxBytes: Long = 1_073_741_824L,
) {
    class Session internal constructor(val id: String, val digest: String, val size: Long, val temporary: File)
    private val verified = File(root, "verified")
    private val markers = File(root, "handed-off")
    private val temporary = File(root, "temporary")
    private val sessions: MutableMap<String, Session>
        get() = activeByRoot.getOrPut(root.canonicalPath) { mutableMapOf() }

    companion object {
        // One lock covers all instances; do not run cleanup from another process.
        private val lock = Any()
        private val activeByRoot = mutableMapOf<String, MutableMap<String, Session>>()
        private fun syncDirectory(dir: File) {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun prepare() {
        for (dir in listOf(root, verified, markers, temporary)) check(dir.isDirectory || dir.mkdirs())
    }
    private fun blob(digest: String) = File(verified, "$digest.apk")
    private fun marker(digest: String) = File(markers, "$digest.retained")
    private fun owns(session: Session) { check(sessions[session.id] === session) { "Stale update session" } }

    /** Admission reserves both bytes and a slot, even before network work begins. */
    fun begin(digest: String, size: Long): Session = synchronized(lock) {
        check(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid APK digest" }
        check(size > 0 && size <= maxBytes) { "APK exceeds update storage limit" }
        prepare()
        check(sessions.values.none { it.digest == digest }) { "APK already owned by another session" }
        val blobs = verified.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }.orEmpty()
        val reserved = sessions.values.filter { !blob(it.digest).exists() }
        val isNew = !blob(digest).exists()
        val slots = blobs.size.toLong() + reserved.size + if (isNew) 1 else 0
        var bytes = 0L
        for (length in blobs.map { it.length() } + reserved.map { it.size } + if (isNew) listOf(size) else emptyList()) {
            check(length >= 0 && bytes <= maxBytes - length) { "Retained update storage is full" }
            bytes += length
        }
        check(slots <= maxBlobs) { "Retained update storage is full" }
        val id = UUID.randomUUID().toString()
        Session(id, digest, size, File(temporary, "$id.part")).also { sessions[id] = it }
    }

    /** The caller must FIRST verify package/version/current signer. This also checks exact byte identity. */
    fun promoteVerified(session: Session): File = synchronized(lock) {
        owns(session)
        val target = blob(session.digest)
        if (target.exists()) {
            check(target.length() == session.size && ApkUpdateVerifier.sha256(target) == session.digest) {
                "Existing immutable APK failed verification"
            }
            session.temporary.delete()
            return@synchronized target
        }
        check(session.temporary.length() == session.size && ApkUpdateVerifier.sha256(session.temporary) == session.digest)
        FileOutputStream(session.temporary, true).use { it.fd.sync() }
        // Both directories are under the same filesDir subtree. No non-atomic fallback is allowed.
        Files.move(session.temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(verified)
        target
    }

    /** Persist retention BEFORE returning the immutable path to the installer-launch effect. Fail closed on IO error. */
    fun retainForHandoff(session: Session): File = synchronized(lock) {
        owns(session)
        val target = blob(session.digest)
        check(target.isFile && target.length() == session.size && ApkUpdateVerifier.sha256(target) == session.digest)
        val destination = marker(session.digest)
        if (!destination.exists()) {
            val staging = File(markers, "${session.id}.pending")
            FileOutputStream(staging).use { it.write(session.digest.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(markers)
        }
        target
    }

    /** Dismissal may remove a verified-but-never-handed-off blob. Retained blobs are NEVER pruned here. */
    fun dismiss(session: Session) = synchronized(lock) {
        owns(session)
        sessions.remove(session.id)
        session.temporary.delete()
        if (!marker(session.digest).exists()) blob(session.digest).delete()
        Unit
    }

    /** Explicit in-app user confirmation required: may interrupt an already opened Android installer. */
    fun clearRetainedAfterUserConfirmation() = synchronized(lock) {
        check(sessions.isEmpty()) { "Close the active update before clearing retained APKs" }
        prepare()
        // Remove blobs first. If interrupted, retained markers conservatively survive and no unmarked blob is exposed.
        for (dir in listOf(verified, temporary, markers)) {
            dir.listFiles()?.forEach { check(it.isFile && (it.delete() || !it.exists())) { "Could not clear retained APK" } }
            syncDirectory(dir)
        }
        Unit
    }

    /** Call only after process startup, before this store accepts any sessions. */
    fun cleanAbandonedBeforeSessions() = synchronized(lock) {
        check(sessions.isEmpty()) { "Cannot clean an active update store" }
        prepare()
        temporary.listFiles()?.forEach { check(it.delete() || !it.exists()) }
        // A .pending marker may mean a crash during retention: over-retain that digest, never infer no grant.
        val pending = markers.listFiles()?.any { it.name.endsWith(".pending") } == true
        if (!pending) verified.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach {
            if (!marker(it.name.removeSuffix(".apk")).exists()) check(it.delete() || !it.exists())
        }
        Unit
    }
}
