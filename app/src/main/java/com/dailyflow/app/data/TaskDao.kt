package com.dailyflow.app.data

import androidx.room.*
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

const val DAILY_QUERY = """
SELECT t.*, COALESCE(v.taskId,r.taskId) AS rule_taskId, COALESCE(v.frequency,r.frequency) AS rule_frequency,
       COALESCE(v.interval,r.interval) AS rule_interval, COALESCE(v.weekdayMask,r.weekdayMask) AS rule_weekdayMask,
       CASE WHEN v.taskId IS NULL THEN r.untilDate ELSE v.untilDate END AS rule_untilDate,
       COALESCE(v.effectiveFrom,t.startDate) AS ruleAnchor, s.completed AS dayCompleted,
       e.taskId AS exceptionId, e.titleOverride, e.noteOverride,
       e.scheduledMinuteOverride, e.timeOverridden, e.priorityOverride,
       COALESCE(ro.enabled,rr.enabled) AS reminderEnabled, COALESCE(ro.minute,rr.minute) AS reminderMinute,
       COALESCE(ro.offsetDays,rr.offsetDays) AS reminderOffset, rd.status AS reminderDeliveryStatus
FROM tasks t
LEFT JOIN recurrence_rules r ON r.taskId = t.id
LEFT JOIN recurrence_versions v ON v.taskId=t.id AND v.effectiveFrom=(SELECT MAX(v2.effectiveFrom) FROM recurrence_versions v2 WHERE v2.taskId=t.id AND v2.effectiveFrom<=:date)
LEFT JOIN task_day_states s ON s.taskId=t.id AND s.date=:date
LEFT JOIN task_exceptions e ON e.taskId=t.id AND e.date=:date
LEFT JOIN reminder_rules rr ON rr.taskId=t.id
LEFT JOIN reminder_overrides ro ON ro.taskId=t.id AND ro.date=:date
LEFT JOIN reminder_deliveries rd ON rd.taskId=t.id AND rd.date=:date
WHERE t.startDate<=:date AND (t.startDate=:date OR r.taskId IS NOT NULL OR s.taskId IS NOT NULL OR e.taskId IS NOT NULL)
  AND COALESCE(e.deleted,0)=0
ORDER BY t.sortOrder,t.createdAt,t.id
"""

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder,createdAt,id") suspend fun tasksForOrdering(): List<TaskEntity>
    @Query("SELECT MAX(sortOrder) FROM tasks") suspend fun maximumSortOrder(): Long?
    @Query("UPDATE tasks SET sortOrder=:position WHERE id=:id AND sortOrder!=:position")
    suspend fun updateSortOrder(id: String, position: Long): Int
    @Query("SELECT (SELECT COUNT(*) FROM tasks)+(SELECT COUNT(*) FROM recurrence_rules)+(SELECT COUNT(*) FROM recurrence_versions)+(SELECT COUNT(*) FROM task_day_states)+(SELECT COUNT(*) FROM task_exceptions)+(SELECT COUNT(*) FROM reminder_rules)+(SELECT COUNT(*) FROM reminder_overrides)+(SELECT COUNT(*) FROM reminder_deliveries)") fun observePlanningSignal(): Flow<Int>
    @Query("SELECT t.* FROM tasks t INNER JOIN recurrence_rules r ON r.taskId=t.id ORDER BY t.createdAt,t.id") suspend fun allSeries(): List<TaskEntity>
    @Query("SELECT * FROM recurrence_versions WHERE taskId=:id AND effectiveFrom<=:date ORDER BY effectiveFrom DESC LIMIT 1") suspend fun version(id: String,date: LocalDate): RecurrenceVersionEntity?
    @Query("SELECT * FROM recurrence_versions WHERE taskId=:id ORDER BY effectiveFrom") suspend fun versions(id: String): List<RecurrenceVersionEntity>
    @Query("DELETE FROM recurrence_versions WHERE taskId=:id AND effectiveFrom>=:date") suspend fun deleteFutureVersions(id: String,date: LocalDate)
    @Insert suspend fun insertVersion(version: RecurrenceVersionEntity)
    @Query("SELECT * FROM task_exceptions WHERE taskId=:id ORDER BY date DESC") fun observeExceptions(id: String): Flow<List<TaskExceptionEntity>>
    @Query("DELETE FROM task_exceptions WHERE taskId=:id AND date=:date") suspend fun deleteException(id: String,date: LocalDate)
    @Query(DAILY_QUERY) fun observeDayCandidates(date: LocalDate): Flow<List<DailyTaskRow>>
    @Query(DAILY_QUERY) suspend fun getDayCandidates(date: LocalDate): List<DailyTaskRow>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM recurrence_rules WHERE taskId = :id") suspend fun rule(id: String): RecurrenceRuleEntity?
    @Query("SELECT * FROM task_exceptions WHERE taskId = :id AND date = :date") suspend fun exception(id: String, date: LocalDate): TaskExceptionEntity?
    @Query("SELECT * FROM task_day_states WHERE taskId = :id AND date = :date") suspend fun dayState(id: String, date: LocalDate): TaskDayStateEntity?
    @Insert suspend fun insertTask(task: TaskEntity)
    @Update suspend fun updateTask(task: TaskEntity)
    @Insert suspend fun insertRule(rule: RecurrenceRuleEntity)
    @Upsert suspend fun saveDayState(state: TaskDayStateEntity)
    @Upsert suspend fun saveException(exception: TaskExceptionEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM task_day_states WHERE taskId = :id AND date = :date") suspend fun deleteDayState(id: String, date: LocalDate)
    @Insert suspend fun insertCategory(category: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: String)
    @Query("SELECT count(*) FROM tasks") suspend fun taskCount(): Int
    @Query("SELECT count(*) FROM task_day_states") suspend fun dayStateCount(): Int
    @Query("SELECT count(*) FROM task_exceptions") suspend fun exceptionCount(): Int
    @Query("SELECT count(*) FROM recurrence_rules") suspend fun ruleCount(): Int
}
