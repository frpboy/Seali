package com.frpboy.seali.util

import com.yausername.youtubedl_android.EngineGuard
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * yt-dlp plugin that makes every cookie policy in the yt-dlp process enforce the Netscape
 * host-only flag. Without it, Python's default policy sends a host-only cookie to subdomains of
 * the cookie host. Domain cookies keep subdomain matching. Redirect hops are covered because the
 * policy runs on every request.
 *
 * The plugin file sits beside the pinned engine, loaded by yt-dlp's own plugin loader. It is not
 * proof by itself: yt-dlp ignores a missing, disabled or broken plugin and keeps running with the
 * lenient policy. [CookiePolicyGate] therefore proves behavior with a probe before cookies are used.
 */
internal object CookiePolicyPlugin {
    const val PLUGIN_ROOT = "yt-dlp-plugins"
    const val PACKAGE = "strictcookie"
    val RELATIVE_FILE = "$PLUGIN_ROOT/$PACKAGE/yt_dlp_plugins/postprocessor/strict_cookie_policy.py"

    /** The plugin file as the launch snapshot reports it: relative to the yt-dlp-plugins folder. */
    val SNAPSHOT_KEY = RELATIVE_FILE.removePrefix("$PLUGIN_ROOT/")

    val SOURCE: String =
        """
        # Seali: host-only cookies match only their exact host in this yt-dlp process.
        import http.cookiejar as _cj
        import inspect as _inspect

        _STRICT = _cj.DefaultCookiePolicy.DomainStrictNonDomain
        _SIG = _inspect.signature(_cj.DefaultCookiePolicy.__init__)
        _ORIG = _cj.DefaultCookiePolicy.__init__


        def _patched(self, *args, **kwargs):
            bound = _SIG.bind(self, *args, **kwargs)
            given = bound.arguments.get('strict_ns_domain')
            bound.arguments['strict_ns_domain'] = (given or 0) | _STRICT
            _ORIG(*bound.args, **bound.kwargs)


        if not getattr(_cj.DefaultCookiePolicy.__init__, '_seali_strict', False):
            _patched._seali_strict = True
            _cj.DefaultCookiePolicy.__init__ = _patched
        """
            .trimIndent() + "\n"

    val SHA256: String = sha256(SOURCE.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun sha256(file: File): String =
        file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

    /**
     * The engine's Python writes a bytecode cache beside the plugin the first time it imports it
     * (confirmed on desktop yt-dlp with this exact layout; the app never turns bytecode writing off).
     * That cache is ours: delete it so only the verified source file is ever loaded. It is removed
     * only when it is a real directory holding nothing but regular files named like this plugin's own
     * compiled files. Anything else (a symlink, a nested directory, another module's file) still
     * stops the install.
     */
    private fun removeOwnBytecodeCache(postDir: File, target: File) {
        val cache = File(postDir, "__pycache__")
        if (!cache.exists() && !Files.isSymbolicLink(cache.toPath())) return
        check(!Files.isSymbolicLink(cache.toPath()) && cache.isDirectory) { "Unexpected plugin module entries" }
        val stem = target.name.removeSuffix(".py")
        val files = cache.listFiles().orEmpty()
        check(files.all { f ->
            !Files.isSymbolicLink(f.toPath()) && f.isFile && f.name.startsWith("$stem.") && f.name.endsWith(".pyc")
        }) { "Unexpected plugin module entries" }
        files.forEach { check(it.delete()) { "Cannot remove plugin bytecode cache" } }
        check(cache.delete()) { "Cannot remove plugin bytecode cache" }
    }

    /**
     * Writes the plugin beside the engine. Atomic (temporary file, sync, rename), exact bytes,
     * and fail closed: symlinks, unexpected extra plugin entries or a non-regular target stop the
     * install. Returns the plugin file. Caller holds [CookiePolicyGate.engineLock].
     */
    fun install(engineRoot: File): File = EngineGuard.exclusive { installLocked(engineRoot) }

    private fun installLocked(engineRoot: File): File {
        val root = engineRoot.canonicalFile
        check(root.isDirectory) { "Engine directory is missing" }
        val pluginsRoot = File(root, PLUGIN_ROOT)
        check(!Files.isSymbolicLink(pluginsRoot.toPath())) { "Plugin directory must not be a symlink" }
        val target = File(root, RELATIVE_FILE)
        val packageDir = File(pluginsRoot, PACKAGE)
        val postDir = target.parentFile
        for (dir in listOf(pluginsRoot, packageDir, File(packageDir, "yt_dlp_plugins"), postDir)) {
            check(!Files.isSymbolicLink(dir.toPath())) { "Plugin directory must not be a symlink" }
            check(dir.isDirectory || dir.mkdirs()) { "Cannot prepare plugin directory" }
        }
        check(!Files.isSymbolicLink(target.toPath())) { "Plugin file must not be a symlink" }
        // Only the one expected package may exist, so no other plugin can change cookie behavior.
        val allowedTop = setOf(PACKAGE)
        val extraTop = pluginsRoot.list().orEmpty().filter { it !in allowedTop }
        check(extraTop.isEmpty()) { "Unexpected plugin entries" }
        check(packageDir.list().orEmpty().toSet() == setOf("yt_dlp_plugins")) { "Unexpected plugin package entries" }
        check(File(packageDir, "yt_dlp_plugins").list().orEmpty().toSet() == setOf("postprocessor")) { "Unexpected plugin namespace entries" }
        // A leftover temporary file of this installer (interrupted write) is ours to remove.
        File(postDir, target.name + ".new").takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.delete()
        removeOwnBytecodeCache(postDir, target)
        check(postDir.list().orEmpty().all { it == target.name }) { "Unexpected plugin module entries" }
        val bytes = SOURCE.toByteArray(Charsets.UTF_8)
        if (target.isFile && sha256(target) == SHA256) return target
        check(!target.exists() || target.isFile) { "Plugin target is not a regular file" }
        val temp = File(postDir, target.name + ".new")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        check(sha256(target) == SHA256) { "Plugin verification failed after install" }
        return target
    }
}
