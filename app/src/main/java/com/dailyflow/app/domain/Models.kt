package com.dailyflow.app.domain

import java.time.DayOfWeek
import java.time.LocalDate

/** Definitions and dates are independent; no permanent future occurrences are generated. */
data class Task(
    val id: String,
    val title: String,
    val note: String = "",
    val startDate: LocalDate,
    val categoryId: String? = null,
    val sortOrder: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init { require(id.isNotBlank()); validateContent(title, note) }
}

fun validateContent(title: String, note: String) {
    require(title.isNotBlank() && title == title.trim() && title.length <= 200) { "标题须为 1—200 字且无首尾空白" }
    require(note.length <= 4000) { "备注最多 4000 字" }
}

enum class Frequency { DAILY, WEEKLY }
enum class EditScope { SINGLE, SERIES }
enum class Priority { NORMAL, LOW, MEDIUM, HIGH }

data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val untilDate: LocalDate? = null,
) {
    init {
        require(interval > 0) { "重复间隔必须为正数" }
        require((frequency == Frequency.WEEKLY && weekdays.isNotEmpty()) ||
            (frequency == Frequency.DAILY && weekdays.isEmpty())) { "每周重复必须选择周几；每日重复不接受周几" }
    }
}

data class TaskOccurrence(
    val taskId: String,
    val date: LocalDate,
    val title: String,
    val note: String,
    val completed: Boolean,
    val recurring: Boolean,
    val overridden: Boolean,
    val scheduledMinute: Int? = null,
    val priority: Priority = Priority.NORMAL,
    val recurrence: RecurrenceRule? = null,
    val stateRevision: Long = 0,
    val sortOrder: Long = 0,
    val createdAt: Long = 0,
    val reminder: ReminderSetting? = null,
    val reminderDeliveryStatus: String? = null,
)

/** UI write model. Time is display-only in 2C, not an alarm. */
data class TaskDraft(
    val title: String = "",
    val note: String = "",
    val date: LocalDate,
    val scheduledMinute: Int? = null,
    val priority: Priority = Priority.NORMAL,
    val rule: RecurrenceRule? = null,
    val reminder: ReminderSetting? = null,
    val reminderEdited: Boolean = false,
) {
    fun validate(allowOutOfRuleDate: Boolean = false) {
        validateContent(title.trim(), note)
        require(scheduledMinute == null || scheduledMinute in 0..1439) { "时间无效" }
        require(allowOutOfRuleDate || rule?.untilDate == null || rule.untilDate >= date) { "结束日期不得早于开始日期" }
    }
}

/** Widget writes require a snapshot match; never invert the current DB value blindly. */
enum class CompletionResult { APPLIED, STALE, MISSING, EXPIRED }


data class RuleChange(val effectiveFrom: LocalDate, val rule: RecurrenceRule)
data class SeriesSummary(val taskId: String, val title: String, val startDate: LocalDate, val rule: RecurrenceRule,
    val revision: Long, val changes: List<RuleChange> = emptyList())
data class TaskException(val taskId: String, val date: LocalDate, val deleted: Boolean, val title: String?)

/** Local wall-clock time relative to the occurrence date, never a persisted UTC timestamp. */
data class ReminderSetting(val enabled: Boolean = false, val minute: Int = 480, val offsetDays: Int = 0) {
    init { require(minute in 0..1439); require(offsetDays in -3652059..3652059) }
}
