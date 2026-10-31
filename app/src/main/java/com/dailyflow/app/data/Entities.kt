package com.dailyflow.app.data

import androidx.room.*
import com.dailyflow.app.domain.Priority
import com.dailyflow.app.domain.Frequency
import com.dailyflow.app.domain.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate

@Entity(tableName = "categories")
data class CategoryEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "tasks", foreignKeys = [ForeignKey(
    entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL
)], indices = [Index("categoryId"), Index("startDate")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val note: String,
    val startDate: LocalDate,
    val categoryId: String? = null,
    val sortOrder: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val scheduledMinute: Int? = null,
    @ColumnInfo(defaultValue = "0") val priority: Priority = Priority.NORMAL,
    @ColumnInfo(defaultValue = "0") val stateRevision: Long = 0,
)

@Entity(tableName = "recurrence_rules", foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE
)])
data class RecurrenceRuleEntity(
    @PrimaryKey val taskId: String,
    val frequency: Frequency,
    val interval: Int,
    val weekdayMask: Int,
    val untilDate: LocalDate?,
) {
    fun toDomain() = RecurrenceRule(frequency, interval,
        DayOfWeek.entries.filter { weekdayMask and (1 shl (it.value - 1)) != 0 }.toSet(), untilDate)
    companion object {
        fun from(taskId: String, rule: RecurrenceRule) = RecurrenceRuleEntity(taskId, rule.frequency,
            rule.interval, rule.weekdays.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }, rule.untilDate)
    }
}

@Entity(tableName = "task_day_states", primaryKeys = ["taskId", "date"], foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE
)])
data class TaskDayStateEntity(val taskId: String, val date: LocalDate, val completed: Boolean, val updatedAt: Long)

@Entity(tableName = "task_exceptions", primaryKeys = ["taskId", "date"], foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE
)])
data class TaskExceptionEntity(
    val taskId: String,
    val date: LocalDate,
    val deleted: Boolean = false,
    val titleOverride: String? = null,
    val noteOverride: String? = null,
    val updatedAt: Long,
    val scheduledMinuteOverride: Int? = null,
    @ColumnInfo(defaultValue = "0") val timeOverridden: Boolean = false,
    val priorityOverride: Priority? = null,
)

class RoomConverters {
    @TypeConverter fun priorityToInt(value: Priority?): Int? = value?.ordinal
    @TypeConverter fun intToPriority(value: Int?): Priority? = value?.let { Priority.entries[it] }
    @TypeConverter fun dateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
    @TypeConverter fun frequencyToString(value: Frequency?): String? = value?.name
    @TypeConverter fun stringToFrequency(value: String?): Frequency? = value?.let(Frequency::valueOf)
}

data class DailyTaskRow(
    @Embedded val task: TaskEntity,
    @Embedded(prefix = "rule_") val rule: RecurrenceRuleEntity?,
    val ruleAnchor: LocalDate,
    val dayCompleted: Boolean?,
    val exceptionId: String?,
    val titleOverride: String?,
    val noteOverride: String?,
    val scheduledMinuteOverride: Int?,
    val timeOverridden: Boolean?,
    val priorityOverride: Priority?,
    val reminderEnabled: Boolean? = null,
    val reminderMinute: Int? = null,
    val reminderOffset: Int? = null,
    val reminderDeliveryStatus: String? = null,
)

@Entity(tableName = "recurrence_versions", primaryKeys = ["taskId", "effectiveFrom"], foreignKeys = [ForeignKey(
    entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE
)])
data class RecurrenceVersionEntity(val taskId: String, val effectiveFrom: LocalDate, val frequency: Frequency,
    val interval: Int, val weekdayMask: Int, val untilDate: LocalDate?) {
    fun toDomain() = RecurrenceRuleEntity(taskId,frequency,interval,weekdayMask,untilDate).toDomain()
}
