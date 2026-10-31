package com.dailyflow.app.reminders

import com.dailyflow.app.data.DailyFlowDatabase
import com.dailyflow.app.domain.*
import java.time.*

/** Called inside the same task transaction: task and reminder settings commit together. */
class ReminderRepository(private val db: DailyFlowDatabase, private val clock: Clock = ReminderDeviceClock()) {
    private val dao get() = db.reminderDao()
    suspend fun setting(id: String, date: LocalDate, scope: EditScope, recurring: Boolean): ReminderSetting? =
        (if (recurring && scope == EditScope.SINGLE) dao.override(id, date)?.setting() else null) ?: dao.rule(id)?.setting()

    suspend fun save(id: String, date: LocalDate, scope: EditScope, recurring: Boolean, setting: ReminderSetting?) {
        if (setting == null) {
            if (recurring && scope == EditScope.SINGLE) dao.clearOverride(id, date)
            return
        }
        if (setting.enabled) {
            if (recurring && scope == EditScope.SERIES) require(setting.offsetDays == 0) { "系列提醒在每个有效实例当天，请单独修改某天的提醒日期" }
            else require(reminderTime(date, setting, clock.zone) > clock.instant()) { "提醒时间已过去，请选择未来时间" }
        }
        if (recurring && scope == EditScope.SINGLE) {
            dao.saveOverride(ReminderOverrideEntity(id, date, setting.enabled, setting.minute, setting.offsetDays))
        } else {
            dao.saveRule(ReminderRuleEntity(id, setting.enabled, setting.minute, setting.offsetDays))
            if (recurring && !setting.enabled) dao.clearOverrides(id)
        }
    }
}

/** Unlike Clock.systemDefaultZone(), follows a device time-zone change without process restart. */
class ReminderDeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
}
