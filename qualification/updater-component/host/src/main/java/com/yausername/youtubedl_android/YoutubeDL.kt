package com.yausername.youtubedl_android
import android.content.Context
/** Fail-fast compile adapter. Never executes or retrieves native/runtime code. */
class YoutubeDL {
 enum class UpdateStatus { DONE }; enum class UpdateChannel { NIGHTLY, STABLE }
 fun updateYoutubeDL(appContext:Context,updateChannel:UpdateChannel):UpdateStatus = error("yt-dlp forbidden")
 fun version(context:Context):String = error("yt-dlp forbidden")
 companion object { fun getInstance():YoutubeDL = error("yt-dlp forbidden") }
}
