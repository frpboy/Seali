package com.frpboy.seali

import com.frpboy.seali.util.UpdateBlockingCall
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UpdateBlockingCallTest {
    @Test fun cancellationInterruptsBlockedOperationAndRemainsCancellation() = runBlocking {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val cancelled = AtomicBoolean(false); val observedCancellation = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            try {
                UpdateBlockingCall.own(cancel = { cancelled.set(true); released.countDown() }) {
                    entered.countDown()
                    check(released.await(5, TimeUnit.SECONDS))
                    throw IOException("fake cancelled HTTP read")
                }
            } catch (_: CancellationException) { observedCancellation.set(true) }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(5000) { job.cancelAndJoin() }
        assertTrue(cancelled.get()); assertTrue(observedCancellation.get())
    }
    @Test fun successClosesGuardAndDoesNotChangeResult() = runBlocking {
        var cancelled = false
        assertEquals(42, UpdateBlockingCall.own(cancel = { cancelled = true }) { 42 })
        assertTrue(cancelled)
    }
    @Test fun downstreamFailureStillClosesCall() = runBlocking {
        var cancelled = false
        try {
            UpdateBlockingCall.own(cancel = { cancelled = true }) { error("verification refused") }
            fail()
        } catch (_: IllegalStateException) { }
        assertTrue(cancelled)
    }
}
