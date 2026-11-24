package com.frpboy.seali.util
import android.content.Context
/** Exact FileUtil extension expression copied; unrelated FileUtil omitted. */
object FileUtil { fun Context.getFileProvider() = "$packageName.provider" }
object PreferenceUtil {
 fun String.getBoolean(): Boolean = error("preferences forbidden in component host")
 fun String.getInt(): Int = error("preferences forbidden in component host")
 fun String.updateLong(value: Long): Unit = error("preferences forbidden in component host")
 fun effectiveYtdlpUpdateChannel(): String = error("yt-dlp forbidden")
 fun encodeString(key:String,value:String): Unit = error("preferences forbidden")
}
const val YT_DLP_AUTO_UPDATE="forbidden"
const val FDROID_RUNTIME_AUTO_UPDATE_CONSENT="forbidden"
const val YT_DLP_NIGHTLY="nightly"
const val YT_DLP_VERSION="forbidden"
const val YT_DLP_UPDATE_TIME="forbidden"
const val UPDATE_CHANNEL="forbidden"
const val STABLE=0
