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
import java.time.ZoneId
import org.junit.Assert.assertNull
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

    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-11-17 10:00 IST and 2026-11-16 23:30 IST / 2026-11-17 00:10 IST
    private val now = 1_794_889_800_000L
    private val yesterdayLate = now - 10L * 3_600_000L - 30L * 60_000L
    private val todayEarly = now - 9L * 3_600_000L - 50L * 60_000L

    private fun withEnd(s: Task.DownloadState, end: Long?) =
        Task.State(s, null, Task.ViewState(url = "u", title = "t"), endedAtMs = end)

    @Test
    fun stateChangeRecordsStartAndEndOnlyFromTheTasksOwnTransitions() {
        val t = task(7)
        val idle = state(Idle)
        val running = idle.withDownloadState(Running(taskId = t.id), 100L)
        assertEquals(100L, running.startedAtMs)
        assertNull(running.endedAtMs)
        val progressed = running.withDownloadState(Running(taskId = t.id, progress = 0.5f), 200L)
        assertEquals(100L, progressed.startedAtMs)
        val done = progressed.withDownloadState(Completed("/x/a.mp4"), 300L)
        assertEquals(300L, done.endedAtMs)
        val again = done.withDownloadState(Completed("/x/a.mp4"), 400L)
        assertEquals(300L, again.endedAtMs)
        val rerun = done.withDownloadState(Running(taskId = t.id), 500L)
        assertNull(rerun.endedAtMs)
        assertEquals(500L, rerun.startedAtMs)
    }

    @Test
    fun backupKeepsOwnCompletedTasksButNotSyntheticOrUndatedOnes() {
        val own = task(8)
        val undated = task(9)
        val synthetic = Task(url = "u", preferences = DownloadUtil.DownloadPreferences.EMPTY, id = historyTaskId(1))
        val live = task(10)
        val out =
            selectTaskBackupEntries(
                mapOf(
                    own to withEnd(Completed("/a"), 5L),
                    undated to withEnd(Completed("/b"), null),
                    synthetic to withEnd(Completed("/c"), 5L),
                    live to state(Idle),
                )
            )
        assertEquals(setOf(own, live), out.keys)
    }

    @Test
    fun restoreKeepsTodaysCompletedAndDropsOtherDaysAndUndatedFromTheQueueOnly() {
        val today = task(11)
        val yesterday = task(12)
        val undated = task(13)
        val plan =
            planQueueBackupRestore(
                mapOf(
                    today to withEnd(Completed("/t"), todayEarly),
                    yesterday to withEnd(Completed("/y"), yesterdayLate),
                    undated to withEnd(Completed("/u"), null),
                ),
                nowMs = now,
                zone = zone,
            )
        assertEquals(setOf(today), plan.restored.keys)
        assertEquals(Completed("/t"), plan.restored[today]?.downloadState)
        assertTrue(plan.staleNotificationIds.isEmpty())
    }

    @Test
    fun knownEndedOutcomesFromEarlierDaysLeaveTheQueueViewButUndatedUnresolvedWorkStays() {
        val oldCanceled = task(14)
        val oldError = task(17)
        val todayCanceled = task(18)
        val undatedCanceled = task(19)
        val idle = task(20)
        val plan =
            planQueueBackupRestore(
                mapOf(
                    oldCanceled to withEnd(Canceled(action = Download), yesterdayLate),
                    oldError to withEnd(Task.DownloadState.Error(action = Download), yesterdayLate),
                    todayCanceled to withEnd(Canceled(action = Download), todayEarly),
                    undatedCanceled to withEnd(Canceled(action = Download), null),
                    idle to state(Idle),
                ),
                nowMs = now,
                zone = zone,
            )
        assertEquals(setOf(todayCanceled, undatedCanceled, idle), plan.restored.keys)
        assertEquals(todayEarly, plan.restored[todayCanceled]?.endedAtMs)
        assertNull(plan.restored[undatedCanceled]?.endedAtMs)
        assertNull(plan.restored[idle]?.endedAtMs)
    }

    @Test
    fun interruptedTaskGetsNoInventedEndTime() {
        val t = task(15)
        val running = state(Running(taskId = t.id)).copy(startedAtMs = 42L)
        val plan = planQueueBackupRestore(mapOf(t to running), nowMs = now, zone = zone)
        assertNull(plan.restored[t]?.endedAtMs)
        assertEquals(42L, plan.restored[t]?.startedAtMs)
    }

    @Test
    fun backupFromBeforeTheTimestampFieldsStillDecodesWithNullTimes() {
        val t = task(16)
        val legacy = TaskBackupCodec.encode(mapOf(t to state(Idle)))
        val back = TaskBackupCodec.decode(legacy).values.single()
        assertNull(back.startedAtMs)
        assertNull(back.endedAtMs)
        val dated = TaskBackupCodec.decode(TaskBackupCodec.encode(mapOf(t to withEnd(Completed("/z"), 7L))))
        assertEquals(7L, dated.values.single().endedAtMs)
    }

    @Test
    fun interruptedTaskWithItsOwnCompletionRecordAndOutputBecomesCompleted() {
        val t = task(21)
        val running = state(Running(taskId = t.id))
        val record = TaskCompletionJournal.Entry("/out/v.mp4", todayEarly)
        val plan =
            planQueueBackupRestore(
                mapOf(t to running),
                nowMs = now,
                zone = zone,
                completionRecord = { if (it == t) record else null },
                outputIsPresent = { it == "/out/v.mp4" },
            )
        assertEquals(Completed("/out/v.mp4"), plan.restored[t]?.downloadState)
        assertEquals(todayEarly, plan.restored[t]?.endedAtMs)
        assertEquals(mapOf(t to "/out/v.mp4"), plan.recoveredCompletions)
        assertTrue(t.progressNotificationId() in plan.staleNotificationIds)
    }

    @Test
    fun completionRecordWithoutThePresentOutputStaysInterruptedCanceled() {
        val t = task(22)
        val plan =
            planQueueBackupRestore(
                mapOf(t to state(Running(taskId = t.id))),
                nowMs = now,
                zone = zone,
                completionRecord = { TaskCompletionJournal.Entry("/gone.mp4", todayEarly) },
                outputIsPresent = { false },
            )
        assertEquals(Canceled(action = Download, progress = Running(taskId = t.id).progress), plan.restored[t]?.downloadState)
        assertNull(plan.restored[t]?.endedAtMs)
        assertTrue(plan.recoveredCompletions.isEmpty())
    }

    @Test
    fun onlyRunningTasksAreRecoveredAndAFileAloneNeverCompletesATask() {
        val running = task(23)
        val idle = task(24)
        val canceled = task(25)
        val plan =
            planQueueBackupRestore(
                mapOf(
                    running to state(Running(taskId = running.id)),
                    idle to state(Idle),
                    canceled to state(Canceled(action = Download)),
                ),
                nowMs = now,
                zone = zone,
                completionRecord = { null },
                outputIsPresent = { true },
            )
        assertTrue(plan.recoveredCompletions.isEmpty())
        assertTrue(plan.restored.values.none { it.downloadState is Completed })
    }

    @Test
    fun recoveredCompletionFromAnEarlierDayLeavesTheQueueViewButIsStillReportedForHistory() {
        val t = task(26)
        val plan =
            planQueueBackupRestore(
                mapOf(t to state(Running(taskId = t.id))),
                nowMs = now,
                zone = zone,
                completionRecord = { TaskCompletionJournal.Entry("/o.mp4", yesterdayLate) },
                outputIsPresent = { true },
            )
        assertTrue(t !in plan.restored)
        assertEquals(mapOf(t to "/o.mp4"), plan.recoveredCompletions)
    }

    @Test
    fun rolloverExpiresOnlyKnownEndedOutcomesFromEarlierDays() {
        val doneYesterday = task(27)
        val failedYesterday = task(28)
        val doneToday = task(29)
        val undatedCanceled = task(30)
        val runningNow = task(31)
        val map =
            mapOf(
                doneYesterday to withEnd(Completed("/a"), yesterdayLate),
                failedYesterday to withEnd(Task.DownloadState.Error(action = Download), yesterdayLate),
                doneToday to withEnd(Completed("/b"), todayEarly),
                undatedCanceled to withEnd(Canceled(action = Download), null),
                runningNow to state(Running(taskId = runningNow.id)).copy(startedAtMs = yesterdayLate),
            )
        val expired = expiredOutcomeTasks(map, now, zone).toSet()
        assertEquals(setOf(doneYesterday, failedYesterday), expired)
    }

    @Test
    fun appOpenAcrossMidnightExpiresATodayOutcomeOnceTheDayChanges() {
        val t = task(32)
        val s = withEnd(Completed("/c"), now)
        assertTrue(expiredOutcomeTasks(mapOf(t to s), now + 3_600_000L, zone).isEmpty())
        val nextDay = now + 24L * 3_600_000L
        assertEquals(listOf(t), expiredOutcomeTasks(mapOf(t to s), nextDay, zone))
    }

    @Test
    fun privateModeCompletedResultsAreNeverPersistedInTheBackup() {
        val priv =
            Task(
                url = "https://example.com/p",
                preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(privateMode = true),
                id = "private1",
            )
        val normal = task(33)
        val out =
            selectTaskBackupEntries(
                mapOf(
                    priv to withEnd(Completed("/p"), 5L),
                    normal to withEnd(Completed("/n"), 5L),
                )
            )
        assertEquals(setOf(normal), out.keys)
    }
}
