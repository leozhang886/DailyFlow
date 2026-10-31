package com.dailyflow.app.data

import androidx.room.withTransaction
import com.dailyflow.app.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomTaskRepository(private val database: DailyFlowDatabase, private val clock: Clock = Clock.systemUTC()) : TaskRepository {
    private val dao = database.taskDao()

    private fun resolveDay(rows: List<DailyTaskRow>, date: LocalDate): List<TaskOccurrence> = rows
        .filter { RecurrenceEngine.occursOn(it.ruleAnchor,it.rule?.toDomain(),date) || it.dayCompleted==true || it.exceptionId!=null }
        .map { row -> TaskOccurrence(row.task.id,date,row.titleOverride?:row.task.title,row.noteOverride?:row.task.note,
            row.dayCompleted?:false,row.rule!=null,row.exceptionId!=null,
            if(row.timeOverridden==true)row.scheduledMinuteOverride else row.task.scheduledMinute,
            row.priorityOverride?:row.task.priority,row.rule?.toDomain(),row.task.stateRevision,
            row.task.sortOrder,row.task.createdAt) }
    override fun observeDay(date: LocalDate): Flow<List<TaskOccurrence>> = dao.observeDayCandidates(date).map { resolveDay(it,date) }.distinctUntilChanged()
    override fun observeSeries(): Flow<List<SeriesSummary>> = dao.observePlanningSignal().map {
        database.withTransaction { dao.allSeries().map { task -> SeriesSummary(task.id,task.title,task.startDate,
            requireNotNull(dao.rule(task.id)).toDomain(),task.stateRevision,dao.versions(task.id).map { RuleChange(it.effectiveFrom,it.toDomain()) }) } }
    }.distinctUntilChanged()
    override fun observeExceptions(taskId: String): Flow<List<TaskException>> = dao.observeExceptions(taskId)
        .map { rows -> rows.map { TaskException(it.taskId,it.date,it.deleted,it.titleOverride) } }.distinctUntilChanged()
    override fun observeHistory(from: LocalDate,to: LocalDate): Flow<List<TaskOccurrence>> {
        require(to>=from && java.time.temporal.ChronoUnit.DAYS.between(from,to)<31) { "历史查询范围为 1—31 天" }
        return dao.observePlanningSignal().map { database.withTransaction {
            val result=mutableListOf<TaskOccurrence>();var day=from
            while(day<=to) { result.addAll(resolveDay(dao.getDayCandidates(day),day));if(day==to)break;day=day.plusDays(1) }
            result
        } }.distinctUntilChanged()
    }
    override suspend fun changeRecurrence(taskId: String,effectiveFrom: LocalDate,rule: RecurrenceRule,expectedRevision: Long,today: () -> LocalDate) = database.withTransaction {
        val task=requireNotNull(dao.task(taskId)) { "任务不存在" }
        requireNotNull(dao.rule(taskId)) { "仅重复任务可修改规则" }
        require(effectiveFrom>=today() && effectiveFrom>=task.startDate) { "生效日不得早于今天或系列开始日" }
        require(rule.untilDate==null || rule.untilDate>=effectiveFrom) { "结束日不得早于生效日" }
        require(task.stateRevision==expectedRevision) { "数据已变化，请重新打开规则页面" }
        val entity=RecurrenceRuleEntity.from(taskId,rule)
        dao.deleteFutureVersions(taskId,effectiveFrom)
        dao.insertVersion(RecurrenceVersionEntity(taskId,effectiveFrom,entity.frequency,entity.interval,entity.weekdayMask,entity.untilDate))
        bumpRevision(task)
    }
    override suspend fun recoverException(taskId: String,date: LocalDate,restoreDeleted: Boolean) = database.withTransaction {
        val task=requireNotNull(dao.task(taskId)) { "任务不存在" }
        val exception=requireNotNull(dao.exception(taskId,date)) { "该日例外已变化，请刷新" }
        require(exception.deleted==restoreDeleted) { "该日例外已变化，请刷新" }
        if(restoreDeleted)dao.saveException(exception.copy(deleted=false,updatedAt=clock.millis()))
        else dao.deleteException(taskId,date)
        bumpRevision(task)
    }
    private suspend fun bumpRevision(task: TaskEntity) {
        check(task.stateRevision<Long.MAX_VALUE)
        dao.updateTask(task.copy(stateRevision=task.stateRevision+1,updatedAt=clock.millis()))
    }
    private suspend fun occurrenceValid(id: String,date: LocalDate): Boolean {
        val task=dao.task(id)?:return false
        val ex=dao.exception(id,date)
        if(ex?.deleted==true)return false
        val version=dao.version(id,date)
        val rule=version?.toDomain()?:dao.rule(id)?.toDomain()
        return RecurrenceEngine.occursOn(version?.effectiveFrom?:task.startDate,rule,date) || ex!=null || dao.dayState(id,date)?.completed==true
    }

    override suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule?): String =
        createTask(TaskDraft(title, note, date, rule = rule))

    override suspend fun createTask(draft: TaskDraft): String {
        draft.validate()
        val id = UUID.randomUUID().toString()
        database.withTransaction {
            val now = clock.millis()
            val highest = dao.maximumSortOrder()
            val position = if (highest == Long.MAX_VALUE) {
                val existing = dao.tasksForOrdering()
                existing.forEachIndexed { index, row -> if (row.sortOrder != index.toLong()) dao.updateSortOrder(row.id, index.toLong()) }
                existing.size.toLong()
            } else highest?.plus(1) ?: 0L
            dao.insertTask(TaskEntity(id, draft.title.trim(), draft.note, draft.date, createdAt = now, updatedAt = now,
                scheduledMinute = draft.scheduledMinute, priority = draft.priority, sortOrder = position))
            draft.rule?.let { dao.insertRule(RecurrenceRuleEntity.from(id, it)) }
        }
        return id
    }

    override suspend fun moveTask(taskId: String, neighbourId: String, expectedOrder: Long, expectedNeighbourOrder: Long): Boolean =
        database.withTransaction {
            if (taskId == neighbourId) return@withTransaction false
            val task = dao.task(taskId) ?: return@withTransaction false
            val neighbour = dao.task(neighbourId) ?: return@withTransaction false
            require(task.sortOrder == expectedOrder && neighbour.sortOrder == expectedNeighbourOrder) { "顺序已变化，请刷新后重试" }
            if (task.sortOrder != neighbour.sortOrder) {
                dao.updateSortOrder(task.id, neighbour.sortOrder)
                dao.updateSortOrder(neighbour.id, task.sortOrder)
            } else {
                // Historical tasks may all have position zero. Resolve ties once without changing any other column.
                val rows = dao.tasksForOrdering().toMutableList()
                val a = rows.indexOfFirst { it.id == taskId }; val b = rows.indexOfFirst { it.id == neighbourId }
                java.util.Collections.swap(rows, a, b)
                rows.forEachIndexed { index, row -> if (row.sortOrder != index.toLong()) dao.updateSortOrder(row.id, index.toLong()) }
            }
            true
        }

    override suspend fun loadDraft(taskId: String, date: LocalDate, scope: EditScope): TaskDraft? = database.withTransaction {
        val task = dao.task(taskId) ?: return@withTransaction null
        requireOccurrence(taskId, date)
        val rule = if(scope==EditScope.SINGLE) dao.version(taskId,date)?.toDomain()?:dao.rule(taskId)?.toDomain() else dao.rule(taskId)?.toDomain()
        val single = scope == EditScope.SINGLE && rule != null
        val exception = if (single) dao.exception(taskId, date) else null
        TaskDraft(exception?.titleOverride ?: task.title, exception?.noteOverride ?: task.note,
            if (single) date else task.startDate,
            if (exception?.timeOverridden == true) exception.scheduledMinuteOverride else task.scheduledMinute,
            exception?.priorityOverride ?: task.priority, rule)
    }

    override suspend fun updateTask(taskId: String, occurrenceDate: LocalDate, draft: TaskDraft, scope: EditScope) = database.withTransaction {
        draft.validate(allowOutOfRuleDate = scope == EditScope.SINGLE)
        requireOccurrence(taskId, occurrenceDate)
        val task = requireNotNull(dao.task(taskId))
        val rule = if(scope==EditScope.SINGLE) dao.version(taskId,occurrenceDate)?.toDomain()?:dao.rule(taskId)?.toDomain() else dao.rule(taskId)?.toDomain()
        require(draft.rule == rule) { "本阶段不修改既有重复规则" }
        if (rule != null) require(draft.date == if (scope == EditScope.SINGLE) occurrenceDate else task.startDate) { "重复任务日期在本阶段保持固定" }
        if (rule != null && scope == EditScope.SINGLE) {
            dao.saveException(TaskExceptionEntity(taskId, occurrenceDate, titleOverride = draft.title.trim(), noteOverride = draft.note,
                updatedAt = clock.millis(), scheduledMinuteOverride = draft.scheduledMinute, timeOverridden = true, priorityOverride = draft.priority))
        } else {
            val oldState = if (rule == null && draft.date != task.startDate) dao.dayState(taskId, task.startDate) else null
            dao.updateTask(task.copy(title = draft.title.trim(), note = draft.note, startDate = draft.date,
                scheduledMinute = draft.scheduledMinute, priority = draft.priority, updatedAt = clock.millis()))
            if (oldState != null) {
                dao.deleteDayState(taskId, task.startDate)
                dao.saveDayState(oldState.copy(date = draft.date, updatedAt = clock.millis()))
            }
        }
    }

    private suspend fun requireOccurrence(id: String, date: LocalDate) {
        require(occurrenceValid(id,date)) { "该日期没有有效任务" }
    }

    override suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean) = database.withTransaction {
        requireOccurrence(taskId, date)
        writeCompletion(taskId, date, completed)
    }

    override suspend fun completeFromSnapshot(taskId: String, date: LocalDate, expectedCompleted: Boolean,
        expectedRevision: Long, target: Boolean, today: () -> LocalDate): CompletionResult = database.withTransaction {
        if (date != today()) return@withTransaction CompletionResult.EXPIRED
        val task = dao.task(taskId) ?: return@withTransaction CompletionResult.MISSING
        if (!occurrenceValid(taskId,date)) return@withTransaction CompletionResult.MISSING
        val actual = dao.dayState(taskId, date)?.completed ?: false
        if (task.stateRevision != expectedRevision || actual != expectedCompleted || target == expectedCompleted)
            return@withTransaction CompletionResult.STALE
        writeCompletion(taskId, date, target)
        CompletionResult.APPLIED
    }

    private suspend fun writeCompletion(taskId: String, date: LocalDate, completed: Boolean) {
        val actual = dao.dayState(taskId, date)?.completed ?: false
        if (actual == completed) return
        val task = requireNotNull(dao.task(taskId))
        check(task.stateRevision < Long.MAX_VALUE) { "State revision exhausted" }
        if (completed) dao.saveDayState(TaskDayStateEntity(taskId, date, true, clock.millis()))
        else dao.deleteDayState(taskId, date)
        dao.updateTask(task.copy(stateRevision = task.stateRevision + 1))
    }

    override suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope) = database.withTransaction {
        val cleanTitle = title.trim()
        validateContent(cleanTitle, note)
        val task = requireNotNull(dao.task(taskId)) { "任务不存在" }
        if (scope == EditScope.SINGLE) requireOccurrence(taskId, date)
        if (scope == EditScope.SERIES || dao.rule(taskId) == null) {
            dao.updateTask(task.copy(title = cleanTitle, note = note, updatedAt = clock.millis()))
        } else {
            val previous = dao.exception(taskId, date)
            dao.saveException((previous ?: TaskExceptionEntity(taskId, date, updatedAt = clock.millis())).copy(
                titleOverride = cleanTitle, noteOverride = note, updatedAt = clock.millis()))
        }
    }

    override suspend fun delete(taskId: String, date: LocalDate, scope: EditScope) = database.withTransaction {
        requireNotNull(dao.task(taskId)) { "任务不存在" }
        if (scope == EditScope.SERIES || dao.rule(taskId) == null) {
            if (scope == EditScope.SINGLE) requireOccurrence(taskId, date)
            dao.deleteTask(taskId)
        } else {
            requireOccurrence(taskId, date)
            dao.saveException(TaskExceptionEntity(taskId, date, deleted = true, updatedAt = clock.millis()))
            dao.deleteDayState(taskId, date)
        }
    }
}
