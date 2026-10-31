package com.dailyflow.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.glance.GlanceId
import androidx.glance.action.*
import androidx.glance.appwidget.action.ActionCallback
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.MainActivity
import com.dailyflow.app.domain.TaskOccurrence
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WidgetActions {
    val taskId = ActionParameters.Key<String>("taskId")
    val date = ActionParameters.Key<String>("date")
    val expected = ActionParameters.Key<Boolean>("expected")
    val revision = ActionParameters.Key<Long>("revision")
    val target = ActionParameters.Key<Boolean>("target")
    fun parameters(task: TaskOccurrence) = actionParametersOf(taskId to task.taskId, date to task.date.toString(),
        expected to task.completed, revision to task.stateRevision, target to !task.completed)
    fun command(params: ActionParameters): WidgetCommand? = try {
        WidgetCommand(requireNotNull(params[taskId]), LocalDate.parse(requireNotNull(params[date])),
            requireNotNull(params[expected]), requireNotNull(params[revision]), requireNotNull(params[target]))
    } catch (_: Exception) { null }
}

class CompleteWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as DailyFlowApplication
        val command = WidgetActions.command(parameters)
        val result = if (command == null) WidgetActionResult.STALE else WidgetController(app.repository).execute(command)
        val text = when (result) {
            WidgetActionResult.EXPIRED -> "日期已变化，已刷新今日任务"
            WidgetActionResult.STALE -> "任务状态已更新，请在刷新后操作"
            WidgetActionResult.MISSING -> "任务已删除或移动，已刷新"
            WidgetActionResult.FAILED -> "保存失败，请刷新后重试"
            WidgetActionResult.APPLIED -> null
        }
        if (text != null) withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
        // Refresh every placed instance, including stale clicks which did not write anything.
        refreshAfterAction(context)
    }
}
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetUpdates.refreshAll(context)
    }
}
object WidgetNavigation {
    const val TASK_ID = "dailyflow.widget.taskId"
    const val DATE = "dailyflow.widget.date"
    fun homeIntent(context: Context) = Intent(context, MainActivity::class.java)
        .setData(Uri.parse("dailyflow://widget/today"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun detailIntent(context: Context, id: String, date: LocalDate) = Intent(context, MainActivity::class.java)
        .setData(Uri.Builder().scheme("dailyflow").authority("widget").appendPath("task").appendPath(id).appendPath(date.toString()).build())
        .putExtra(TASK_ID, id).putExtra(DATE, date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun destination(intent: Intent): Pair<String, LocalDate>? = try {
        val id = requireNotNull(intent.getStringExtra(TASK_ID)); require(id.isNotBlank() && id.length <= 100)
        id to LocalDate.parse(requireNotNull(intent.getStringExtra(DATE)))
    } catch (_: Exception) { null }
}

private suspend fun refreshAfterAction(context: Context) {
    try { WidgetUpdates.refreshAll(context) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) {
        android.util.Log.w("DailyFlowWidget", "Refresh deferred", e)
        try { WidgetUpdates.request(context) } catch (_: Exception) { }
        withContext(Dispatchers.Main) { Toast.makeText(context, "刷新暂时失败，请点刷新重试", Toast.LENGTH_SHORT).show() }
    }
}
