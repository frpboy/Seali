package com.frpboy.seali.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Scope MUST enclose execute AND response reading. Cancel targets this exact call, not another session. */
internal object UpdateBlockingCall {
    suspend fun <T> own(cancel: () -> Unit, operation: suspend () -> T): T = coroutineScope {
        // UNDISPATCHED installs the guard before operation can enter a blocking execute/read.
        // IO prevents the guard's cancellation finally from needing the blocked caller thread.
        val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { cancel() }
        }
        try {
            ensureActive()
            operation().also { ensureActive() }
        } catch (failure: Exception) {
            // Call.cancel() often unblocks IO by throwing IOException. Preserve cancellation semantics.
            ensureActive()
            throw failure
        } finally {
            guard.cancelAndJoin()
        }
    }
}
