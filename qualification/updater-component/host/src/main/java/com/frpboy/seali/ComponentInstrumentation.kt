package com.frpboy.seali

import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import com.frpboy.seali.util.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Disposable component test, not the Seali Application/UI or installer-screen automation. */
class ComponentInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output=Bundle()
        try {
            runBlocking { exercise() }
            output.putString("result", "COMPONENT_SUCCESS")
            finish(-1, output)
        } catch (t: Throwable) {
            output.putString("result", "COMPONENT_FAILURE ${t.javaClass.name}: ${t.message}")
            android.util.Log.e("ComponentQualification", "test failure",t)
            finish(0, output)
        }
    }
    private fun evidence(text:String) { android.util.Log.i("ComponentQualification",text) }
    private suspend fun exercise() {
        val c=targetContext
        check(c.packageName=="com.frpboy.seali.updaterqualification")
        check(CandidateFeaturePolicy.appUpdatesEnabled("generic"))
        check(!CandidateFeaturePolicy.appUpdatesEnabled("play"))
        val root=File(c.filesDir,"update-apks/verified").apply { mkdirs() }
        val client=okhttp3.OkHttpClient()
        val index=withContext(Dispatchers.IO) {
            client.newCall(okhttp3.Request.Builder().url("http://10.0.2.2:8765/index.json").build()).execute().use { response ->
                check(response.isSuccessful); org.json.JSONObject(response.body.string())
            }
        }
        fun release(name:String, route:String=name, badDigest:Boolean=false):UpdateUtil.Release {
            val row=index.getJSONObject(name)
            return UpdateUtil.Release(tagName="v1.0.1",assets=listOf(UpdateUtil.AssetsItem(
                name="component-${android.os.Build.SUPPORTED_ABIS.first()}.apk",
                size=row.getLong("size"),digest=if(badDigest) "sha256:"+"0".repeat(64) else row.getString("digest"),
                browserDownloadUrl="http://10.0.2.2:8765/$route")))
        }
        suspend fun collect(r:UpdateUtil.Release,f:File):List<UpdateUtil.DownloadStatus> =
            UpdateUtil.downloadApk(c,r,f).toList()
        val good=File(root,"verified.apk")
        val statuses=collect(release("newer.apk"),good)
        check(statuses.last() is UpdateUtil.DownloadStatus.Finished)
        check(statuses.indexOfFirst { it is UpdateUtil.DownloadStatus.Verifying } in 0 until statuses.lastIndex)
        val expected=ApkArchiveReader.expectation(c,good.length(),"sha256:"+ApkUpdateVerifier.sha256(good))
        val actual=ApkArchiveReader.archive(c,good)
        check(actual.packageName==c.packageName && actual.versionCode==11L && expected.installedVersionCode==10L)
        check(ApkUpdateVerifier.verify(expected,actual)==ApkUpdateVerifier.Result.Ok)
        evidence("PASS actual HTTP wrapper and PackageManager same-key version10-to11 archive, no modified expectation")
        suspend fun refuse(name:String, reason:ApkUpdateVerifier.Reason, badDigest:Boolean=false) {
            val f=File(root,"refused.apk")
            var thrown:Throwable?=null
            try { collect(release(name,badDigest=badDigest),f) } catch(t:Throwable) { thrown=t }
            check(thrown is UpdateUtil.UpdateRefusedException && thrown.reason==reason) { "wrong refusal $name: $thrown" }
            check(!f.exists());evidence("PASS refusal $name $reason and file deletion")
        }
        refuse("wrong-package.apk",ApkUpdateVerifier.Reason.PACKAGE_MISMATCH)
        refuse("wrong-key.apk",ApkUpdateVerifier.Reason.CERTIFICATE_MISMATCH)
        refuse("same.apk",ApkUpdateVerifier.Reason.NOT_NEWER)
        refuse("older.apk",ApkUpdateVerifier.Reason.NOT_NEWER)
        refuse("malformed.apk",ApkUpdateVerifier.Reason.UNREADABLE_ARCHIVE)
        refuse("newer.apk",ApkUpdateVerifier.Reason.DIGEST_MISMATCH,true)
        for(route in listOf("http-error","truncated","oversize")) {
            val f=File(root,"transport.apk");var failure=false
            try { collect(release("newer.apk",route),f) } catch(_:Exception) { failure=true }
            check(failure && !f.exists());evidence("PASS transport failure $route cleanup")
        }
        val cancelFile=File(root,"cancel.apk")
        withTimeout(10000) {
            val job=launch(Dispatchers.IO) { collect(release("newer.apk","slow"),cancelFile) }
            withTimeout(5000) { while(!cancelFile.exists() || cancelFile.length()==0L) delay(20) }
            job.cancelAndJoin()
        }
        check(!cancelFile.exists());evidence("PASS actual wrapper cancellation cleanup")
        val intent=UpdateUtil.installerIntent(release("newer.apk"),good,c)
        check(intent.action==Intent.ACTION_VIEW && intent.type=="application/vnd.android.package-archive")
        check(intent.data?.scheme=="content" && intent.data?.authority==c.packageName+".provider")
        check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION !=0)
        check(intent.resolveActivity(c.packageManager)!=null) { "no actual installer resolution" }
        c.contentResolver.openInputStream(intent.data!!)!!.use { check(ApkUpdateVerifier.sha256(it.readBytes())==actual.fileSha256) }
        c.grantUriPermission("com.android.shell",intent.data!!,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        File(c.filesDir,"component-uri").writeText(intent.data.toString())
        File(c.filesDir,"upgrade-sentinel").writeText("component-data-preserved")
        evidence("PASS FileProvider URI, MIME, read flag, installer resolution and self read; URI="+intent.data)
        val state=UpdateSessionState();val old=(state.claim(UpdateSessionState.Origin.MANUAL) as UpdateSessionState.Claim.Started).id
        check(state.dismiss(old));check(!state.requestHandoff(old,true));check(state.consumeEffect(old)==null)
        evidence("PASS unchanged state stale-session refusal; not full ViewModel/UI launch proof")
        evidence("COMPONENT_SUCCESS API="+android.os.Build.VERSION.SDK_INT)
    }
}
