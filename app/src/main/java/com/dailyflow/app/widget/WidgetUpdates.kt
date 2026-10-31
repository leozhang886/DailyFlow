package com.dailyflow.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.*
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

object WidgetUpdates {
    const val REFRESH_WORK = "dailyflow-widget-refresh"
    const val PERIODIC_WORK = "dailyflow-widget-periodic"
    const val MIDNIGHT = "com.dailyflow.app.WIDGET_MIDNIGHT"
    fun hasWidgets(context: Context): Boolean = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, DailyTaskWidgetReceiver::class.java)).isNotEmpty()
    fun request(context: Context) {
        if (!hasWidgets(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build())
    }
    fun schedule(context: Context) {
        if (!hasWidgets(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build())
        scheduleBoundary(context); request(context)
    }
    fun placedIds(context: Context): List<androidx.glance.GlanceId> {
        val glance = GlanceAppWidgetManager(context)
        return AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DailyTaskWidgetReceiver::class.java))
            .map { glance.getGlanceIdBy(it) }
    }
    suspend fun refreshAll(context: Context, update: suspend (androidx.glance.GlanceId) -> Unit = { DailyTaskWidget().update(context, it) }) {
        // Resolve bound receiver IDs directly; do not depend on Glance's provider-name cache.
        val ids = placedIds(context)
        refreshWidgetInstances(ids, update)
        if (ids.isNotEmpty()) scheduleBoundary(context)
    }
    private fun boundaryIntent(context: Context) = PendingIntent.getBroadcast(context, 7301,
        Intent(context, WidgetClockReceiver::class.java).setAction(MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun scheduleBoundary(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        // Inexact alarm: no exact-alarm permission. OS/Doze may delay rendering after midnight.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextWidgetMidnight(), boundaryIntent(context))
    }
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        context.getSystemService(AlarmManager::class.java).cancel(boundaryIntent(context))
    }
}
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        WidgetUpdates.refreshAll(applicationContext); Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, WidgetUpdates.MIDNIGHT)) return
        if (!WidgetUpdates.hasWidgets(context)) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { WidgetUpdates.schedule(context); WidgetUpdates.refreshAll(context) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("DailyFlowWidget", "Clock refresh deferred", e) }
            finally { pending.finish() }
        }
    }
}
