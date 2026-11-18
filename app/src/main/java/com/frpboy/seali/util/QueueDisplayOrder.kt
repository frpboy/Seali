package com.frpboy.seali.util

import com.frpboy.seali.download.Task
import com.frpboy.seali.download.Task.DownloadState.Canceled
import com.frpboy.seali.download.Task.DownloadState.Completed
import com.frpboy.seali.download.Task.DownloadState.Error

/**
 * Display order of the download queue (display only; the scheduler keeps its own order).
 *
 * Work that is still moving or waiting comes first, in the existing state order. Ended
 * tasks (Completed, Error, Canceled) follow, newest end time first, so a fresh download
 * is never listed below an older canceled one. Ended tasks without a recorded end time
 * are listed last and keep their relative order (no time is invented for them).
 */
internal fun queueDisplayOrder(
    entries: List<Pair<Task, Task.State>>
): List<Pair<Task, Task.State>> {
    fun ended(s: Task.State): Boolean =
        s.downloadState.let { it is Completed || it is Error || it is Canceled }
    val (ended, active) = entries.partition { ended(it.second) }
    val orderedActive = active.sortedBy { it.second.downloadState }
    val dated = ended.filter { it.second.endedAtMs != null }.sortedByDescending { it.second.endedAtMs }
    val undated = ended.filter { it.second.endedAtMs == null }
    return orderedActive + dated + undated
}
