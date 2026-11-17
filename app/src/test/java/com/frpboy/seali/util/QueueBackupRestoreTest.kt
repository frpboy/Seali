package com.frpboy.seali.util

import com.frpboy.seali.download.Task
import com.frpboy.seali.download.Task.DownloadState.Canceled
import com.frpboy.seali.download.Task.DownloadState.Completed
import com.frpboy.seali.download.Task.DownloadState.Idle
import com.frpboy.seali.download.Task.DownloadState.Running
import com.frpboy.seali.download.Task.RestartableAction.Download
import com.frpboy.seali.download.Task.RestartableAction.FetchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBackupRestoreTest {
    private fun task(n: Int) =
        Task(
            url = "https://example.com/v$n",
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
            id = "task$n",
        )

    private fun state(s: Task.DownloadState) =
        Task.State(s, null, Task.ViewState(url = "u", title = "t"))

    @Test
    fun runningTaskRestoresAsCanceledAndItsNotificationIsCleared() {
        val t = task(1)
        val plan = planQueueBackupRestore(mapOf(t to state(Running(taskId = t.id, progress = 0.1f))))
        assertEquals(Canceled(action = Download, progress = 0.1f), plan.restored[t]?.downloadState)
        assertEquals(setOf(t.progressNotificationId()), plan.staleNotificationIds)
    }

    @Test
    fun idleRestoresAsFetchInfoCancel() {
        val t = task(2)
        val plan = planQueueBackupRestore(mapOf(t to state(Idle)))
        assertEquals(Canceled(action = FetchInfo), plan.restored[t]?.downloadState)
        assertTrue(t.progressNotificationId() in plan.staleNotificationIds)
    }

    @Test
    fun alreadyCanceledOrCompletedTasksTouchNoNotification() {
        val c = task(3)
        val d = task(4)
        val plan =
            planQueueBackupRestore(
                mapOf(
                    c to state(Canceled(action = Download)),
                    d to state(Completed("/x/y.mp4")),
                )
            )
        assertTrue(plan.staleNotificationIds.isEmpty())
        assertTrue(d !in plan.restored)
        assertEquals(Canceled(action = Download), plan.restored[c]?.downloadState)
    }

    @Test
    fun restoreNeverPromotesATaskToCompleted() {
        val t = task(5)
        val plan = planQueueBackupRestore(mapOf(t to state(Running(taskId = t.id))))
        assertFalse(plan.restored.values.any { it.downloadState is Completed })
    }

    @Test
    fun notificationIdMatchesDownloaderV2Scheme() {
        val t = task(6)
        assertEquals(t.id.hashCode(), t.progressNotificationId())
    }
}
