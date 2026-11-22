package com.frpboy.seali.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/** Android boundary shared by the real updater and the isolated extraction fixture. No install or network effect. */
object ApkArchiveReader {
    fun expectation(context: Context, assetSize: Long?, assetDigest: String?): ApkUpdateVerifier.Expectation {
        val info = installed(context)
        return ApkUpdateVerifier.Expectation(
            context.packageName, versionCode(info), certificates(info), assetSize, assetDigest)
    }

    fun archive(context: Context, file: File): ApkUpdateVerifier.Archive {
        val info = archiveInfo(context, file)
        return ApkUpdateVerifier.Archive(
            info?.packageName, info?.let(::versionCode), info?.let(::certificates).orEmpty(),
            file.length(), ApkUpdateVerifier.sha256(file))
    }

    @Suppress("DEPRECATION")
    private fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun archiveInfo(context: Context, file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(
        file.absolutePath,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** Fail-closed identity: exact current signers only, not an arbitrary member of rotation history. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { ApkUpdateVerifier.sha256(it.toByteArray()) }?.toSet().orEmpty()
    }
}
