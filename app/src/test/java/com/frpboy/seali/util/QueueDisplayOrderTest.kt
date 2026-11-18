package com.frpboy.seali.util

import com.frpboy.seali.download.Task
import com.frpboy.seali.download.Task.DownloadState.Canceled
import com.frpboy.seali.download.Task.DownloadState.Completed
import com.frpboy.seali.download.Task.DownloadState.Idle
import com.frpboy.seali.download.Task.DownloadState.Running
import com.frpboy.seali.download.Task.RestartableAction.Download
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDisplayOrderTest {
    private fun task(n: Int) =
        Task(
            url = "https://example.com/v$n",
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
            id = "t$n",
        )

    private fun st(s: Task.DownloadState, end: Long?) =
        Task.State(s, null, Task.ViewState(url = "u", title = "t"), endedAtMs = end)

    @Test
    fun aFreshCompletedDownloadIsListedAboveAnOlderCanceledOne() {
        val oldCanceled = task(1) to st(Canceled(action = Download, progress = 0.2f), 1_000L)
        val fresh = task(2) to st(Completed("/f"), 9_000L)
        assertEquals(listOf(fresh, oldCanceled), queueDisplayOrder(listOf(oldCanceled, fresh)))
    }

    @Test
    fun undatedEndedTasksComeAfterDatedOnesWithoutReordering() {
        val u1 = task(3) to st(Canceled(action = Download, progress = null), null)
        val u2 = task(4) to st(Canceled(action = Download, progress = null), null)
        val dated = task(5) to st(Completed("/d"), 5L)
        assertEquals(listOf(dated, u1, u2), queueDisplayOrder(listOf(u1, u2, dated)))
    }

    @Test
    fun activeWorkStaysOnTopInTheExistingStateOrder() {
        val running = task(6) to st(Running(taskId = "t6", progress = 0.5f), null)
        val idle = task(7) to st(Idle, null)
        val done = task(8) to st(Completed("/x"), 7L)
        assertEquals(listOf(running, idle, done), queueDisplayOrder(listOf(done, idle, running)))
    }
}
