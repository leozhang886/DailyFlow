package com.dailyflow.app.reminders

import com.dailyflow.app.domain.*
import java.time.LocalDate

/** DB write before system synchronization; failures in the latter do not roll back a committed edit. */
class ReminderAwareRepository(private val delegate: TaskRepository, private val engine: ReminderEngine) : TaskRepository by delegate {
    override suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule?) = engine.mutate { delegate.create(title,note,date,rule) }
    override suspend fun createTask(draft: TaskDraft) = engine.mutate { delegate.createTask(draft) }
    override suspend fun updateTask(taskId: String, occurrenceDate: LocalDate, draft: TaskDraft, scope: EditScope) = engine.mutate { delegate.updateTask(taskId,occurrenceDate,draft,scope) }
    override suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope) = engine.mutate { delegate.updateContent(taskId,date,title,note,scope) }
    override suspend fun delete(taskId: String, date: LocalDate, scope: EditScope) = engine.mutate { delegate.delete(taskId,date,scope) }
    override suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean) = engine.mutate { delegate.setCompleted(taskId,date,completed) }
    override suspend fun completeFromSnapshot(taskId: String,date: LocalDate,expectedCompleted: Boolean,expectedRevision: Long,target: Boolean,today: () -> LocalDate) = engine.mutate { delegate.completeFromSnapshot(taskId,date,expectedCompleted,expectedRevision,target,today) }
    override suspend fun changeRecurrence(taskId: String,effectiveFrom: LocalDate,rule: RecurrenceRule,expectedRevision: Long,today: () -> LocalDate) = engine.mutate { delegate.changeRecurrence(taskId,effectiveFrom,rule,expectedRevision,today) }
    override suspend fun recoverException(taskId: String,date: LocalDate,restoreDeleted: Boolean) = engine.mutate { delegate.recoverException(taskId,date,restoreDeleted) }
    override suspend fun moveTask(taskId: String,neighbourId: String,expectedOrder: Long,expectedNeighbourOrder: Long) = engine.mutate { delegate.moveTask(taskId,neighbourId,expectedOrder,expectedNeighbourOrder) }
}
