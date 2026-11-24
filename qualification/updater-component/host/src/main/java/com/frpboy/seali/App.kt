package com.frpboy.seali
import android.app.Application
import android.content.Context
/** Test-only context adapter. No native startup, preferences, release discovery or yt-dlp allowed. */
class App : Application() {
 override fun onCreate() { super.onCreate(); context=applicationContext }
 companion object { lateinit var context: Context; fun isPlayBuild()=false; fun isFDroidBuild()=false }
}
