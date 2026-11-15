package com.frpboy.seali.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.frpboy.seali.App
import com.frpboy.seali.App.Companion.context
import com.frpboy.seali.R
import com.frpboy.seali.util.FileUtil.getFileProvider
import com.frpboy.seali.util.PreferenceUtil.getBoolean
import com.frpboy.seali.util.PreferenceUtil.getInt
import com.frpboy.seali.util.PreferenceUtil.updateLong
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

object UpdateUtil {

    private const val OWNER = "frpboy"
    private const val REPO = "Seali"
    private const val ARM64 = "arm64-v8a"
    private const val ARM32 = "armeabi-v7a"
    private const val X86 = "x86"
    private const val X64 = "x86_64"
    private const val TAG = "UpdateUtil"

    private val client = OkHttpClient()
    private val requestForLatestRelease =
        Request.Builder()
            .url("https://api.github.com/repos/${OWNER}/${REPO}/releases/latest")
            .build()

    private val requestForReleases =
        Request.Builder().url("https://api.github.com/repos/${OWNER}/${REPO}/releases").build()

    private const val ytdlpNightlyBuildRelease =
        "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    suspend fun updateYtDlp(manualConsent: Boolean = false): YoutubeDL.UpdateStatus? =
        withContext(Dispatchers.IO) {
            // Google Play builds ship yt-dlp updates with app updates instead of
            // downloading executable code at runtime.
            if (!RuntimeUpdatePolicy.mayDownload(
                    isPlay = App.isPlayBuild(),
                    isFDroid = App.isFDroidBuild(),
                    automaticEnabled = YT_DLP_AUTO_UPDATE.getBoolean(),
                    automaticConsent = FDROID_RUNTIME_AUTO_UPDATE_CONSENT.getBoolean(),
                    manualConsent = manualConsent,
                )) return@withContext null
            val channel =
                when (PreferenceUtil.effectiveYtdlpUpdateChannel()) {
                    YT_DLP_NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                    else -> YoutubeDL.UpdateChannel.STABLE
                }

            YoutubeDL.getInstance()
                .updateYoutubeDL(appContext = context, updateChannel = channel)
                .also {
                    if (it == YoutubeDL.UpdateStatus.DONE) {
                        YoutubeDL.getInstance().version(context)?.let {
                            PreferenceUtil.encodeString(YT_DLP_VERSION, it)
                        }
                    }
                    val now = System.currentTimeMillis()
                    YT_DLP_UPDATE_TIME.updateLong(now)
                }
        }

    private fun getLatestRelease(): Release =
        client.newCall(requestForReleases).execute().body.use {
            val releaseList = jsonFormat.decodeFromString<List<Release>>(it.string())
            selectLatestRelease(releaseList, stableOnly = UPDATE_CHANNEL.getInt() == STABLE)
                ?: throw Exception("null response")
        }

    /**
     * Picks the newest published release for the configured channel. Drafts are never
     * eligible: private candidate drafts must not surface through the updater.
     */
    internal fun selectLatestRelease(releases: List<Release>, stableOnly: Boolean): Release? =
        releases
            .filter { it.draft != true }
            .filter { if (stableOnly) it.name.toVersion() is Version.Stable else true }
            .maxByOrNull { it.name.toVersion() }

    fun checkForUpdate(context: Context = App.context): Release? {
        CandidateFeaturePolicy.requireAppUpdatesAvailable(App.isPlayBuild(), App.isFDroidBuild())
        val currentVersion = context.getCurrentVersion()
        val latestRelease = getLatestRelease()
        val latestVersion = latestRelease.name.toVersion()
        return if (currentVersion < latestVersion) latestRelease else null
    }

