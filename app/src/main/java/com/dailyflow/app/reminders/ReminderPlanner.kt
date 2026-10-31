package com.dailyflow.app.reminders

import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import java.time.*

data class ReminderKey(val taskId: String, val date: LocalDate) {
    val tag: String get() = "dailyflow-reminder:$taskId:$date"
}
data class ReminderPlan(val key: ReminderKey, val dueAt: Long, val title: String, val note: String)

class ReminderSnapshot(
    val tasks: List<TaskEntity>, rules: List<RecurrenceRuleEntity>, versions: List<RecurrenceVersionEntity>,
    exceptions: List<TaskExceptionEntity>, states: List<TaskDayStateEntity>,
    val configurations: List<ReminderRuleEntity>, val overrides: List<ReminderOverrideEntity>, val deliveries: List<ReminderDeliveryEntity>,
) {
    private val rulesById = rules.associateBy { it.taskId }
    private val versionsById = versions.groupBy { it.taskId }
    private val exceptionsByKey = exceptions.associateBy { ReminderKey(it.taskId, it.date) }
    private val statesByKey = states.associateBy { ReminderKey(it.taskId, it.date) }
    private val configById = configurations.associateBy { it.taskId }
    private val overrideByKey = overrides.associateBy { ReminderKey(it.taskId, it.date) }
    private val taskById = tasks.associateBy { it.id }
    private val sent = deliveries.filter { it.status != "FAILED" }.map { ReminderKey(it.taskId, it.date) }.toSet()
    val hasEnabled get() = configurations.any { it.enabled } || overrides.any { it.enabled }
    fun plan(key: ReminderKey, zone: ZoneId, excludeSent: Boolean = true): ReminderPlan? {
        val task = taskById[key.taskId] ?: return null
        if (key.date < task.startDate || (excludeSent && key in sent)) return null
        val ex = exceptionsByKey[key]
        if (ex?.deleted == true || statesByKey[key]?.completed == true) return null
        val version = versionsById[task.id]?.filter { it.effectiveFrom <= key.date }?.maxByOrNull { it.effectiveFrom }
        val rule = version?.toDomain() ?: rulesById[task.id]?.toDomain()
        if (!RecurrenceEngine.occursOn(version?.effectiveFrom ?: task.startDate, rule, key.date) && ex == null) return null
        val setting = overrideByKey[key]?.setting() ?: configById[task.id]?.setting() ?: return null
        if (!setting.enabled || setting.minute !in 0..1439) return null
        val local = key.date.plusDays(setting.offsetDays.toLong()).atTime(setting.minute / 60, setting.minute % 60)
        // java.time shifts a DST gap forward and selects the earlier offset for an overlap.
        return ReminderPlan(key, local.atZone(zone).toInstant().toEpochMilli(), ex?.titleOverride ?: task.title, ex?.noteOverride ?: task.note)
    }
    fun candidates(now: Instant, zone: ZoneId, from: LocalDate? = null): List<ReminderPlan> {
        val today = now.atZone(zone).toLocalDate()
        val first = from?.coerceAtMost(today)?.coerceAtLeast(today.minusDays(1)) ?: today
        val result = mutableListOf<ReminderPlan>()
        for (task in tasks) {
            val dates = mutableSetOf<LocalDate>()
            if (rulesById[task.id] == null) dates.add(task.startDate)
            else {
                var date = first.coerceAtLeast(task.startDate)
                val last = today.plusDays(366)
                while (date <= last) { dates.add(date); date = date.plusDays(1) }
            }
            overrides.filter { it.taskId == task.id }.forEach { dates.add(it.date) }
            dates.forEach { date -> plan(ReminderKey(task.id, date), zone)?.let(result::add) }
        }
        return result.sortedWith(compareBy<ReminderPlan> { it.dueAt }.thenBy { it.key.taskId }.thenBy { it.key.date })
    }
    fun next(now: Instant, zone: ZoneId): ReminderPlan? = candidates(now, zone).firstOrNull { it.dueAt > now.toEpochMilli() }
}

fun reminderTime(date: LocalDate, setting: ReminderSetting, zone: ZoneId): Instant =
    date.plusDays(setting.offsetDays.toLong()).atTime(setting.minute / 60, setting.minute % 60).atZone(zone).toInstant()
