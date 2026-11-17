package com.frpboy.seali.util

import com.frpboy.seali.download.Task
import com.frpboy.seali.download.Task.DownloadState.Canceled
import com.frpboy.seali.download.Task.DownloadState.Completed
import com.frpboy.seali.download.Task.DownloadState.FetchingInfo
import com.frpboy.seali.download.Task.DownloadState.Idle
import com.frpboy.seali.download.Task.DownloadState.ReadyWithInfo
import com.frpboy.seali.download.Task.DownloadState.Running
import com.frpboy.seali.download.Task.RestartableAction.Download
import com.frpboy.seali.download.Task.RestartableAction.FetchInfo

/**
 * Result of restoring the saved queue after a process start.
 *
 * [restored] holds each non-completed saved task with its live state turned into a
 * restartable Canceled state (no job survives a process restart).
 * [staleNotificationIds] holds the notification ids of every task whose saved state
 * was in progress. The previous process may have died without clearing the ongoing
 * progress notification it posted, and that notification's Cancel action targets a
 * task that no longer exists, so the restore must clear it. The ids are only those of
 * tasks the restore itself just made inactive.
 *
 * This deliberately does not decide that a task finished: whether an output is
 * complete is not knowable from the saved state, so such a task stays restartable and
 * is never promoted to Completed here.
 */
internal data class QueueBackupRestorePlan(
    val restored: Map<Task, Task.State>,
    val staleNotificationIds: Set<Int>,
)

internal fun Task.progressNotificationId(): Int = id.hashCode()

internal fun planQueueBackupRestore(saved: Map<Task, Task.State>): QueueBackupRestorePlan {
    val stale = mutableSetOf<Int>()
    val restored =
        saved
            .filter { it.value.downloadState !is Completed }
            .mapValues { (task, state) ->
                val pre = state.downloadState
                val next =
                    when (pre) {
                        is FetchingInfo,
                        Idle -> Canceled(action = FetchInfo)
                        is Running -> Canceled(action = Download, progress = pre.progress)
                        ReadyWithInfo -> Canceled(action = Download, progress = null)
                        else -> pre
                    }
                if (pre is FetchingInfo || pre is Running || pre === Idle || pre === ReadyWithInfo) {
                    stale += task.progressNotificationId()
                }
                state.copy(downloadState = next)
            }
    return QueueBackupRestorePlan(restored, stale)
}