    private fun Context.getCurrentVersion(): Version =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager
                .getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
                .toVersion()
        } else {
            packageManager.getPackageInfo(packageName, 0).versionName.toVersion()
        }

    private fun Context.getLatestApk() = File(getExternalFilesDir("apk"), "latest.apk")

    fun installLatestApk(context: Context = App.context) =
        context.run {
            kotlin
                .runCatching {
                    CandidateFeaturePolicy.requireAppUpdatesAvailable(
                        App.isPlayBuild(),
                        App.isFDroidBuild(),
                    )
                    when (val validation = validateDownloadedApk()) {
                        is ApkValidation.Rejected -> {
                            ToastUtil.makeToast(validation.reasonRes)
                            return@runCatching
                        }
                        ApkValidation.Indeterminate,
                        ApkValidation.Valid -> Unit
                    }
                    val contentUri =
                        FileProvider.getUriForFile(this, getFileProvider(), getLatestApk())
                    val intent =
                        Intent(Intent.ACTION_VIEW).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setDataAndType(contentUri, "application/vnd.android.package-archive")
                        }
                    startActivity(intent)
                }
                .onFailure { throwable: Throwable ->
                    throwable.printStackTrace()
                    ToastUtil.makeToast(R.string.app_update_failed)
                }
        }

    suspend fun deleteOutdatedApk(context: Context = App.context) =
        context.runCatching {
            val apkFile = getLatestApk()
            if (apkFile.exists()) {
                val apkVersion =
                    context.packageManager
                        .getPackageArchiveInfo(apkFile.absolutePath, 0)
                        ?.versionName
                        .toVersion()
                if (apkVersion <= context.getCurrentVersion()) {
                    apkFile.delete()
                }
            }
        }

    suspend fun downloadApk(
        context: Context = App.context,
        release: Release,
    ): Flow<DownloadStatus> =
        withContext(Dispatchers.IO) {
            CandidateFeaturePolicy.requireAppUpdatesAvailable(App.isPlayBuild(), App.isFDroidBuild())
            val apkVersion =
                context.packageManager
                    .getPackageArchiveInfo(context.getLatestApk().absolutePath, 0)
                    ?.versionName
                    .toVersion()

            Log.d(TAG, apkVersion.toString())

            if (apkVersion >= release.name.toVersion()) {
                return@withContext flow<DownloadStatus> {
                    emit(DownloadStatus.Finished(context.getLatestApk()))
                }
            }

            val abiList = Build.SUPPORTED_ABIS
            val preferredArch = abiList.firstOrNull() ?: return@withContext emptyFlow()

            val targetAsset =
                release.assets?.find {
                    return@find it.name?.contains(preferredArch) ?: false
                } ?: return@withContext emptyFlow()
            val targetUrl = targetAsset.browserDownloadUrl ?: return@withContext emptyFlow()
            if (!isTrustedDownloadUrl(targetUrl)) return@withContext emptyFlow()
            val expectedSha256 = expectedSha256(release = release, assetName = targetAsset.name)
            try {
                val response = executeFollowingTrustedRedirects(Request.Builder().url(targetUrl).build())
                val responseBody = response.body
                return@withContext flow {
                        responseBody.downloadFileWithProgress(context.getLatestApk()).collect {
                            status ->
                            if (status is DownloadStatus.Finished && expectedSha256 != null) {
                                val actual = sha256Hex(status.file.readBytes())
                                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                                    status.file.delete()
                                    throw Exception("update package checksum mismatch")
                                }
                            }
                            emit(status)
                        }
                    }
                    .flowOn(Dispatchers.IO)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            emptyFlow()
        }

    private const val MAX_REDIRECTS = 5

    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    /** Asset downloads must stay on GitHub hosts; redirects anywhere else are refused. */
    internal fun isTrustedDownloadUrl(url: String): Boolean {
        val parsed = runCatching { okhttp3.HttpUrl.get(url) }.getOrNull() ?: return false
        return parsed.scheme == "https" && isTrustedDownloadHost(parsed.host)
    }

    private fun isTrustedDownloadHost(host: String): Boolean =
        host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")

    private fun executeFollowingTrustedRedirects(request: Request): okhttp3.Response {
        val noRedirectClient =
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var currentRequest = request
        repeat(MAX_REDIRECTS + 1) { attempt ->
            val response = noRedirectClient.newCall(currentRequest).execute()
            if (response.code !in redirectCodes) return response
            val location = response.header("Location")
            response.close()
            val resolved =
                location?.let { currentRequest.url.resolve(it) }
                    ?: throw Exception("update download redirect without location")
            if (attempt == MAX_REDIRECTS) throw Exception("too many update download redirects")
            if (resolved.scheme != "https" || !isTrustedDownloadHost(resolved.host)) {
                throw Exception("update download redirect to untrusted host: ${resolved.host}")
            }
            currentRequest = currentRequest.newBuilder().url(resolved).build()
        }
        throw Exception("too many update download redirects")
    }

    /**
     * Reads the expected SHA-256 for [assetName] from a SHA256SUMS asset published with
     * the release, when one exists. Returns null when the release publishes no sums;
     * the signature-continuity check in [validateDownloadedApk] remains the integrity
     * anchor in that case.
     */
    private fun expectedSha256(release: Release, assetName: String?): String? {
        assetName ?: return null
        val sumsAsset =
            release.assets?.firstOrNull {
                it.name == "SHA256SUMS" || it.name == "SHA256SUMS.txt"
            } ?: return null
        val sumsUrl = sumsAsset.browserDownloadUrl?.takeIf { isTrustedDownloadUrl(it) }
            sumsUrl ?: return null
        return runCatching {
                executeFollowingTrustedRedirects(Request.Builder().url(sumsUrl).build()).body.use {
                    body ->
                    body.string().lineSequence().firstNotNullOfOrNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size == 2 && parts[1].trimStart('*') == assetName) parts[0]
                        else null
                    }
                }
            }
            .getOrNull()
    }

    /** Outcome of the pre-install trust checks on the downloaded update package. */
    sealed class ApkValidation {
        object Valid : ApkValidation()

        /** The platform could not parse the package; Android enforces at install time. */
        object Indeterminate : ApkValidation()

        data class Rejected(val reasonRes: Int) : ApkValidation()
    }

    /**
     * Pre-install trust checks on the downloaded update package: it must parse as an
     * APK, carry this installation's exact package name, be strictly newer by
     * versionCode, support this device's API level, and be signed by the same
     * certificate. Android enforces signature and version rules at install time too;
     * these checks exist so the user gets an explicit reason instead of a silent
     * failed install handoff.
     */
    @Suppress("DEPRECATION")
    fun validateDownloadedApk(context: Context = App.context): ApkValidation =
        runCatching {
                val apkFile = context.getLatestApk()
                if (!apkFile.exists()) return ApkValidation.Indeterminate
                val packageManager = context.packageManager
                val archiveInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageManager.getPackageArchiveInfo(
                            apkFile.absolutePath,
                            PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                    } else {
                        packageManager.getPackageArchiveInfo(
                            apkFile.absolutePath,
                            PackageManager.GET_SIGNATURES,
                        )
                    }
                val archiveApplicationInfo = archiveInfo?.applicationInfo
                if (archiveInfo == null || archiveApplicationInfo == null) {
                    return ApkValidation.Rejected(R.string.app_update_invalid_apk)
                }
                if (archiveInfo.packageName != context.packageName) {
                    return ApkValidation.Rejected(R.string.app_update_package_mismatch)
                }
                val currentInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageManager.getPackageInfo(
                            context.packageName,
                            PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                    } else {
                        packageManager.getPackageInfo(
                            context.packageName,
                            PackageManager.GET_SIGNATURES,
                        )
                    }
                val archiveVersionCode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archiveInfo.longVersionCode
                    else archiveInfo.versionCode.toLong()
                val currentVersionCode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) currentInfo.longVersionCode
                    else currentInfo.versionCode.toLong()
                if (!isUpgrade(archiveVersionCode, currentVersionCode)) {
                    return ApkValidation.Rejected(R.string.app_update_not_newer)
                }
                if (!supportsDevice(archiveApplicationInfo.minSdkVersion, Build.VERSION.SDK_INT)) {
                    return ApkValidation.Rejected(R.string.app_update_requires_newer_android)
                }
                val archiveHashes = signatureHashes(archiveInfo)
                val currentHashes = signatureHashes(currentInfo)
                if (archiveHashes.isEmpty() || currentHashes.isEmpty()) {
                    return ApkValidation.Indeterminate
                }
                if (archiveHashes.toSet() != currentHashes.toSet()) {
                    return ApkValidation.Rejected(R.string.app_update_signature_mismatch)
                }
                ApkValidation.Valid
            }
            .getOrElse { ApkValidation.Indeterminate }

    internal fun isUpgrade(archiveVersionCode: Long, currentVersionCode: Long): Boolean =
        archiveVersionCode > currentVersionCode

    internal fun supportsDevice(minSdkVersion: Int, deviceSdk: Int): Boolean =
        minSdkVersion <= deviceSdk

    private fun signatureHashes(info: PackageInfo?): List<String> {
        info ?: return emptyList()
        val signatures: Array<android.content.pm.Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { signingInfo ->
                    if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                    else signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION") info.signatures
            }
        return signatures?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ResponseBody.downloadFileWithProgress(saveFile: File): Flow<DownloadStatus> =
        flow {
                emit(DownloadStatus.Progress(0))

                var deleteFile = true

                try {
                    byteStream().use { inputStream ->
                        saveFile.outputStream().use { outputStream ->
                            val totalBytes = contentLength()
                            val data = ByteArray(8_192)
                            var progressBytes = 0L

                            while (true) {
                                val bytes = inputStream.read(data)

                                if (bytes == -1) {
                                    break
                                }

                                outputStream.channel
                                outputStream.write(data, 0, bytes)
                                progressBytes += bytes
                                emit(
                                    DownloadStatus.Progress(
                                        percent = ((progressBytes * 100) / totalBytes).toInt()
                                    )
                                )
                            }

                            when {
                                progressBytes < totalBytes -> throw Exception("missing bytes")
                                progressBytes > totalBytes -> throw Exception("too many bytes")
                                else -> deleteFile = false
                            }
                        }
                    }

                    emit(DownloadStatus.Finished(saveFile))
                } finally {
                    if (deleteFile) {
                        saveFile.delete()
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    @Serializable
    data class Release(
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("tag_name") val tagName: String? = null,
        val name: String? = null,
        val draft: Boolean? = null,
        @SerialName("prerelease") val preRelease: Boolean? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<AssetsItem>? = null,
        val body: String? = null,
    )

    @Serializable
    data class AssetsItem(
        val name: String? = null,
        @SerialName("content_type") val contentType: String? = null,
        val size: Int? = null,
        @SerialName("download_count") val downloadCount: Int? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    sealed class DownloadStatus {
        object NotYet : DownloadStatus()

        data class Progress(val percent: Int) : DownloadStatus()

        data class Finished(val file: File) : DownloadStatus()
    }

    private val pattern = Pattern.compile("""v?(\d+)\.(\d+)\.(\d+)(-(\w+)\.(\d+))?""")
    private val EMPTY_VERSION = Version.Stable()

    fun String?.toVersion(): Version =
        this?.run {
            val matcher = pattern.matcher(this)
            if (matcher.find()) {
                val major = matcher.group(1)?.toInt() ?: 0
                val minor = matcher.group(2)?.toInt() ?: 0
                val patch = matcher.group(3)?.toInt() ?: 0
                val buildNumber = matcher.group(6)?.toInt() ?: 0
                when (matcher.group(5)) {
                    "alpha" -> Version.Alpha(major, minor, patch, buildNumber)
                    "beta" -> Version.Beta(major, minor, patch, buildNumber)
                    "rc" -> Version.ReleaseCandidate(major, minor, patch, buildNumber)
                    else -> Version.Stable(major, minor, patch)
                }
            } else EMPTY_VERSION
        } ?: EMPTY_VERSION

    sealed class Version(val major: Int, val minor: Int, val patch: Int, val build: Int = 0) :
        Comparable<Version> {
        companion object {
            // private const val ABI = 1L
            private const val BUILD = 10L
            private const val VARIANT = 100L
            private const val PATCH = 10_000L
            private const val MINOR = 1_000_000L
            private const val MAJOR = 100_000_000L

            private const val STABLE = VARIANT * 4
            private const val ALPHA = VARIANT * 1
            private const val BETA = VARIANT * 2
            private const val RELEASE_CANDIDATE = VARIANT * 3
        }

        abstract fun toVersionName(): String

        abstract fun toNumber(): Long

        class Alpha(
            versionMajor: Int = 0,
            versionMinor: Int = 0,
            versionPatch: Int = 0,
            versionBuild: Int = 0,
        ) : Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-alpha.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + ALPHA
        }

        class Beta(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
            Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-beta.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + BETA
        }

        class ReleaseCandidate(
            versionMajor: Int,
            versionMinor: Int,
            versionPatch: Int,
            versionBuild: Int,
        ) : Version(versionMajor, versionMinor, versionPatch, versionBuild) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}-rc.$build"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + RELEASE_CANDIDATE
        }

        class Stable(versionMajor: Int = 0, versionMinor: Int = 0, versionPatch: Int = 0) :
            Version(versionMajor, versionMinor, versionPatch) {
            override fun toVersionName(): String = "${major}.${minor}.${patch}"

            override fun toNumber(): Long =
                major * MAJOR + minor * MINOR + patch * PATCH + build * BUILD + STABLE
            // Prioritize stable versions

        }

        override operator fun compareTo(other: Version): Int =
            this.toNumber().compareTo(other.toNumber())
    }
}
