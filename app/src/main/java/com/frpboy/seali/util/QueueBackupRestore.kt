package com.frpboy.seali.util

import com.frpboy.seali.download.Task
import com.frpboy.seali.download.Task.DownloadState.Canceled
import com.frpboy.seali.download.Task.DownloadState.Error
import com.frpboy.seali.download.Task.DownloadState.Completed
import com.frpboy.seali.download.Task.DownloadState.FetchingInfo
import com.frpboy.seali.download.Task.DownloadState.Idle
import com.frpboy.seali.download.Task.DownloadState.ReadyWithInfo
import com.frpboy.seali.download.Task.DownloadState.Running
import com.frpboy.seali.download.Task.RestartableAction.Download
import com.frpboy.seali.download.Task.RestartableAction.FetchInfo
import java.time.Instant
import java.time.ZoneId

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

/**
 * Applies a download-state change and keeps the queue-owned run times honest.
 * startedAtMs is set when a task begins Running; endedAtMs is set only on the
 * transition into a terminal state made by this app, and cleared when the task runs
 * again. Nothing here derives a time from files, history rows or URLs.
 */
internal fun Task.State.withDownloadState(
    value: Task.DownloadState,
    nowMs: Long,
): Task.State {
    val terminal = value is Completed || value is Error || value is Canceled
    val wasTerminal =
        downloadState is Completed || downloadState is Error || downloadState is Canceled
    val started =
        if (value is Running && downloadState !is Running) nowMs else startedAtMs
    val ended =
        when {
            terminal && !wasTerminal -> nowMs
            terminal -> endedAtMs
            else -> null
        }
    return copy(downloadState = value, startedAtMs = started, endedAtMs = ended)
}

/**
 * Entries worth persisting in the queue backup: every live task, plus tasks that this
 * app itself completed (they carry endedAtMs). Synthetic history entries are never
 * persisted, and a Completed entry without its own end time is never invented.
 */
internal fun selectTaskBackupEntries(map: Map<Task, Task.State>): Map<Task, Task.State> =
    map.filter { (task, state) ->
        !task.isHistoryEntry() &&
            (state.downloadState !is Completed || state.endedAtMs != null)
    }

internal fun isSameLocalDay(aMs: Long, bMs: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()

internal fun planQueueBackupRestore(
    saved: Map<Task, Task.State>,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): QueueBackupRestorePlan {
    val stale = mutableSetOf<Int>()
    val restored =
        saved
            // Retention for the queue view only (backups, files and Room history are not
            // deleted by this filter):
            //  - a task with a recorded end time (Completed, Error or Canceled by this app)
            //    is a known outcome and stays for that local day only;
            //  - Completed with no end time has unknown age and is not shown here;
            //  - everything else (interrupted or in-progress work, or Error/Canceled with
            //    no recorded end time) is unresolved and stays until the user resolves it.
            .filter { (_, state) ->
                val ds = state.downloadState
                val ended = state.endedAtMs
                when {
                    ds is Completed -> ended != null && isSameLocalDay(ended, nowMs, zone)
                    (ds is Error || ds is Canceled) && ended != null ->
                        isSameLocalDay(ended, nowMs, zone)
                    else -> true
                }
            }
            .mapValues { (task, state) ->
                val pre = state.downloadState
                val next =
                    when (pre) {
                        is Completed -> pre
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
