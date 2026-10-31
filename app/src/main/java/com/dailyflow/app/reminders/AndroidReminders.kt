package com.dailyflow.app.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.*
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.MainActivity
import com.dailyflow.app.R
import com.dailyflow.app.data.DailyFlowDatabase
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

object ReminderIntents {
    const val ALARM = "com.dailyflow.app.REMINDER_ALARM"
    const val OPEN = "com.dailyflow.app.OPEN_REMINDER"
    const val TOKEN = "reminderToken"
    private fun uri(authority: String, key: ReminderKey) = Uri.Builder().scheme("dailyflow").authority(authority)
        .appendPath(key.taskId).appendPath(key.date.toString()).build()
    fun alarm(context: Context, value: ReminderAlarmEntity) = Intent(context, ReminderReceiver::class.java)
        .setAction(ALARM).setData(uri("reminder-alarm", ReminderKey(value.taskId, value.date))).putExtra(TOKEN, value.token)
    fun detail(context: Context, key: ReminderKey) = Intent(context, MainActivity::class.java)
        .setAction(OPEN).setData(uri("reminder", key)).putExtra("reminderTaskId", key.taskId).putExtra("reminderDate", key.date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun key(intent: Intent, authority: String): ReminderKey? = try {
        val data = requireNotNull(intent.data)
        require(data.scheme == "dailyflow" && data.authority == authority && data.pathSegments.size == 2)
        val id = data.pathSegments[0];require(id.isNotBlank() && id.length <= 100)
        ReminderKey(id, LocalDate.parse(data.pathSegments[1]))
    } catch (_: Exception) { null }
    fun alarmKey(intent: Intent) = if(intent.action == ALARM) key(intent, "reminder-alarm") else null
    fun destination(intent: Intent): ReminderKey? {
        if(intent.action != OPEN)return null
        val key=key(intent,"reminder")?:return null
        return key.takeIf{intent.getStringExtra("reminderTaskId")==it.taskId && intent.getStringExtra("reminderDate")==it.date.toString()}
    }
}

class AndroidReminderScheduler(private val context: Context) : ReminderScheduler {
    private val manager get() = context.getSystemService(AlarmManager::class.java)
    override fun schedule(alarm: ReminderAlarmEntity, atMillis: Long): String {
        val pending = PendingIntent.getBroadcast(context, 0, ReminderIntents.alarm(context, alarm), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending); return "EXACT" }
            catch (_: SecurityException) { /* Permission revoked between check and API call: explicit inexact fallback. */ }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        return "INEXACT"
    }
    override fun cancel(alarm: ReminderAlarmEntity) {
        val pending = PendingIntent.getBroadcast(context, 0, ReminderIntents.alarm(context, alarm), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        manager.cancel(pending);pending.cancel()
    }
}

class AndroidReminderNotifier(private val context: Context) : ReminderNotifier {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    init { createChannel() }
    fun createChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "DailyFlow 本地任务与重复任务提醒，可在系统设置中管理"
        })
    }
    override fun allowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    override fun post(plan: ReminderPlan) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Notification permission unavailable")
        check(allowed()) { "Notification channel unavailable" }
        val click = PendingIntent.getActivity(context, 0, ReminderIntents.detail(context,plan.key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = plan.note.ifBlank { "任务提醒 · " + plan.key.date }
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_widget_empty)
            .setContentTitle(plan.title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(click).setAutoCancel(true).setOnlyAlertOnce(true).setWhen(plan.dueAt)
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        manager.notify(plan.key.tag, 0, notification)
    }
    override fun cancel(key: ReminderKey) { manager.cancel(key.tag, 0) }
    companion object { const val CHANNEL = "dailyflow_task_reminders_v1" }
}

object ReminderPermissions {
    fun canExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
    fun notificationSettings(context: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun exactSettings(context: Context) = Intent(if(Build.VERSION.SDK_INT >= 31) Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM else Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:" + context.packageName))
}

class ReminderRuntime(private val context: Context, database: DailyFlowDatabase) {
    val notifier = AndroidReminderNotifier(context)
    val engine = ReminderEngine(database, AndroidReminderScheduler(context), notifier, ReminderDeviceClock(), repair = ::repairWork)
    private fun repairWork(enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (enabled) manager.enqueueUniquePeriodicWork(REPAIR, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderRepairWorker>(15, TimeUnit.MINUTES).build())
        else manager.cancelUniqueWork(REPAIR)
    }
    suspend fun refresh() = engine.reconcile()
    companion object { const val REPAIR = "dailyflow-reminder-repair-v1" }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = ReminderIntents.alarmKey(intent) ?: return
        val token = intent.getStringExtra(ReminderIntents.TOKEN)?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return
        val app = context.applicationContext as? DailyFlowApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { app.reminders.engine.fire(key.taskId, key.date, token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { requestReminderRepair(context) }
            finally { pending.finish() }
        }
    }
}
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!handles(intent.action)) return
        val app = context.applicationContext as? DailyFlowApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { app.reminders.refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { requestReminderRepair(context) }
            finally { pending.finish() }
        }
    }
    companion object {
        fun handles(action: String?) = action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED) ||
            (Build.VERSION.SDK_INT >= 31 && action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
    }
}
private fun requestReminderRepair(context: Context) {
    runCatching { WorkManager.getInstance(context).enqueueUniqueWork("dailyflow-reminder-recover-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ReminderRepairWorker>().build()) }
}
class ReminderRepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as DailyFlowApplication).reminders.refresh();Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
