package com.dailyflow.app.data

import com.dailyflow.app.domain.*
import java.time.LocalDate

/** Notify after commit, never turn a committed task write into a reported save failure. */
class UpdatingTaskRepository(private val delegate: TaskRepository, private val changed: () -> Unit) : TaskRepository by delegate {
    override suspend fun moveTask(taskId: String, neighbourId: String, expectedOrder: Long, expectedNeighbourOrder: Long): Boolean =
        delegate.moveTask(taskId, neighbourId, expectedOrder, expectedNeighbourOrder).also { if (it) notifyChanged() }
    override suspend fun changeRecurrence(taskId: String,effectiveFrom: LocalDate,rule: RecurrenceRule,expectedRevision: Long,today: () -> LocalDate) {
        delegate.changeRecurrence(taskId,effectiveFrom,rule,expectedRevision,today);notifyChanged()
    }
    override suspend fun recoverException(taskId: String,date: LocalDate,restoreDeleted: Boolean) {
        delegate.recoverException(taskId,date,restoreDeleted);notifyChanged()
    }
    private fun notifyChanged() { try { changed() } catch (_: Exception) { /* periodic refresh remains a fallback */ } }
    override suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule?): String =
        delegate.create(title, note, date, rule).also { notifyChanged() }
    override suspend fun createTask(draft: TaskDraft): String = delegate.createTask(draft).also { notifyChanged() }
    override suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope) {
        delegate.updateContent(taskId, date, title, note, scope); notifyChanged()
    }
    override suspend fun updateTask(taskId: String, occurrenceDate: LocalDate, draft: TaskDraft, scope: EditScope) {
        delegate.updateTask(taskId, occurrenceDate, draft, scope); notifyChanged()
    }
    override suspend fun delete(taskId: String, date: LocalDate, scope: EditScope) {
        delegate.delete(taskId, date, scope); notifyChanged()
    }
    override suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean) {
        delegate.setCompleted(taskId, date, completed); notifyChanged()
    }
    override suspend fun completeFromSnapshot(taskId: String, date: LocalDate, expectedCompleted: Boolean, expectedRevision: Long, target: Boolean, today: () -> LocalDate): CompletionResult =
        delegate.completeFromSnapshot(taskId, date, expectedCompleted, expectedRevision, target, today).also { notifyChanged() }
}
