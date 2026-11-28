package com.frpboy.seali

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.frpboy.seali.util.FunctionalEngine
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Test

/**
 * Isolated App bootstrap data tests; never touches the user's engine or app state.
 *
 * The alpha8 EngineGuard binds one engine directory per process and refuses a
 * different one, so every test in this class shares ONE fixed isolated root
 * (emptied before each test) instead of a per-test directory: the first install
 * binds it, later installs on the same directory are legal, and no test ever
 * asks the process for a second directory. This class must run with the app
 * engine-init skipped (instrumentation argument skipAppEngineInit=true, see
 * [TestApp]); otherwise the app init binds the real directory first and every
 * install here fails with the guard refusal instead of exercising its target.
 */
class FunctionalEngineMigrationTest {
    private fun isolated(block: (ContextWrapper, File) -> Unit) {
        // Fail-closed lane precondition: this class only produces meaningful results in
        // the skip lane. Without the arg the app init binds the real directory and every
        // install fails on the guard; say so explicitly instead of letting the failures
        // read as product bugs. This is a hard assertion, not an Assume/skip.
        assertEquals(
            "FunctionalEngineMigrationTest must run in the skip lane " +
                "(--environment-variables skipAppEngineInit=true); without it the app " +
                "engine-init binds the real directory first and every failure here is a " +
                "mis-dispatched lane, not a product result",
            "true",
            InstrumentationRegistry.getArguments().getString("skipAppEngineInit"),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "engine-migration-test")
        dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory) { "Cannot prepare isolated engine root" }
        val isolatedContext =
            object : ContextWrapper(context) {
                override fun getNoBackupFilesDir(): File = dir
            }
        try {
            block(isolatedContext, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldEngineGetsVerifiedCurrentAndFirstBackupSurvives() = isolated { context, root ->
        val bin =
            File(root, "youtubedl-android/yt-dlp/yt-dlp").apply {
                parentFile!!.mkdirs()
                writeText("old-alpha-fixture")
            }
        FunctionalEngine.install(context)
        val backup = File(root, "seali-functional-engine-state/yt-dlp.pre-functional-alpha")
        assertEquals("old-alpha-fixture", backup.readText())
        assertTrue(bin.length() > 1000000)
        bin.writeText("newer-user-approved-engine-fixture")
        FunctionalEngine.install(context)
        assertEquals("newer-user-approved-engine-fixture", bin.readText())
        assertEquals("old-alpha-fixture", backup.readText())
    }

    @Test
    fun updaterDirectoryRemovalCannotDeleteStateOrBackupAndFallbackIsCurrent() = isolated { context, root ->
        val bin =
            File(root, "youtubedl-android/yt-dlp/yt-dlp").apply {
                parentFile!!.mkdirs()
                writeText("pre-alpha-fixture")
            }
        FunctionalEngine.install(context)
        bin.parentFile!!.deleteRecursively() // The published updater's failure cleanup boundary.
        bin.parentFile!!.mkdirs()
        // Mirrors init_ytdlp fallback using the transformed wrapper's raw engine, not the asset.
        val raw = context.resources.getIdentifier("ytdlp", "raw", context.packageName)
        assertTrue("Missing wrapper fallback", raw != 0)
        context.resources.openRawResource(raw).use { input -> bin.outputStream().use { input.copyTo(it) } }
        val hash =
            java.security.MessageDigest.getInstance("SHA-256").digest(bin.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("Wrapper updater failure restores stale engine", FunctionalEngine.SHA256, hash)
        assertEquals(
            "pre-alpha-fixture",
            File(root, "seali-functional-engine-state/yt-dlp.pre-functional-alpha").readText(),
        )
        assertEquals(
            FunctionalEngine.VERSION,
            File(root, "seali-functional-engine-state/functional-alpha-engine-version").readText(),
        )
    }

    @Test
    fun legitimateParentAliasIsAcceptedButChildSymlinkIsRejected() = isolated { context, root ->
        val alias = File(root.parentFile, "engine-parent-alias")
        alias.delete()
        android.system.Os.symlink(root.path, alias.path)
        try {
            val aliasContext =
                object : ContextWrapper(context) {
                    override fun getNoBackupFilesDir(): File = alias
                }
            FunctionalEngine.install(aliasContext)
            assertTrue(File(root, "youtubedl-android/yt-dlp/yt-dlp").length() > 1000000)
            val temporary = File(root, "youtubedl-android/yt-dlp/yt-dlp.alpha-new")
            android.system.Os.symlink(File(root, "outside-fixture").path, temporary.path)
            val failure = runCatching { FunctionalEngine.install(aliasContext) }.exceptionOrNull()
            assertNotNull("install must reject the child symlink", failure)
            // The targeted path is the child-symlink check, never the process-global guard.
            assertTrue(
                "expected the child-symlink rejection, was: $failure",
                failure is IllegalStateException &&
                    failure.message == "Unexpected engine state child symlink",
            )
            assertFalse(
                "must not be the engine guard refusal: $failure",
                failure.message.orEmpty().contains("Engine guard is already bound"),
            )
        } finally {
            alias.delete()
        }
    }

    @Test
    fun failedTemporaryOutputOpenLeavesOldEngineAndNoMigrationMarker() = isolated { context, root ->
        val bin =
            File(root, "youtubedl-android/yt-dlp/yt-dlp").apply {
                parentFile!!.mkdirs()
                writeText("old-preserved")
            }
        // Occupy the temporary output path with a directory to force safe pre-swap failure.
        File(bin.parentFile, "yt-dlp.alpha-new").mkdirs()
        val failure = runCatching { FunctionalEngine.install(context) }.exceptionOrNull()
        assertNotNull("install must fail on the occupied temporary output", failure)
        // The targeted path is the unwritable temporary output, never the process-global
        // guard. An "Engine guard is already bound" failure here would be vacuous: the
        // occupied-temp path would never have been reached.
        assertTrue(
            "expected the occupied temporary output failure, was: $failure",
            failure is FileNotFoundException && failure.message.orEmpty().contains("yt-dlp.alpha-new"),
        )
        assertFalse(
            "must not be the engine guard refusal: $failure",
            failure.message.orEmpty().contains("Engine guard is already bound"),
        )
        assertEquals("old-preserved", bin.readText())
        assertFalse(File(root, "seali-functional-engine-state/functional-alpha-engine-version").exists())
    }

    /**
     * Documents the alpha8 library invariant this class is built around: once a
     * process is bound to one engine directory, binding a different directory is
     * refused. The failed attempt must not rebind the process, so this test is
     * order-independent inside the shared fixed root.
     */
    @Test
    fun engineGuardRefusesSecondDifferentDirectoryPerProcess() = isolated { context, root ->
        FunctionalEngine.install(context) // binds (or legally rebinds) the shared fixed root
        val other = File(root.parentFile, "engine-migration-test-other")
        other.deleteRecursively()
        other.mkdirs()
        try {
            val otherContext =
                object : ContextWrapper(context) {
                    override fun getNoBackupFilesDir(): File = other
                }
            val failure = runCatching { FunctionalEngine.install(otherContext) }.exceptionOrNull()
            assertNotNull("a second, different engine directory must be refused", failure)
            assertTrue(
                "expected the engine guard refusal, was: $failure",
                failure.message.orEmpty().contains("Engine guard is already bound"),
            )
        } finally {
            other.deleteRecursively()
        }
    }
}
