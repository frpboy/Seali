package com.frpboy.seali

import com.frpboy.seali.util.UpdateBlockingCall
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Loopback HTTP only. Not an Android/provider or real release download test. */
class UpdateSocketCancellationTest {
    private fun scenario(sendHeaders: Boolean) = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val requestRead = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            val readStarted = CountDownLatch(1)
            val observedCancellation = AtomicBoolean(false)
            val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val worker = thread(name = "updater-loopback-http") {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
                        if (sendHeaders) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        requestRead.countDown()
                        check(releaseServer.await(5, TimeUnit.SECONDS))
                    }
                } catch (t: Throwable) { workerFailure.set(t); requestRead.countDown() }
            }
            val client = OkHttpClient.Builder().readTimeout(30,TimeUnit.SECONDS).build()
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/fixture").build())
            try {
                val job = launch(Dispatchers.IO) {
                    try {
                        UpdateBlockingCall.own(cancel = { call.cancel() }) {
                            call.execute().use { response ->
                                readStarted.countDown()
                                response.body.byteStream().read()
                            }
                        }
                    } catch (_: CancellationException) { observedCancellation.set(true) }
                }
                assertTrue(requestRead.await(5, TimeUnit.SECONDS))
                workerFailure.get()?.let { throw AssertionError(it) }
                if (sendHeaders) assertTrue(readStarted.await(5, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue(observedCancellation.get())
            } finally {
                releaseServer.countDown()
                worker.join(6000)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
            assertFalse(worker.isAlive)
            workerFailure.get()?.let { throw AssertionError(it) }
        }
    }
    @Test fun cancellationUnblocksExecuteAwaitingHeaders() { scenario(false) }
    @Test fun cancellationUnblocksBodyRead() { scenario(true) }
}
