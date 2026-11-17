package com.frpboy.seali.util

import com.frpboy.seali.database.objects.DownloadedVideoInfo
import com.frpboy.seali.download.Task

/**
 * Maps durable Room download history onto synthetic queue entries so the queue's
 * Finished lane and its counts survive process restarts. Synthetic entries are
 * in-memory only: they always use [Task.DownloadState.Completed], which the
 * existing backup writer excludes, so nothing new is persisted. They carry an id
 * prefix so the UI can withhold actions that require a live session task.
 */

internal const val HISTORY_TASK_ID_PREFIX = "history_"

internal fun historyTaskId(historyRowId: Int): String = "$HISTORY_TASK_ID_PREFIX$historyRowId"

fun Task.isHistoryEntry(): Boolean = id.startsWith(HISTORY_TASK_ID_PREFIX)

internal data class HistoryMergePlan(
    val toAdd: List<Pair<Task, Task.State>>,
    val toRemoveIds: Set<String>,
    val missingFileIds: Set<String>,
)

internal fun synthesizeHistoryEntry(info: DownloadedVideoInfo): Pair<Task, Task.State> {
    val task =
        Task(
            url = info.videoUrl,
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
            id = historyTaskId(info.id),
        )
    val state =
        Task.State(
            downloadState = Task.DownloadState.Completed(filePath = info.videoPath),
            videoInfo = null,
            viewState =
                Task.ViewState(
                    url = info.videoUrl,
                    title = info.videoTitle,
                    uploader = info.videoAuthor,
                    extractorKey = info.extractor,
                    thumbnailUrl = info.thumbnailUrl,
                ),
        )
    return task to state
}

/**
 * Computes the minimal mutation aligning synthetic history entries in [session]
 * with durable [history]. Idempotent: applying the plan and re-planning the
 * result yields an empty plan, which terminates the collector's apply loop.
 * Newest history rows come first in [HistoryMergePlan.toAdd]. Duplicate history
 * paths (possible after a backup restore, since the DAO dedupes only at insert
 * time) collapse to the newest row. [hiddenPaths] holds session-scoped queue
 * dismissals so a deleted entry does not reappear from its Room row.
 *
 * [fileExists] is invoked by the caller off the UI thread; it must never run
 * during composition or layout.
 */
internal fun planHistoryMerge(
    session: Map<Task, Task.State>,
    history: List<DownloadedVideoInfo>,
    hiddenPaths: Set<String> = emptySet(),
    includeHistory: Boolean = true,
    fileExists: (String) -> Boolean,
): HistoryMergePlan {
    val realCompletedPaths =
        session.entries
            .filter { !it.key.isHistoryEntry() }
            .mapNotNull {
                (it.value.downloadState as? Task.DownloadState.Completed)?.filePath
            }
            .toSet()
    val existingHistoryIds =
        session.keys.filter { it.isHistoryEntry() }.map { it.id }.toSet()

    // [hiddenPaths] are paths the user dismissed from the queue this session;
    // without the exclusion a deleted live Completed task would instantly
    // reappear as a synthesized entry for the same Room row.
    // The DAO dedupes by path only at insert time, so a restored database can
    // hold duplicate paths; keep the newest row per path.
    // includeHistory=false keeps the queue to session/restored tasks: existing synthetic
    // entries are removed from the in-memory view only; Room rows and files are untouched.
    val wanted =
        (if (includeHistory) history else emptyList())
            .filter {
                it.videoPath.isNotBlank() &&
                    it.videoPath !in realCompletedPaths &&
                    it.videoPath !in hiddenPaths
            }
            .groupBy { it.videoPath }
            .map { (_, rows) -> rows.maxBy { it.id } }
    val wantedIds = wanted.map { historyTaskId(it.id) }.toSet()

    val toRemoveIds = existingHistoryIds - wantedIds
    val toAdd =
        wanted
            .filter { historyTaskId(it.id) !in existingHistoryIds }
            .sortedByDescending { it.id }
            .map(::synthesizeHistoryEntry)
    val missingFileIds =
        wanted.filter { !fileExists(it.videoPath) }.map { historyTaskId(it.id) }.toSet()

    return HistoryMergePlan(
        toAdd = toAdd,
        toRemoveIds = toRemoveIds,
        missingFileIds = missingFileIds,
    )
}
