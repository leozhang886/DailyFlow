package com.dailyflow.app.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.saveable.rememberSaveable
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.domain.*
import com.dailyflow.app.reminders.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch

private data class ReminderUiEnvironment(val now: Instant = Instant.now(), val zone: ZoneId = ZoneId.systemDefault(),
    val notifications: Boolean = true, val exact: Boolean = true, val status: String = "", val recheck: () -> Unit = {})
private val LocalReminderEnvironment = staticCompositionLocalOf { ReminderUiEnvironment() }

@Composable
fun DailyFlowApp(model: DailyViewModel, planner: PlanningViewModel) {
    val context=LocalContext.current
    val app=context.applicationContext as? DailyFlowApplication
    val owner=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope()
    var now by remember { mutableStateOf(Instant.now()) }
    var notifications by remember { mutableStateOf(app?.reminders?.notifier?.allowed() ?: true) }
    var exact by remember { mutableStateOf(ReminderPermissions.canExact(context)) }
    val signal = remember(app) { app?.database?.reminderDao()?.observeAlarm()?.catch { emit(null) } ?: flowOf(null) }
    val alarm by signal.collectAsState(initial=null)
    val recheck: () -> Unit = {
        notifications=app?.reminders?.notifier?.allowed() ?: true
        exact=ReminderPermissions.canExact(context);now=Instant.now()
        scope.launch { runCatching { app?.reminders?.refresh() } }
    }
    DisposableEffect(owner,app) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME)recheck() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(alarm?.token) { now=Instant.now();notifications=app?.reminders?.notifier?.allowed() ?: true;exact=ReminderPermissions.canExact(context) }
    LaunchedEffect(Unit) { while(isActive) { delay(60_000);now=Instant.now() } }
    val message=alarm?.detail.orEmpty()
    CompositionLocalProvider(LocalReminderEnvironment provides ReminderUiEnvironment(now,ZoneId.systemDefault(),notifications,exact,message,recheck)) {
        DailyFlowContent(model,planner)
    }
}

