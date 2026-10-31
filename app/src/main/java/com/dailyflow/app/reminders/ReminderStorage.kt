package com.dailyflow.app.reminders

import androidx.room.*
import com.dailyflow.app.data.*
import com.dailyflow.app.domain.ReminderSetting
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reminder_rules", foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)])
data class ReminderRuleEntity(@PrimaryKey val taskId: String, val enabled: Boolean, val minute: Int, val offsetDays: Int) {
    fun setting() = ReminderSetting(enabled, minute, offsetDays)
}
@Entity(tableName = "reminder_overrides", primaryKeys = ["taskId", "date"], foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)])
data class ReminderOverrideEntity(val taskId: String, val date: LocalDate, val enabled: Boolean, val minute: Int, val offsetDays: Int) {
    fun setting() = ReminderSetting(enabled, minute, offsetDays)
}
// No task FK: deleted tasks still need their old system notifications canceled.
@Entity(tableName = "reminder_deliveries", primaryKeys = ["taskId", "date"])
data class ReminderDeliveryEntity(val taskId: String, val date: LocalDate, val deliveredAt: Long, val status: String)
@Entity(tableName = "reminder_alarm")
data class ReminderAlarmEntity(@PrimaryKey val slot: Int = 0, val taskId: String, val date: LocalDate,
    val dueAt: Long, val token: String, val state: String = "PLANNED", val detail: String = "")

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_rules") suspend fun rules(): List<ReminderRuleEntity>
    @Query("SELECT * FROM reminder_overrides") suspend fun overrides(): List<ReminderOverrideEntity>
    @Query("SELECT * FROM reminder_deliveries") suspend fun deliveries(): List<ReminderDeliveryEntity>
    @Query("SELECT * FROM reminder_alarm WHERE slot=0") suspend fun alarm(): ReminderAlarmEntity?
    @Query("SELECT * FROM reminder_alarm WHERE slot=0") fun observeAlarm(): Flow<ReminderAlarmEntity?>
    @Query("SELECT * FROM reminder_rules WHERE taskId=:id") suspend fun rule(id: String): ReminderRuleEntity?
    @Query("SELECT * FROM reminder_overrides WHERE taskId=:id AND date=:date") suspend fun override(id: String, date: LocalDate): ReminderOverrideEntity?
    @Upsert suspend fun saveRule(value: ReminderRuleEntity)
    @Upsert suspend fun saveOverride(value: ReminderOverrideEntity)
    @Upsert suspend fun saveDelivery(value: ReminderDeliveryEntity)
    @Upsert suspend fun saveAlarm(value: ReminderAlarmEntity)
    @Query("DELETE FROM reminder_overrides WHERE taskId=:id") suspend fun clearOverrides(id: String)
    @Query("DELETE FROM reminder_overrides WHERE taskId=:id AND date=:date") suspend fun clearOverride(id: String, date: LocalDate)
    @Query("DELETE FROM reminder_alarm") suspend fun clearAlarm()
    @Query("SELECT * FROM tasks WHERE id IN (SELECT taskId FROM reminder_rules UNION SELECT taskId FROM reminder_overrides)") suspend fun tasks(): List<TaskEntity>
    @Query("SELECT * FROM recurrence_rules WHERE taskId IN (SELECT taskId FROM reminder_rules UNION SELECT taskId FROM reminder_overrides)") suspend fun recurrence(): List<RecurrenceRuleEntity>
    @Query("SELECT * FROM recurrence_versions WHERE taskId IN (SELECT taskId FROM reminder_rules UNION SELECT taskId FROM reminder_overrides)") suspend fun versions(): List<RecurrenceVersionEntity>
    @Query("SELECT * FROM task_exceptions WHERE taskId IN (SELECT taskId FROM reminder_rules UNION SELECT taskId FROM reminder_overrides)") suspend fun exceptions(): List<TaskExceptionEntity>
    @Query("SELECT * FROM task_day_states WHERE taskId IN (SELECT taskId FROM reminder_rules UNION SELECT taskId FROM reminder_overrides)") suspend fun states(): List<TaskDayStateEntity>
}

suspend fun ReminderDao.snapshot() = ReminderSnapshot(tasks(), recurrence(), versions(), exceptions(), states(), rules(), overrides(), deliveries())
