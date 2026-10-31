package com.dailyflow.app.reminders

import androidx.room.withTransaction
import com.dailyflow.app.data.DailyFlowDatabase
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ReminderScheduler {
    fun schedule(alarm: ReminderAlarmEntity, atMillis: Long = alarm.dueAt): String
    fun cancel(alarm: ReminderAlarmEntity)
}
interface ReminderNotifier {
    fun allowed(): Boolean
    fun post(plan: ReminderPlan)
    fun cancel(key: ReminderKey)
}

/** One coordinator per app process serializes commits, reconciliation and broadcast delivery. */
class ReminderEngine(private val db: DailyFlowDatabase, private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier, private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }, private val repair: (Boolean) -> Unit = {}) {
    private val mutex = Mutex()
    private val dao get() = db.reminderDao()
    suspend fun <T> mutate(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock {
        val result = block()
        safeReconcile()
        result
    } }
    suspend fun reconcile() = withContext(Dispatchers.IO) { mutex.withLock { safeReconcile() } }
    suspend fun fire(taskId: String, date: LocalDate, token: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = dao.alarm() ?: return@withLock
        if (current.taskId != taskId || current.date != date || current.token != token) return@withLock
        if (current.dueAt > clock.millis()) { safeReconcile(); return@withLock }
        try {
            dispatch(current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { persistFailure(current, "ERROR", "提醒处理失败，将自动重试") }
    }
    }
    private suspend fun safeReconcile() {
        try {
            val current = dao.alarm()
            if (current?.state == "NOTIFY_ERROR" && current.dueAt <= clock.millis() && clock.millis() - current.dueAt <= LATE_WINDOW) dispatch(current)
            else reconcileLocked()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            dao.alarm()?.let { persistFailure(it, "ERROR", "系统调度失败，将自动重试") }
        }
    }
    private suspend fun snapshot() = db.withTransaction { dao.snapshot() }
    private suspend fun persistFailure(alarm: ReminderAlarmEntity, state: String, message: String) {
        dao.saveAlarm(alarm.copy(state = state, detail = message))
        runCatching { repair(true) }
    }
    private suspend fun reconcileLocked() {
        val state = snapshot(); val old = dao.alarm()
        // Only records which have not already been canceled need another system cancel.
        for (delivery in state.deliveries.filter { it.status != "CANCELED" && it.status != "FAILED" }) {
            val key = ReminderKey(delivery.taskId, delivery.date)
            if (state.plan(key, zone(), excludeSent = false) == null) {
                notifier.cancel(key)
                dao.saveDelivery(delivery.copy(status = "CANCELED"))
            }
        }
        if (!state.hasEnabled) {
            if (old != null) { scheduler.cancel(old); dao.clearAlarm(); runCatching { repair(false) } }
            return
        }
        runCatching { repair(true) }
        val now = clock.instant(); val zoneId = zone()
        val next = state.next(now, zoneId)
        val maintenance = now.atZone(zoneId).toLocalDate().plusDays(1).atTime(0, 5).atZone(zoneId).toInstant().toEpochMilli()
        val selected = next?.takeIf { it.dueAt <= maintenance }
        val key = selected?.key ?: ReminderKey(MAINTENANCE, now.atZone(zoneId).toLocalDate().plusDays(1))
        val due = selected?.dueAt ?: maintenance
        val cursor = ReminderAlarmEntity(taskId = key.taskId, date = key.date, dueAt = due, token = UUID.randomUUID().toString())
        // Keep the old cursor until cancellation succeeds. Never lose the only cancel identity.
        if (old != null) scheduler.cancel(old)
        dao.saveAlarm(cursor)
        if (!notifier.allowed()) {
            dao.saveAlarm(cursor.copy(state = "NOTIFICATIONS_BLOCKED", detail = "通知或提醒渠道已关闭，设置已保留"))
            return
        }
        try {
            val mode = scheduler.schedule(cursor)
            dao.saveAlarm(cursor.copy(state = mode, detail = if (mode == "INEXACT") "未获精确闹钟授权，提醒可能延迟" else ""))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { persistFailure(cursor, "ERROR", "系统闹钟未注册，将自动重试") }
    }
    private suspend fun dispatch(cursor: ReminderAlarmEntity) {
        val now = clock.instant()
        if (!notifier.allowed() || now.toEpochMilli() - cursor.dueAt > LATE_WINDOW) { reconcileLocked(); return }
        val state = snapshot()
        var failed: ReminderPlan? = null
        run {
            val from = cursor.date.coerceAtLeast(now.atZone(zone()).toLocalDate().minusDays(1))
            val due = state.candidates(now, zone(), from).filter { it.dueAt >= cursor.dueAt && it.dueAt <= now.toEpochMilli() }
            for (plan in due) {
                // Room and NotificationManager cannot commit atomically. Claim first to avoid duplicate alerts after process death.
                dao.saveDelivery(ReminderDeliveryEntity(plan.key.taskId, plan.key.date, clock.millis(), "CLAIMED"))
                try {
                    notifier.post(plan)
                    dao.saveDelivery(ReminderDeliveryEntity(plan.key.taskId, plan.key.date, clock.millis(), "DELIVERED"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    dao.saveDelivery(ReminderDeliveryEntity(plan.key.taskId, plan.key.date, clock.millis(), "FAILED"))
                    if (failed == null) failed = plan
                }
            }
        }
        if (failed != null) {
            val retry = cursor.copy(taskId = failed.key.taskId, date = failed.key.date, dueAt = failed.dueAt,
                token = UUID.randomUUID().toString(), state = "NOTIFY_ERROR", detail = "通知发送失败，将自动重试")
            scheduler.cancel(cursor); dao.saveAlarm(retry)
            runCatching { scheduler.schedule(retry, clock.millis() + 60_000) }
            runCatching { repair(true) }
        } else reconcileLocked()
    }
    companion object {
        const val MAINTENANCE = "_dailyflow_reminder_maintenance_"
        const val LATE_WINDOW = 24 * 60 * 60 * 1000L
    }
}
