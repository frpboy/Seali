package com.frpboy.seali.util

import android.content.Context
import android.content.Intent
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
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
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
        client.newCall(requestForReleases).execute().use { response ->
            check(response.isSuccessful) { "Release listing is unavailable" }
            val releaseList = jsonFormat.decodeFromString<List<Release>>(response.body.string())
            selectLatestRelease(releaseList, UPDATE_CHANNEL.getInt() == STABLE)
                ?: error("No eligible release for this update channel")
        }

    /** App release channel only. This does NOT change yt-dlp's separate explicit Nightly consent. */
    fun selectLatestRelease(releases: List<Release>, stableOnly: Boolean): Release? =
        releases.filter { release ->
            release.draft != true && releaseVersionLabel(release) != null &&
                (!stableOnly || (release.preRelease != true && releaseVersionLabel(release).toVersion() is Version.Stable))
        }.maxByOrNull { releaseVersionLabel(it).toVersion() }

    fun releaseVersionLabel(release: Release): String? =
        listOfNotNull(release.tagName, release.name).firstOrNull {
            Regex("""v?\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)\.\d+)?""").matches(it)
        }

    fun checkForUpdate(context: Context = App.context): Release? {
        CandidateFeaturePolicy.requireAppUpdatesAvailable()
        val currentVersion = context.getCurrentVersion()
        val latestRelease = getLatestRelease()
        val latestVersion = releaseVersionLabel(latestRelease).toVersion()
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

    /** Caller owns displaying failure. Must run on IO because it re-checks the archive before every hand-off. */
    fun installLatestApk(release: Release, context: Context = App.context) = context.run {
        CandidateFeaturePolicy.requireAppUpdatesAvailable()
        requireGitHubDistribution()
        val asset = Build.SUPPORTED_ABIS.firstOrNull()?.let { selectAsset(release, it) }
            ?: error("No APK for this device")
        verifyOrDelete(this, asset, getLatestApk())
        val contentUri = FileProvider.getUriForFile(this, getFileProvider(), getLatestApk())
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(contentUri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
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

    /** The asset for this device's preferred ABI, or null. */
    fun selectAsset(release: Release, preferredArch: String): AssetsItem? =
        release.assets?.filter { asset ->
            val name = asset.name.orEmpty()
            name.endsWith("-$preferredArch.apk") || name.endsWith("_$preferredArch.apk")
        }?.singleOrNull()

    /** A missing or ambiguous asset is a visible error, never a silently empty download flow. */
    fun requireDownloadAsset(release: Release, preferredArch: String?): AssetsItem {
        val asset = preferredArch?.let { selectAsset(release, it) }
            ?: error("No unique APK for this device")
        check(!asset.browserDownloadUrl.isNullOrBlank()) { "The APK has no download URL" }
        check(asset.size != null && asset.size > 0) { "The APK has no valid published size" }
        check(asset.digest?.let { ApkUpdateVerifier.normalizeDigest(it) } != null) { "The APK has no published SHA256" }
        return asset
    }

    /** "14.4 MB" style size of the asset this device would download, or null. */
    fun updateSizeLabel(release: Release): String? =
        Build.SUPPORTED_ABIS.firstOrNull()?.let { selectAsset(release, it) }?.size
            ?.takeIf { it > 0 }
            ?.let { "%.1f MB".format(it / 1_000_000.0) }

    /** Release date as yyyy-MM-dd from GitHub's ISO timestamp, or null. */
    fun releaseDateLabel(release: Release): String? =
        (release.publishedAt ?: release.createdAt)?.take(10)?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }

    private fun requireGitHubDistribution() {
        check(!App.isPlayBuild() && !App.isFDroidBuild()) {
            "In-app APK updates exist only in the GitHub build"
        }
    }

    suspend fun downloadApk(
        context: Context = App.context,
        release: Release,
    ): Flow<DownloadStatus> =
        withContext(Dispatchers.IO) {
            CandidateFeaturePolicy.requireAppUpdatesAvailable()
            requireGitHubDistribution()
            val asset = requireDownloadAsset(release, Build.SUPPORTED_ABIS.firstOrNull())
            val targetUrl = checkNotNull(asset.browserDownloadUrl)

            val apkVersion =
                context.packageManager
                    .getPackageArchiveInfo(context.getLatestApk().absolutePath, 0)
                    ?.versionName
                    .toVersion()

            Log.d(TAG, apkVersion.toString())

            // A previously downloaded file is trusted no more than a new one: it goes through the same checks.
            if (apkVersion >= releaseVersionLabel(release).toVersion()) {
                return@withContext flow<DownloadStatus> {
                    val file = context.getLatestApk()
                    emit(DownloadStatus.Verifying)
                    verifyOrDelete(context, asset, file)
                    emit(DownloadStatus.Finished(file))
                }.flowOn(Dispatchers.IO)
            }

            val request = Request.Builder().url(targetUrl).build()
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body
                return@withContext responseBody.downloadFileWithProgress(context.getLatestApk()).transform { status ->
                    if (status is DownloadStatus.Finished) {
                        emit(DownloadStatus.Verifying)
                        verifyOrDelete(context, asset, status.file)
                    }
                    emit(status)
                }.flowOn(Dispatchers.IO)
            } catch (e: Exception) {
                throw e
            }
        }

    /** A downloaded update that failed an integrity check. Carries a reason the UI can show; the file is already deleted. */
    class UpdateRefusedException(val reason: ApkUpdateVerifier.Reason) :
        Exception("Update refused: $reason")

    /** Text for the failure toast: names the refusal when there is one, otherwise the generic failure. */
    fun failureMessage(context: Context, t: Throwable): String =
        if (t is UpdateRefusedException)
            context.getString(R.string.app_update_refused, refusalText(t.reason))
        else context.getString(R.string.app_update_failed)

    fun refusalText(reason: ApkUpdateVerifier.Reason): String =
        when (reason) {
            ApkUpdateVerifier.Reason.NO_PUBLISHED_DIGEST -> "the release has no published checksum"
            ApkUpdateVerifier.Reason.SIZE_MISMATCH,
            ApkUpdateVerifier.Reason.DIGEST_MISMATCH -> "the download does not match the published file"
            ApkUpdateVerifier.Reason.PACKAGE_MISMATCH -> "it is a different app package"
            ApkUpdateVerifier.Reason.NOT_NEWER -> "it is not newer than the installed version"
            ApkUpdateVerifier.Reason.CERTIFICATE_MISMATCH,
            ApkUpdateVerifier.Reason.NO_ARCHIVE_CERTIFICATE,
            ApkUpdateVerifier.Reason.NO_INSTALLED_CERTIFICATE -> "it is not signed by the same key as the installed app"
            ApkUpdateVerifier.Reason.UNREADABLE_ARCHIVE -> "the downloaded file could not be read"
        }

    /** Throws and deletes the file unless it passes [ApkUpdateVerifier]; the system installer is never reached otherwise. */
    private fun verifyOrDelete(context: Context, asset: AssetsItem, file: File) {
        val result =
            runCatching {
                    ApkUpdateVerifier.verify(
                        ApkArchiveReader.expectation(context, asset.size, asset.digest),
                        ApkArchiveReader.archive(context, file),
                    )
                }
                .getOrElse { ApkUpdateVerifier.Result.Refused(ApkUpdateVerifier.Reason.UNREADABLE_ARCHIVE) }
        if (result is ApkUpdateVerifier.Result.Refused) {
            file.delete()
            throw UpdateRefusedException(result.reason)
        }
    }

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
        val size: Long? = null,
        /** GitHub's "sha256:<hex>" asset digest; the update is refused without one. */
        val digest: String? = null,
        @SerialName("download_count") val downloadCount: Int? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    sealed class DownloadStatus {
        object NotYet : DownloadStatus()
        object Verifying : DownloadStatus()

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
