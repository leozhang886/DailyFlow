package com.dailyflow.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyflow.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class,ExperimentalFoundationApi::class)
@Composable
fun PlanningScreen(planner:PlanningViewModel,openTask:(TaskOccurrence)->Unit){
    val state by planner.state.collectAsStateWithLifecycle()
    val series by planner.series.collectAsStateWithLifecycle()
    val exceptions by planner.exceptions.collectAsStateWithLifecycle()
    val history by planner.history.collectAsStateWithLifecycle()
    val month by planner.historyMonth.collectAsStateWithLifecycle()
    var effectivePicker by rememberSaveable{mutableStateOf(false)};var untilPicker by rememberSaveable{mutableStateOf(false)};var historyPicker by rememberSaveable{mutableStateOf(false)}
    val managerScroll=rememberLazyListState();val historyScroll=rememberLazyListState();val exceptionScroll=rememberLazyListState()
    val ruleScroll=rememberScrollState();val intervalFocus=remember{FocusRequester()};val intervalBring=remember{BringIntoViewRequester()}
    LaunchedEffect(state.error){if(state.error!=null&&state.page==PlanningPage.RULE){val n=state.form?.settings?.intervalText?.toIntOrNull();if(n==null||n !in 1..365){intervalFocus.requestFocus();intervalBring.bringIntoView()}else ruleScroll.scrollTo(0)}}
    BackHandler{planner.back()}
    Scaffold(modifier=Modifier.fillMaxSize().imePadding(),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(when(state.page){PlanningPage.RULE->"编辑重复规则";PlanningPage.EXCEPTIONS->"单日例外";PlanningPage.HISTORY->"历史记录";else->"重复管理"},style=MaterialTheme.typography.titleLarge)},
            navigationIcon={TextButton(onClick=planner::back,enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp)){Text("返回")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))},
        bottomBar={if(state.page==PlanningPage.RULE)ActionDock(primary="检查并保存规则",primaryTag="previewRule",enabled=!state.busy&&state.form?.dirty==true,onPrimary=planner::confirmRule,onSecondary=planner::back,
            secondary="取消编辑",secondaryTag="cancelRuleEdit",secondaryEnabled=!state.busy)}){padding->
        PageFrame(Modifier.padding(padding).consumeWindowInsets(padding)){
            when(state.page){
                PlanningPage.MANAGE->LazyColumn(state=managerScroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    item{SectionTitle("让日常安排自动出现","每天或每周重复，一次调整，清晰掌握生效范围。")}
                    state.message?.let{item{NoticeCard(it)}};state.error?.let{item{NoticeCard(it,error=true)}}
                    item{LoadingState(series,planner::retry)}
                    if(!series.loading&&!series.error&&series.items.isEmpty())item{NoticeCard("暂无重复任务，可在新建任务时选择每天或每周。")}
                    items(series.items,key={it.taskId}){s->
                        var expanded by rememberSaveable(s.taskId){mutableStateOf(false)}
                        val active=s.changes.lastOrNull{it.effectiveFrom<=LocalDate.now()}?.rule?:s.rule
                        Card(Modifier.fillMaxWidth(),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Text(s.title,style=MaterialTheme.typography.titleMedium)
                                Text("当前："+ruleLabel(active),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                s.changes.firstOrNull{it.effectiveFrom>LocalDate.now()}?.let{StatusPill(it.effectiveFrom.toString()+" 起有新安排",true)}
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    FilledTonalButton(onClick={planner.openRule(s)},modifier=Modifier.heightIn(min=48.dp).testTag("rule_"+s.taskId)){Text("编辑规则")}
                                    OutlinedButton(onClick={planner.openExceptions(s)},modifier=Modifier.heightIn(min=48.dp).testTag("exceptions_"+s.taskId)){Text("单日例外")}
                                }
                                if(s.changes.isNotEmpty())TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)){Text(if(expanded)"收起调整记录" else "查看 "+s.changes.size+" 次规则调整")}
                                if(expanded){Text("最初："+ruleLabel(s.rule),style=MaterialTheme.typography.bodySmall);s.changes.forEach{Text(it.effectiveFrom.toString()+" 起："+ruleLabel(it.rule),style=MaterialTheme.typography.bodySmall)}}
                            }
                        }
                    }
                }
                PlanningPage.RULE->state.form?.let{form->
                    val settings=form.settings
                    Column(Modifier.fillMaxSize().verticalScroll(ruleScroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                        Text(form.series.title,style=MaterialTheme.typography.titleLarge)
                        state.error?.let{NoticeCard(it,error=true)};state.message?.let{NoticeCard(it)}
                        NoticeCard("只调整生效日及之后的安排。过去日期、已有完成记录和单日例外会保留。")
                        SectionTitle("从哪天开始")
                        OutlinedButton(onClick={effectivePicker=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("effectiveDate")){Icon(FlowIcons.Calendar,null);Spacer(Modifier.width(8.dp));Text("从 "+settings.effectiveFrom+" 起生效")}
                        SectionTitle("重复频率")
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)){Frequency.entries.forEach{f->FilterChip(selected=settings.frequency==f,onClick={planner.frequency(f)},enabled=!state.busy,label={Text(if(f==Frequency.DAILY)"每天" else "每周")},modifier=Modifier.heightIn(min=48.dp))}}
                        OutlinedTextField(value=settings.intervalText,onValueChange=planner::interval,label={Text(if(settings.frequency==Frequency.DAILY)"间隔天数（1—365）" else "间隔周数（1—365）")},singleLine=true,enabled=!state.busy,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().focusRequester(intervalFocus).bringIntoViewRequester(intervalBring).testTag("ruleInterval"),
                            supportingText={Text("例如 2 表示每隔 "+if(settings.frequency==Frequency.DAILY)"2 天" else "2 周")})
                        if(settings.frequency==Frequency.WEEKLY){SectionTitle("选择周几","至少选择一天")
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){DayOfWeek.entries.forEach{d->FilterChip(selected=d in settings.weekdays,onClick={planner.weekday(d)},enabled=!state.busy,label={Text(weekdayLabel(d))},modifier=Modifier.heightIn(min=48.dp))}}
                        }
                        SectionTitle("何时结束","不设置时会持续重复")
                        OutlinedButton(onClick={untilPicker=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("ruleUntil")){Text("结束日期："+(settings.untilDate?.toString()?:"不设结束"))}
                        if(settings.untilDate!=null)TextButton(onClick={planner.until(null)},enabled=!state.busy,modifier=Modifier.heightIn(min=48.dp)){Text("清除结束日期")}
                        Text("新的间隔以生效日所在日/周为起点，保存前会再次确认。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlanningPage.EXCEPTIONS->LazyColumn(state=exceptionScroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    item{SectionTitle(state.exceptionSeries?.title.orEmpty(),"单独改过或删除过的日期会出现在这里。")}
                    state.message?.let{item{NoticeCard(it)}};state.error?.let{item{NoticeCard(it,error=true)}};item{LoadingState(exceptions,planner::retry)}
                    if(!exceptions.loading&&!exceptions.error&&exceptions.items.isEmpty())item{NoticeCard("没有单日修改或删除记录。")}
                    items(exceptions.items,key={it.date}){e->Card(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                            Text(e.date.toString(),style=MaterialTheme.typography.titleMedium);StatusPill(if(e.deleted)"已单次删除" else "单日修改",error=e.deleted)
                            e.title?.let{Text(it)}
                            OutlinedButton(onClick={planner.requestRecovery(e)},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("recover_"+e.date)){Text(if(e.deleted)"恢复这一天的任务" else "清除这一天的修改")}
                        }
                    }}
                }
                PlanningPage.HISTORY->LazyColumn(state=historyScroll,modifier=Modifier.fillMaxSize().testTag("historyList"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        TextButton(onClick={historyPicker=true},modifier=Modifier.heightIn(min=48.dp).testTag("historyMonthPicker")){Icon(FlowIcons.Calendar,null);Spacer(Modifier.width(8.dp));Text(month.format(DateTimeFormatter.ofPattern("yyyy年M月")),style=MaterialTheme.typography.titleLarge)}
                        Row(Modifier.fillMaxWidth()){TextButton(onClick={planner.moveMonth(-1)},enabled=month.year>1||month.monthValue>1,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("上个月")};TextButton(onClick={planner.moveMonth(1)},enabled=month<YearMonth.now(),modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("下个月")}}
                    }}}
                    state.error?.let{item{NoticeCard(it,error=true)}};item{LoadingState(history,planner::retry)}
                    if(!history.loading&&!history.error&&history.items.isEmpty())item{NoticeCard("这个月暂无历史任务。")}
                    history.items.groupBy{it.date}.toSortedMap(compareByDescending{it}).forEach{(date,tasks)->
                        item("date_"+date){SectionTitle(date.toString(),"已完成 "+tasks.count{it.completed}+" / "+tasks.size)}
                        items(tasks,key={it.taskId+":"+it.date}){task->Card(onClick={openTask(task)},modifier=Modifier.fillMaxWidth().testTag("history_"+task.taskId+"_"+task.date)){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Text(task.title,style=MaterialTheme.typography.titleMedium);FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                    StatusPill(if(task.completed)"已完成" else "未完成",emphasis=task.completed);StatusPill(timeLabel(task.scheduledMinute));if(task.recurring)StatusPill("重复")
                                }
                            }
                        }}
                    }
                }
                null->Unit
            }
        }
    }
    state.form?.let{form->
        if(effectivePicker)CalendarDialog(form.settings.effectiveFrom,{effectivePicker=false},minDate=maxOf(LocalDate.now(),form.series.startDate)){planner.effectiveDate(it);effectivePicker=false}
        if(untilPicker)CalendarDialog(form.settings.untilDate?:form.settings.effectiveFrom,{untilPicker=false},minDate=form.settings.effectiveFrom){planner.until(it);untilPicker=false}
        if(state.confirmRule)AlertDialog(onDismissRequest=planner::dismissConfirm,title={Text("确认更改未来规则？")},text={ScrollableDialogText{Text(form.series.title,style=MaterialTheme.typography.titleMedium);Text(form.settings.effectiveFrom.toString()+" 起："+ruleLabel(form.settings.validatedRule()));Text("替换该日及以后的规则版本；生效日前不改。已有完成和例外保留。")}},
            confirmButton={TextButton(onClick=planner::saveRule,enabled=!state.busy,modifier=Modifier.testTag("saveRule")){Text(if(state.busy)"保存中…" else "确认保存")}},dismissButton={TextButton(onClick=planner::dismissConfirm,enabled=!state.busy){Text("取消")}})
    }
    if(historyPicker)CalendarDialog(month.atDay(1),{historyPicker=false},maxDate=LocalDate.now().minusDays(1)){planner.selectHistoryMonth(YearMonth.from(it));historyPicker=false}
    state.confirmRecovery?.let{e->AlertDialog(onDismissRequest=planner::dismissConfirm,title={Text(if(e.deleted)"恢复这一天？" else "清除单日修改？")},text={ScrollableDialogText{Text(e.date.toString());Text(if(e.deleted)"恢复任务为未完成；原单次删除已移除的完成记录无法恢复。" else "恢复当前该日规则内容。若规则不含这天且没有完成记录，清单中将不再显示；已有完成状态保留。")}},confirmButton={TextButton(onClick=planner::recover,enabled=!state.busy,modifier=Modifier.testTag("confirmRecovery")){Text("确认恢复")}},dismissButton={TextButton(onClick=planner::dismissConfirm,enabled=!state.busy){Text("取消")}})}
    if(state.discard)AlertDialog(onDismissRequest=planner::keepDraft,title={Text("放弃未保存的规则？")},text={Text("这些修改尚未保存。")},confirmButton={TextButton(onClick=planner::discard,modifier=Modifier.testTag("discardRule")){Text("放弃修改")}},dismissButton={TextButton(onClick=planner::keepDraft){Text("继续编辑")}})
}
@Composable private fun <T> LoadingState(value:PlanningList<T>,retry:()->Unit){if(value.loading)LinearProgressIndicator(Modifier.fillMaxWidth());if(value.error)NoticeCard("无法读取，请重试",error=true,action={TextButton(onClick=retry){Text("重试")}})}
