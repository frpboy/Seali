package com.frpboy.seali

import com.frpboy.seali.util.ApkUpdateVerifier
import com.frpboy.seali.util.UpdateFileStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UpdateFileStoreTest {
    private fun fixture(block: (File, UpdateFileStore) -> Unit) {
        val dir = Files.createTempDirectory("update-file-store").toFile()
        try { block(dir, UpdateFileStore(dir, maxBlobs = 2, maxBytes = 10)) }
        finally { dir.deleteRecursively() }
    }
    private fun ready(store: UpdateFileStore, value: String): UpdateFileStore.Session {
        val bytes = value.toByteArray()
        return store.begin(ApkUpdateVerifier.sha256(bytes), bytes.size.toLong()).also {
            it.temporary.writeBytes(bytes)
            store.promoteVerified(it)
        }
    }
    @Test fun dismissedVerifiedFileWithoutHandoffIsRemoved() = fixture { _, store ->
        val session = ready(store, "abc")
        val f = store.promoteVerified(session)
        store.dismiss(session)
        assertFalse(f.exists())
        assertThrows(IllegalStateException::class.java) { store.retainForHandoff(session) }
    }
    @Test fun retainedFileSurvivesDismissalAndNewProcessCleanup() = fixture { dir, store ->
        val session = ready(store, "abc")
        val f = store.retainForHandoff(session)
        store.dismiss(session)
        UpdateFileStore(dir).cleanAbandonedBeforeSessions()
        assertEquals("abc", f.readText())
    }
    @Test fun admissionCountsRetainedBlobsAndActiveReservations() = fixture { _, store ->
        val a = ready(store, "abc"); store.retainForHandoff(a); store.dismiss(a)
        val b = ready(store, "def"); store.retainForHandoff(b); store.dismiss(b)
        assertThrows(IllegalStateException::class.java) { store.begin(ApkUpdateVerifier.sha256("ghi".toByteArray()), 3) }
        val same = store.begin(ApkUpdateVerifier.sha256("abc".toByteArray()), 3)
        assertEquals("abc", store.promoteVerified(same).readText())
        store.dismiss(same)
    }
    @Test fun startupDeletesUnhandedVerifiedAndPartialFiles() = fixture { dir, _ ->
        val digest = ApkUpdateVerifier.sha256("abc".toByteArray())
        val f = File(dir, "verified/$digest.apk").also { it.parentFile.mkdirs(); it.writeText("abc") }
        val partial = File(dir, "temporary/old.part").also { it.parentFile.mkdirs(); it.writeText("partial") }
        UpdateFileStore(dir).cleanAbandonedBeforeSessions()
        assertFalse(f.exists()); assertFalse(partial.exists())
    }
    @Test fun anotherInstanceCannotCleanAnActiveSession() = fixture { dir, store ->
        val a = ready(store, "abc")
        assertThrows(IllegalStateException::class.java) { UpdateFileStore(dir).cleanAbandonedBeforeSessions() }
        store.dismiss(a)
    }
    @Test fun promotionRejectsWrongBytesAndNeverChangesRetainedBlob() = fixture { _, store ->
        val a = ready(store, "abc"); val f = store.retainForHandoff(a); store.dismiss(a)
        val b = store.begin(ApkUpdateVerifier.sha256("def".toByteArray()), 3)
        b.temporary.writeText("bad")
        assertThrows(IllegalStateException::class.java) { store.promoteVerified(b) }
        assertEquals("abc", f.readText()); store.dismiss(b)
    }
    @Test fun pendingMarkerCausesConservativeRetention() = fixture { dir, _ ->
        val digest = ApkUpdateVerifier.sha256("abc".toByteArray())
        val f = File(dir, "verified/$digest.apk").also { it.parentFile.mkdirs(); it.writeText("abc") }
        File(dir, "handed-off/crashed.pending").also { it.parentFile.mkdirs(); it.writeText(digest) }
        UpdateFileStore(dir).cleanAbandonedBeforeSessions()
        assertTrue(f.exists())
    }
    @Test fun explicitMaintenanceRestoresAdmissionWithoutTouchingOtherFiles() = fixture { dir, store ->
        val outside = File(dir.parentFile, dir.name + "-outside").also { it.writeText("keep") }
        try {
            val a = ready(store, "abc"); store.retainForHandoff(a)
            assertThrows(IllegalStateException::class.java) { store.clearRetainedAfterUserConfirmation() }
            store.dismiss(a)
            store.clearRetainedAfterUserConfirmation()
            assertTrue(File(dir,"verified").listFiles().orEmpty().isEmpty())
            assertTrue(File(dir,"handed-off").listFiles().orEmpty().isEmpty())
            val b = ready(store,"def"); store.dismiss(b)
            assertEquals("keep",outside.readText())
        } finally { outside.delete() }
    }

}