@Composable
internal fun ReminderPermissionPanel() {
    val env=LocalReminderEnvironment.current
    val context=LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { env.recheck() }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(!env.notifications) {
            NoticeCard("系统通知或任务提醒渠道已关闭。提醒设置会保留，授权后恢复未来提醒。",error=true)
            TextButton(onClick={
                if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else runCatching { context.startActivity(ReminderPermissions.notificationSettings(context)) }.onFailure { failure="无法打开通知设置" }
            },modifier=Modifier.heightIn(min=48.dp).testTag("requestReminderNotification")){Text("允许通知")}
            TextButton(onClick={runCatching{context.startActivity(ReminderPermissions.notificationSettings(context))}.onFailure{failure="无法打开通知设置"}},modifier=Modifier.heightIn(min=48.dp)){Text("系统通知设置")}
        }
        if(!env.exact) {
            Text("未获精确闹钟授权，当前使用非精确提醒，可能延迟。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={runCatching{context.startActivity(ReminderPermissions.exactSettings(context))}.onFailure{failure="无法打开闹钟授权设置"}},modifier=Modifier.heightIn(min=48.dp).testTag("requestExactReminder")){Text("设置精确提醒权限")}
        }
        if(env.status.isNotBlank() && env.notifications && env.exact)Text(env.status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        failure?.let { NoticeCard(it,error=true) }
    }
}

@Composable
internal fun ReminderEditorControls(editor:EditorState, model:DailyViewModel, enabled:Boolean) {
    val context=LocalContext.current;val env=LocalReminderEnvironment.current
    val draft=editor.draft;val setting=draft.reminder?:ReminderSetting()
    val series=draft.rule!=null&&(editor.taskId==null||editor.scope==EditScope.SERIES)
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var timeOpen by rememberSaveable { mutableStateOf(false) }
    var explain by rememberSaveable { mutableStateOf(false) }
    val ask=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { env.recheck() }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SectionTitle("本地提醒","独立于任务安排时间，默认关闭。")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("开启提醒",Modifier.weight(1f))
            Switch(checked=setting.enabled,enabled=enabled,onCheckedChange={
                model.setReminderEnabled(it)
                if(it && !env.notifications)explain=true
            },modifier=Modifier.testTag("reminderEnabled"))
        }
        if(setting.enabled) {
            if(series)Text("作用于整个系列：每个有效实例当天提醒。",style=MaterialTheme.typography.bodySmall)
            else OutlinedButton(onClick={dateOpen=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("reminderDate")) {Text("提醒日期："+draft.date.plusDays(setting.offsetDays.toLong()))}
            OutlinedButton(onClick={timeOpen=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("reminderTime")){Text("提醒时间："+timeLabel(setting.minute))}
            if(!series && reminderTime(draft.date,setting,env.zone)<=env.now)NoticeCard("提醒时间已过去。修改提醒时请选择未来时间；仅编辑标题备注会保留原配置。",error=true)
            ReminderPermissionPanel()
        }
        if(editor.taskId!=null && series)TextButton(onClick={model.setReminderEnabled(false)},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("disableSeriesReminders")) {Text("关闭本系列全部提醒（含单日覆盖）")}
        if(!series && draft.rule!=null)Text("只改变这一天的提醒，其他日期仍使用系列设置。",style=MaterialTheme.typography.bodySmall)
    }
    if(dateOpen)CalendarDialog(draft.date.plusDays(setting.offsetDays.toLong()),{dateOpen=false}){model.setReminderDate(it);dateOpen=false}
    if(timeOpen)ClockDialog(setting.minute,{timeOpen=false}){if(it==null)model.setReminderEnabled(false)else model.setReminderMinute(it);timeOpen=false}
    if(explain)AlertDialog(onDismissRequest={explain=false},title={Text("允许本地任务提醒")},text={Text("DailyFlow通过系统通知显示你主动设置的任务提醒，不连接服务器。拒绝后仍保留设置，但无法显示通知。精确闹钟是另一项独立授权，不会在这里同时请求。")},
        confirmButton={TextButton(onClick={explain=false;if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ask.launch(Manifest.permission.POST_NOTIFICATIONS)else runCatching { context.startActivity(ReminderPermissions.notificationSettings(context)) }}){Text("继续")}},
        dismissButton={TextButton(onClick={explain=false}){Text("稍后")}})
}

fun reminderSummary(task:TaskOccurrence,now:Instant,zone:ZoneId):String {
    val setting=task.reminder?:return "未设置提醒"
    if(task.completed)return "提醒已结束（任务已完成）"
    if(!setting.enabled)return "提醒已关闭"
    val time=reminderTime(task.date,setting,zone)
    when(task.reminderDeliveryStatus) {
        "DELIVERED"->return "已提醒"
        "CLAIMED"->return "提醒发送状态待核实"
        "FAILED"->return if(now.toEpochMilli()-time.toEpochMilli()>ReminderEngine.LATE_WINDOW) "提醒已过期（发送失败，重试已结束）" else "提醒发送失败，等待重试"
        "CANCELED"->return "本实例已提醒并结束"
    }
    if(time<=now)return "提醒已过期"
    return "提醒："+time.atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
@Composable
internal fun ReminderBadge(task:TaskOccurrence) {
    val env=LocalReminderEnvironment.current
    if(task.reminder!=null)StatusPill(reminderSummary(task,env.now,env.zone))
}
@Composable
internal fun ReminderDetail(task:TaskOccurrence) {
    val env=LocalReminderEnvironment.current
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SectionTitle("任务提醒")
        Text(reminderSummary(task,env.now,env.zone),modifier=Modifier.testTag("reminderDetailStatus"))
        if(task.reminder==null)Text("在编辑任务中添加提醒。",style=MaterialTheme.typography.bodySmall)
        if(task.reminder?.enabled==true && !task.completed)ReminderPermissionPanel()
    } }
}
