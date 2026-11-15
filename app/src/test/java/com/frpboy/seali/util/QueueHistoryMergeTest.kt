package com.frpboy.seali.util

import com.frpboy.seali.database.objects.DownloadedVideoInfo
import com.frpboy.seali.download.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueHistoryMergeTest {

    private fun row(
        id: Int,
        path: String = "/downloads/v$id.mp4",
        url: String = "https://example.com/v$id",
    ) =
        DownloadedVideoInfo(
            id = id,
            videoTitle = "title$id",
            videoAuthor = "author$id",
            videoUrl = url,
            thumbnailUrl = "",
            videoPath = path,
        )

    private val allExist = { _: String -> true }

    @Test
    fun addsHistoryRowsAsCompletedSyntheticEntriesNewestFirst() {
        val plan = planHistoryMerge(emptyMap(), listOf(row(1), row(2)), allExist)
        assertEquals(listOf(historyTaskId(2), historyTaskId(1)), plan.toAdd.map { it.first.id })
        assertTrue(
            plan.toAdd.all {
                (it.second.downloadState as? Task.DownloadState.Completed)?.filePath != null
            }
        )
        assertTrue(plan.toRemoveIds.isEmpty())
    }

    @Test
    fun skipsRowsWhosePathIsAlreadyALiveCompletedTask() {
        val live =
            Task(
                url = "https://example.com/live",
                preferences = DownloadUtil.DownloadPreferences.EMPTY,
                id = "real-task-1",
            ) to
                Task.State(
                    downloadState = Task.DownloadState.Completed("/downloads/v1.mp4"),
                    videoInfo = null,
                    viewState = Task.ViewState(),
                )
        val plan = planHistoryMerge(mapOf(live), listOf(row(1)), allExist)
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.toRemoveIds.isEmpty())
    }

    @Test
    fun removesSyntheticEntriesWhoseHistoryRowDisappeared() {
        val (task, state) = synthesizeHistoryEntry(row(3))
        val plan = planHistoryMerge(mapOf(task to state), emptyList(), allExist)
        assertEquals(setOf(historyTaskId(3)), plan.toRemoveIds)
        assertTrue(plan.toAdd.isEmpty())
    }

    @Test
    fun flagsMissingFilesWithoutRemovingTheEntry() {
        val plan = planHistoryMerge(emptyMap(), listOf(row(4), row(5))) { it.contains("v5") }
        assertEquals(setOf(historyTaskId(4)), plan.missingFileIds)
        assertEquals(2, plan.toAdd.size)
    }

    @Test
    fun planningIsIdempotentAfterApplyingThePlan() {
        val history = listOf(row(1), row(2))
        val first = planHistoryMerge(emptyMap(), history, allExist)
        val second = planHistoryMerge(first.toAdd.toMap(), history, allExist)
        assertTrue(second.toAdd.isEmpty())
        assertTrue(second.toRemoveIds.isEmpty())
    }

    @Test
    fun blankPathsAreNotSynthesized() {
        val plan = planHistoryMerge(emptyMap(), listOf(row(6, path = " ")), allExist)
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.missingFileIds.isEmpty())
    }

    @Test
    fun syntheticEntriesAreCompletedForFinishedFilterSemantics() {
        val (task, state) = synthesizeHistoryEntry(row(7))
        assertTrue(task.isHistoryEntry())
        assertTrue(state.downloadState is Task.DownloadState.Completed)
    }

    @Test
    fun duplicateHistoryPathsCollapseToTheNewestRow() {
        val older = row(8, path = "/downloads/dup.mp4", url = "https://example.com/old")
        val newer = row(9, path = "/downloads/dup.mp4", url = "https://example.com/new")
        val plan = planHistoryMerge(emptyMap(), listOf(older, newer), allExist)
        assertEquals(listOf(historyTaskId(9)), plan.toAdd.map { it.first.id })
    }

    @Test
    fun hiddenPathsAreNotSynthesizedSoDeletedEntriesStayGone() {
        val plan =
            planHistoryMerge(
                emptyMap(),
                listOf(row(10)),
                hiddenPaths = setOf("/downloads/v10.mp4"),
                fileExists = allExist,
            )
        assertTrue(plan.toAdd.isEmpty())
        assertTrue(plan.missingFileIds.isEmpty())
    }

    @Test
    fun hiddenPathsAlsoRemoveAnAlreadySynthesizedEntry() {
        val (task, state) = synthesizeHistoryEntry(row(11))
        val plan =
            planHistoryMerge(
                mapOf(task to state),
                listOf(row(11)),
                hiddenPaths = setOf("/downloads/v11.mp4"),
                fileExists = allExist,
            )
        assertEquals(setOf(historyTaskId(11)), plan.toRemoveIds)
    }
}
