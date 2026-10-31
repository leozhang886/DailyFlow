package com.dailyflow.app.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    /** Swap two global task positions only when both persisted snapshot positions still match. */
    suspend fun moveTask(taskId: String, neighbourId: String, expectedOrder: Long, expectedNeighbourOrder: Long): Boolean =
        throw UnsupportedOperationException("Task ordering unavailable")
    fun observeSeries(): Flow<List<SeriesSummary>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun observeExceptions(taskId: String): Flow<List<TaskException>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun observeHistory(from: LocalDate, to: LocalDate): Flow<List<TaskOccurrence>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun changeRecurrence(taskId: String, effectiveFrom: LocalDate, rule: RecurrenceRule, expectedRevision: Long,
        today: () -> LocalDate = { LocalDate.now() }): Unit = throw UnsupportedOperationException()
    suspend fun recoverException(taskId: String, date: LocalDate, restoreDeleted: Boolean): Unit = throw UnsupportedOperationException()
    fun observeDay(date: LocalDate): Flow<List<TaskOccurrence>>
    suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule? = null): String
    suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope)
    suspend fun delete(taskId: String, date: LocalDate, scope: EditScope)
    suspend fun loadDraft(taskId: String, date: LocalDate, scope: EditScope): TaskDraft? = null
    suspend fun createTask(draft: TaskDraft): String = throw UnsupportedOperationException("Draft creation unavailable")
    suspend fun updateTask(taskId: String, occurrenceDate: LocalDate, draft: TaskDraft, scope: EditScope): Unit = throw UnsupportedOperationException("Draft editing unavailable")
    suspend fun completeFromSnapshot(taskId: String, date: LocalDate, expectedCompleted: Boolean, expectedRevision: Long, target: Boolean, today: () -> LocalDate = { LocalDate.now() }): CompletionResult = throw UnsupportedOperationException("Snapshot completion unavailable")
    suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean)
}
