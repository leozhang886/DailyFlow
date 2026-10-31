package com.dailyflow.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dailyflow.app.domain.*
import java.time.LocalDate
import java.time.DayOfWeek

fun priorityLabel(value: Priority) = when(value.organizedPriority()) { Priority.HIGH -> "高优先级"; Priority.LOW -> "低优先级"; else -> "普通" }
fun timeLabel(minute: Int?) = minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "全天"
fun weekdayLabel(day: DayOfWeek) = "周" + listOf("一","二","三","四","五","六","日")[day.value - 1]
fun ruleLabel(rule: RecurrenceRule?): String = when (rule?.frequency) {
    null -> "不重复"
    Frequency.DAILY -> if(rule.interval == 1) "每天" else "每 "+rule.interval+" 天"
    Frequency.WEEKLY -> (if(rule.interval == 1) "每周" else "每 "+rule.interval+" 周") + " · " + rule.weekdays.sortedBy { it.value }.joinToString("、") { weekdayLabel(it) }
} + (rule?.untilDate?.let { " · 至 "+it } ?: "")


fun selectableCalendarDate(date:LocalDate,minDate:LocalDate?,maxDate:LocalDate?)=date.year in 1..9999&&(minDate==null||date>=minDate)&&(maxDate==null||date<=maxDate)
@Composable fun ScrollableDialogText(content:@Composable ColumnScope.()->Unit){
    val max=(LocalConfiguration.current.screenHeightDp.dp*.45f).coerceIn(100.dp,420.dp)
    Column(Modifier.fillMaxWidth().heightIn(max=max).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDialog(date:LocalDate,close:()->Unit,minDate:LocalDate?=null,maxDate:LocalDate?=null,select:(LocalDate)->Unit){
    val config=LocalConfiguration.current;val compact=config.screenWidthDp<360||config.screenHeightDp<540||LocalDensity.current.fontScale>1.4f
    val initial=if(maxDate!=null&&date>maxDate)maxDate else if(minDate!=null&&date<minDate)minDate else date
    val allowed=remember(minDate,maxDate){object:SelectableDates{
        override fun isSelectableDate(utcTimeMillis:Long)=runCatching{selectableCalendarDate(pickerDate(utcTimeMillis),minDate,maxDate)}.getOrDefault(false)
        override fun isSelectableYear(year:Int)=year in 1..9999&&(minDate==null||year>=minDate.year)&&(maxDate==null||year<=maxDate.year)
    }}
    val state=rememberDatePickerState(initialSelectedDateMillis=pickerMillis(initial),yearRange=1..9999,initialDisplayMode=if(compact)DisplayMode.Input else DisplayMode.Picker,selectableDates=allowed)
    DatePickerDialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false),modifier=Modifier.widthIn(max=560.dp).padding(horizontal=12.dp),
        confirmButton={TextButton(onClick={state.selectedDateMillis?.let{select(pickerDate(it))}},enabled=state.selectedDateMillis?.let{allowed.isSelectableDate(it)}==true){Text("确定日期")}},
        dismissButton={TextButton(onClick=close){Text("取消")}}){
        DatePicker(state=state,showModeToggle=!compact,title={Text("选择日期",Modifier.padding(start=24.dp,top=16.dp))})
    }
}
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun ClockDialog(minute:Int?,close:()->Unit,select:(Int?)->Unit){
    val state=rememberTimePickerState(initialHour=minute?.div(60)?:9,initialMinute=minute?.rem(60)?:0,is24Hour=true)
    AlertDialog(onDismissRequest=close,title={Text("任务时间")},text={ScrollableDialogText{TimeInput(state);Text("仅用于任务展示，不会触发提醒。",style=MaterialTheme.typography.bodySmall)}},
        confirmButton={TextButton(onClick={select(state.hour*60+state.minute)}){Text("确定时间")}},
        dismissButton={FlowRow{TextButton(onClick={select(null)}){Text("设为全天")};TextButton(onClick=close){Text("取消")}}})
}
@Composable
fun ScopeChoices(scope:EditScope,busy:Boolean,select:(EditScope)->Unit){
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        EditScope.entries.forEach{value->val chosen=scope==value;val entire=value==EditScope.SERIES
            Surface(shape=MaterialTheme.shapes.medium,color=if(chosen){if(entire)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer}else MaterialTheme.colorScheme.surfaceVariant,
                border=BorderStroke(1.dp,if(chosen)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)){
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(chosen,enabled=!busy,role=Role.RadioButton,onClick={select(value)}).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                    RadioButton(selected=chosen,onClick=null,enabled=!busy)
                    Text(if(entire)"整个系列（包含历史）" else "仅这一天",Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(if(scope==EditScope.SINGLE)"只影响所选日期，其他日期保持独立。" else "整个系列操作包含过去与未来；已有的单日内容覆盖仍优先。",
            style=MaterialTheme.typography.bodySmall,color=if(scope==EditScope.SERIES)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun ActionDialogs(ui:TaskInteractionState,model:DailyViewModel){
    ui.editPrompt?.let{prompt->AlertDialog(onDismissRequest=model::dismissEditScope,title={Text("修改哪些任务？")},text={ScrollableDialogText{
        Text(prompt.task.title,style=MaterialTheme.typography.titleMedium);Text(prompt.task.date.toString());ScopeChoices(prompt.scope,ui.busy,model::selectEditScope)
    }},confirmButton={TextButton(onClick=model::confirmEditScope,enabled=!ui.busy,modifier=Modifier.testTag("confirmEditScope")){Text("继续编辑")}},dismissButton={TextButton(onClick=model::dismissEditScope,enabled=!ui.busy){Text("取消")}})}
    ui.deletePrompt?.let{prompt->AlertDialog(onDismissRequest=model::dismissDelete,title={Text("确认删除任务？")},text={ScrollableDialogText{
        Text(prompt.task.title,style=MaterialTheme.typography.titleMedium);Text(prompt.task.date.toString())
        if(prompt.task.recurring)ScopeChoices(prompt.scope,ui.busy,model::selectDeleteScope)
        Text(when{prompt.task.recurring&&prompt.scope==EditScope.SERIES->"整个系列及其历史完成记录将被删除，无法撤销。";prompt.task.recurring->"仅删除这一天，可在重复管理的单日例外中恢复任务；该日完成记录不会恢复。";else->"一次性任务及其完成记录将被删除，无法撤销。"},color=MaterialTheme.colorScheme.error)
        ui.error?.let{NoticeCard(it,error=true)}
    }},confirmButton={TextButton(onClick=model::confirmDelete,enabled=!ui.busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),modifier=Modifier.testTag("confirmDelete")){Text(if(ui.busy)"删除中…" else "确认删除")}},dismissButton={TextButton(onClick=model::dismissDelete,enabled=!ui.busy){Text("取消")}})}
    if(ui.discardPrompt)AlertDialog(onDismissRequest=model::stayInEditor,title={Text("放弃未保存的修改？")},text={Text("这些修改尚未保存。")},confirmButton={TextButton(onClick=model::discardEditor,modifier=Modifier.testTag("discardDraft")){Text("放弃修改")}},dismissButton={TextButton(onClick=model::stayInEditor){Text("继续编辑")}})
}
