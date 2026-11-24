package com.frpboy.seali.util

import java.io.File
import java.security.MessageDigest

/**
 * Decides whether a downloaded APK may be handed to the system installer. Pure logic: the Android glue reads the
 * archive facts (package name, version code, signing certificates) and passes them in, so every rule is unit-tested.
 * The system installer still enforces signature and version rules; this check exists so the app never offers an
 * install it can already tell is wrong, and so a substituted or truncated download is refused before the prompt.
 */
object ApkUpdateVerifier {
    /** What the running app is, and what the release listing promised about the file. */
    data class Expectation(
        val packageName: String,
        val installedVersionCode: Long,
        /** SHA-256 (lowercase hex) of every signing certificate of the installed app. Must not be empty. */
        val installedSigningCertSha256: Set<String>,
        val assetSize: Long?,
        /** SHA-256 (lowercase hex) published for the asset by the release listing. Required. */
        val assetSha256: String?,
    )

    /** Facts read from the downloaded archive. */
    data class Archive(
        val packageName: String?,
        val versionCode: Long?,
        val signingCertSha256: Set<String>,
        val fileSize: Long,
        val fileSha256: String,
    )

    sealed class Result {
        object Ok : Result()
        data class Refused(val reason: Reason) : Result()
    }

    enum class Reason {
        NO_PUBLISHED_DIGEST, NO_INSTALLED_CERTIFICATE, SIZE_MISMATCH, DIGEST_MISMATCH, UNREADABLE_ARCHIVE,
        PACKAGE_MISMATCH, NOT_NEWER, NO_ARCHIVE_CERTIFICATE, CERTIFICATE_MISMATCH,
    }

    fun verify(expected: Expectation, archive: Archive): Result {
        val published = expected.assetSha256?.let(::normalizeDigest)
            ?: return refuse(Reason.NO_PUBLISHED_DIGEST)
        if (expected.installedSigningCertSha256.isEmpty()) return refuse(Reason.NO_INSTALLED_CERTIFICATE)
        if (expected.assetSize != null && expected.assetSize != archive.fileSize) return refuse(Reason.SIZE_MISMATCH)
        if (!constantTimeEquals(published, archive.fileSha256.lowercase())) return refuse(Reason.DIGEST_MISMATCH)
        val pkg = archive.packageName ?: return refuse(Reason.UNREADABLE_ARCHIVE)
        val code = archive.versionCode ?: return refuse(Reason.UNREADABLE_ARCHIVE)
        if (pkg != expected.packageName) return refuse(Reason.PACKAGE_MISMATCH)
        if (code <= expected.installedVersionCode) return refuse(Reason.NOT_NEWER)
        if (archive.signingCertSha256.isEmpty()) return refuse(Reason.NO_ARCHIVE_CERTIFICATE)
        // The archive must be signed by exactly the certificate set of the installed app: a superset or a
        // different member is a different signing identity.
        if (archive.signingCertSha256.map { it.lowercase() }.toSet() !=
            expected.installedSigningCertSha256.map { it.lowercase() }.toSet()) {
            return refuse(Reason.CERTIFICATE_MISMATCH)
        }
        return Result.Ok
    }

    /** GitHub lists asset digests as "sha256:<hex>". Anything else (other algorithm, bad length) is no digest. */
    fun normalizeDigest(raw: String): String? {
        val hex = raw.trim().lowercase().removePrefix("sha256:")
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun refuse(reason: Reason) = Result.Refused(reason)
}
