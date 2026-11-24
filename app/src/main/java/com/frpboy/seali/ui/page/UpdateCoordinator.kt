package com.frpboy.seali.ui.page

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.frpboy.seali.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Activity-owned, not persisted across process death. All entry/effect methods called on MAIN. */
internal class UpdateCoordinator(application: Application, private val environment: UpdateEnvironment) : AndroidViewModel(application) {
    internal data class Ui(
        val id: String? = null,
        val release: UpdateUtil.Release? = null,
        val status: UpdateUtil.DownloadStatus = UpdateUtil.DownloadStatus.NotYet,
        val message: String? = null,
        val checking: Boolean = false,
        val effect: UpdateSessionState.Effect? = null,
        val intent: Intent? = null,
        val awaitingPermission: Boolean = false,
        val confirmMaintenance: Boolean = false,
    )
    private val session = UpdateSessionState()
    private val store = UpdateFileStore(File(application.filesDir, "update-apks"))
    private val mutable = MutableStateFlow(Ui())
    internal val ui = mutable.asStateFlow()
    private var job: Job? = null
    private var fileSession: UpdateFileStore.Session? = null
    private var stopping = false
    private var cleaned = false
    private val context get() = getApplication<Application>()
    private fun enabled() = environment.enabled()
    private fun assertMain() = kotlin.check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())

    fun check(manual: Boolean) {
        assertMain()
        if (!enabled() || stopping || mutable.value.confirmMaintenance) return
        when (val claim = session.claim(if (manual) UpdateSessionState.Origin.MANUAL else UpdateSessionState.Origin.AUTOMATIC)) {
            is UpdateSessionState.Claim.Focus -> return // The single root host already displays this session.
            UpdateSessionState.Claim.QuietBusy -> return
            is UpdateSessionState.Claim.Started -> {
                mutable.value = Ui(id = claim.id, checking = manual)
                job = viewModelScope.launch {
                    try {
                        val release = withContext(Dispatchers.IO) {
                            if (!cleaned) { store.cleanAbandonedBeforeSessions(); cleaned = true }
                            environment.discover()
                        }
                        ensureActive()
                        if (!session.isCurrent(claim.id)) return@launch
                        if (release == null) {
                            session.dismiss(claim.id)
                            mutable.value = Ui(message = if (manual) environment.upToDateMessage() else null)
                        } else if (session.discovered(claim.id)) {
                            mutable.value = Ui(id = claim.id, release = release)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        if (session.fail(claim.id)) mutable.value = Ui(message = "Could not check for updates. Try again.")
                        else if (mutable.value.id == claim.id) mutable.value = Ui()
                    }
                }
            }
        }
    }

    internal fun download(id: String) {
        assertMain()
        if (!enabled() || stopping || !session.startDownload(id)) return
        val release = mutable.value.release ?: return
        job = viewModelScope.launch {
            try {
                val reserved = withContext(Dispatchers.IO + NonCancellable) {
                    val asset = environment.asset(release)
                    store.begin(checkNotNull(asset.digest?.let { ApkUpdateVerifier.normalizeDigest(it) }), checkNotNull(asset.size)).also { fileSession = it }
                }
                // Record the reservation even if the session was invalidated, so finally/dismiss can release it.
                fileSession = reserved
                ensureActive()
                environment.download(release, reserved.temporary).collect { status ->
                    ensureActive()
                    if (!session.isCurrent(id)) return@collect
                    if (status is UpdateUtil.DownloadStatus.Verifying) session.verifying(id)
                    if (status is UpdateUtil.DownloadStatus.Finished) {
                        withContext(Dispatchers.IO) { store.promoteVerified(reserved) }
                        ensureActive()
                        if (session.ready(id)) prepareHandoff(id)
                    } else mutable.value = mutable.value.copy(status = status)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { stop(id, "Update was not installed. The download, verification or retained update storage check failed.") }
        }
    }

    private suspend fun prepareHandoff(id: String) {
        if (!session.isCurrent(id)) return
        val allowed = environment.hasInstallPermission()
        if (!session.requestHandoff(id, allowed)) return
        fileSession?.let { mutable.value = mutable.value.copy(status = UpdateUtil.DownloadStatus.Finished(it.temporary)) }
        if (allowed) prepareIntent(id) else mutable.value = mutable.value.copy(effect = UpdateSessionState.Effect.OPEN_PERMISSION)
    }

    private suspend fun prepareIntent(id: String) {
        val release = mutable.value.release ?: return
        val reserved = fileSession ?: return
        val intent = withContext(Dispatchers.IO) {
            store.promoteVerified(reserved)
            val file = store.retainForHandoff(reserved)
            environment.installerIntent(release, file)
        }
        currentCoroutineContext().ensureActive()
        if (session.isCurrent(id)) mutable.value = mutable.value.copy(effect = UpdateSessionState.Effect.LAUNCH_INSTALLER, intent = intent)
    }

    internal fun consume(id: String): Pair<UpdateSessionState.Effect, Intent?>? {
        assertMain()
        if (stopping || !session.isCurrent(id)) return null
        val effect = session.consumeEffect(id) ?: return null
        val intent = mutable.value.intent
        mutable.value = mutable.value.copy(effect = null, intent = null,
            awaitingPermission = effect == UpdateSessionState.Effect.OPEN_PERMISSION)
        return effect to intent
    }
    internal fun stillCurrent(id: String): Boolean = !stopping && session.isCurrent(id)
    /** Deliberate in-app Continue, NOT an unidentifiable ActivityResult callback. */
    internal fun permissionContinue(id: String) {
        assertMain()
        if (!session.isCurrent(id) || stopping || !mutable.value.awaitingPermission) return
        mutable.value = mutable.value.copy(awaitingPermission = false)
        val granted = environment.hasInstallPermission()
        if (session.permissionResult(id, granted)) {
            job = viewModelScope.launch {
                try { prepareIntent(id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { stop(id, "Could not prepare the Android installer. Check for updates again.") }
            }
        } else if (!granted) stop(id, "Android permission was not granted. Allow Seali to install updates, then check again.")
    }
    internal fun requestMaintenance() {
        assertMain()
        if (!enabled() || stopping) return
        if (session.snapshot() != null) return // Existing root host is focused. Never delete during an owned session.
        mutable.value = Ui(confirmMaintenance = true)
    }
    internal fun cancelMaintenance() { assertMain(); mutable.value = mutable.value.copy(confirmMaintenance = false) }
    internal fun confirmMaintenance() {
        assertMain()
        if (!enabled() || stopping || !mutable.value.confirmMaintenance || session.snapshot() != null) return
        stopping = true
        mutable.value = Ui()
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.clearRetainedAfterUserConfirmation() }
                mutable.value = Ui(message = "Retained update files cleared. You can check for updates again.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = Ui(message = "Could not clear all retained update files. No update was started.") }
            finally { stopping = false }
        }
    }
    internal fun launchFailed(id: String) = stop(id, "Android could not open the requested update screen. Check for updates again.")
    internal fun closeMessage() { assertMain(); mutable.value = mutable.value.copy(message = null) }
    internal fun dismiss(id: String) { assertMain(); stop(id, null) }

    /** Invalidate UI immediately; release claim only AFTER old work and its file IO stop. */
    private fun stop(id: String, message: String?) {
        if (stopping || mutable.value.id != id) return
        stopping = true
        val old = job
        mutable.value = Ui(message = message)
        job = viewModelScope.launch {
            try {
                old?.cancelAndJoin()
                withContext(Dispatchers.IO) { fileSession?.let { store.dismiss(it) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = Ui(message = "Could not clean the previous update. Close and reopen Seali before trying again.")
                return@launch // Keep stopping=true, fail closed rather than release uncertain ownership.
            }
            fileSession = null
            session.dismiss(id)
            stopping = false
        }
    }

    override fun onCleared() {
        val old = job
        old?.cancel()
        // No Activity reference. Finish file cleanup even though viewModelScope is now canceled.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { old?.join(); fileSession?.let { store.dismiss(it) } }
            finally { cancel() }
        }
        super.onCleared()
    }
}
