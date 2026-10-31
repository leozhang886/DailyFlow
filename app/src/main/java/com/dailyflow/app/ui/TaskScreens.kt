package com.dailyflow.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dailyflow.app.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyFlowApp(model: DailyViewModel, planner: PlanningViewModel) {
    val pageStates=rememberSaveableStateHolder()
    val planning by planner.state.collectAsStateWithLifecycle()
    if(planning.page!=null) { pageStates.SaveableStateProvider("planning") { PlanningScreen(planner) { planner.openHistoryDetails();model.selectDate(it.date);model.openDetails(it) } };return }
    val day by model.state.collectAsStateWithLifecycle()
    val ui by model.interaction.collectAsStateWithLifecycle()
    val pending by model.completionPending.collectAsStateWithLifecycle()
    var calendar by rememberSaveable { mutableStateOf(false) }
    val snack=remember{SnackbarHostState()}
    val live=ui.detail?.let { initial -> if(day.date==initial.date&&!day.loading&&!day.readFailed) day.tasks.firstOrNull {it.taskId==initial.taskId} else initial }
    val back:()->Unit={if(ui.deletePrompt!=null||ui.editPrompt!=null)model.requestBack() else if(ui.page==TaskPage.DETAILS&&planner.returnsToHistory){model.requestBack();planner.resumeHistory()}else model.requestBack()}
    LaunchedEffect(ui.message){ui.message?.let{snack.showSnackbar(it);model.clearMessage()}}
    LaunchedEffect(ui.page){if(ui.page==TaskPage.LIST&&planner.returnsToHistory)planner.resumeHistory()}
    BackHandler(enabled=ui.page!=TaskPage.LIST||ui.editPrompt!=null||ui.deletePrompt!=null){back()}
    Scaffold(modifier=Modifier.fillMaxSize().imePadding(),containerColor=MaterialTheme.colorScheme.background,
        topBar={TopAppBar(title={Text(when(ui.page){TaskPage.LIST->"DailyFlow";TaskPage.DETAILS->"任务详情";TaskPage.EDITOR->if(ui.editor?.taskId==null)"新建任务" else "编辑任务"},style=MaterialTheme.typography.titleLarge)},
            navigationIcon={if(ui.page!=TaskPage.LIST)TextButton(onClick=back,enabled=!ui.busy,modifier=Modifier.heightIn(min=48.dp)){Text("返回")}},
            actions={if(ui.page==TaskPage.LIST){IconButton(onClick=planner::openManage,modifier=Modifier.testTag("openPlanning")){Icon(FlowIcons.Repeat,"重复管理")};IconButton(onClick=planner::openHistory,modifier=Modifier.testTag("openHistory")){Icon(FlowIcons.History,"历史记录")}}},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))},
        snackbarHost={SnackbarHost(snack)},
        floatingActionButton={if(ui.page==TaskPage.LIST)ExtendedFloatingActionButton(onClick=model::newTask,text={Text("新建任务")},icon={Icon(FlowIcons.Add,null)},modifier=Modifier.testTag("newTask"))},
        bottomBar={when(ui.page){
            TaskPage.EDITOR->ActionDock(primary=if(ui.busy)"保存中…" else "保存任务",primaryTag="saveTask",enabled=!ui.busy,onPrimary=model::saveEditor,onSecondary=back)
            TaskPage.DETAILS->live?.let{task->ActionDock(primary=if(task.completed)"撤销完成" else "标记完成",primaryTag="detailCompletion",
                secondary="编辑任务",secondaryTag="editTask",enabled=!ui.busy&&!day.loading&&day.date==task.date&&task.taskId+":"+task.date !in pending&&!day.readFailed,
                onPrimary={model.setCompleted(task,!task.completed)},onSecondary={model.requestEdit(task)})}
            else->Unit
        }}){padding->
        PageFrame(Modifier.padding(padding).consumeWindowInsets(padding)){
            when(ui.page){
                TaskPage.LIST->pageStates.SaveableStateProvider("daily"){DayList(day,pending,model,{calendar=true})}
                TaskPage.DETAILS->if(live!=null)key(live.taskId,live.date){TaskDetails(live,ui,model,!ui.busy&&!day.loading&&day.date==live.date&&live.taskId+":"+live.date !in pending&&!day.readFailed,day.error.takeIf{day.readFailed})}else Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    NoticeCard("该任务已删除或移动，请返回清单查看。")
                    OutlinedButton(onClick=back){Text("返回清单")}
                }
                TaskPage.EDITOR->ui.editor?.let{TaskEditor(it,ui,model)}
            }
        }
    }
    if(calendar)CalendarDialog(day.date,{calendar=false}){model.selectDate(it);calendar=false}
    ActionDialogs(ui,model)
}

@Composable
internal fun ActionDock(primary:String,primaryTag:String,enabled:Boolean,onPrimary:()->Unit,onSecondary:()->Unit,
    secondary:String="取消",secondaryTag:String="cancelEditor",secondaryEnabled:Boolean=enabled){
    val large=LocalDensity.current.fontScale>=1.5f
    Surface(shadowElevation=3.dp,color=MaterialTheme.colorScheme.surface){
        Box(Modifier.fillMaxWidth().navigationBarsPadding(),contentAlignment=Alignment.Center){
            BoxWithConstraints(Modifier.widthIn(max=FlowDesign.MaxWidth).fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)){
                val vertical=large||maxWidth<300.dp
                if(vertical)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick=onPrimary,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(primaryTag)){Text(primary)}
                    TextButton(onClick=onSecondary,enabled=secondaryEnabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(secondaryTag)){Text(secondary)}
                }else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    OutlinedButton(onClick=onSecondary,enabled=secondaryEnabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag(secondaryTag)){Text(secondary)}
                    Button(onClick=onPrimary,enabled=enabled,modifier=Modifier.weight(2f).heightIn(min=48.dp).testTag(primaryTag)){Text(primary)}
                }
            }
        }
    }
}

@Composable
private fun DayList(day:DayUiState,pending:Set<String>,model:DailyViewModel,calendar:()->Unit){
    val visible=day.visibleTasks
    val unfinished=visible.filterNot{it.completed};val completed=visible.filter{it.completed}
    var reordering by rememberSaveable { mutableStateOf(false) }
    val canReorder=reordering&&day.options.sort==TaskSort.MANUAL&&!day.options.hasCriteria
    LazyColumn(Modifier.fillMaxSize().testTag("dailyList"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=104.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item("date"){
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface){Column(Modifier.fillMaxWidth().padding(12.dp)){
                TextButton(onClick=calendar,modifier=Modifier.fillMaxWidth().testTag("listDatePicker")){
                    Icon(FlowIcons.Calendar,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                        Text(day.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日",Locale.SIMPLIFIED_CHINESE)),style=MaterialTheme.typography.titleLarge)
                        Text(day.date.format(DateTimeFormatter.ofPattern("EEEE",Locale.SIMPLIFIED_CHINESE))+" · 点击选择日期",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth()){
                    TextButton(onClick=model::previousDay,enabled=day.date>LocalDate.of(1,1,1),modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("前一天")}
                    FilledTonalButton(onClick=model::today,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("今天")}
                    TextButton(onClick=model::nextDay,enabled=day.date<LocalDate.of(9999,12,31),modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("后一天")}
                }
            }}
        }
        item("organize"){TaskOrganizationControls(day,model,reordering){reordering=it}}
        if(canReorder)item("globalOrderNotice"){NoticeCard("全局顺序：在同一完成分组内上移或下移；其他日期的同一任务也会沿用。",action={TextButton(onClick={reordering=false}){Text("结束调整")}})}
        day.error?.let{error->item("error"){NoticeCard(error,error=true,action={
            if(day.readFailed)TextButton(onClick=model::retryDay){Text("重试加载")}else TextButton(onClick=model::dismissActionError){Text("关闭提示")}
        })}}
        when{
            day.loading->item("loading"){Column(Modifier.padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在读取任务…",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            day.readFailed->item("failed"){Text("暂时无法加载任务，请重试加载。",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            visible.isEmpty()&&day.options.hasCriteria->item("noMatches"){
                Column(Modifier.fillMaxWidth().padding(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("没有匹配的任务",style=MaterialTheme.typography.titleMedium)
                    Text("只搜索当前日期的可见任务；可更换日期或清除条件。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick=model::clearListCriteria,modifier=Modifier.heightIn(min=48.dp).testTag("clearListCriteria")){Text("清除搜索和筛选")}
                }
            }
            day.tasks.isEmpty()->item("empty"){
                Column(Modifier.fillMaxWidth().padding(vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Icon(FlowIcons.Calendar,null,Modifier.size(44.dp),tint=MaterialTheme.colorScheme.primary)
                    Text("这一天暂无任务",style=MaterialTheme.typography.titleMedium)
                    Text("从一件小事开始，安排属于你的节奏。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick=model::newTask,modifier=Modifier.heightIn(min=48.dp).testTag("emptyCreate")){Text("添加第一项任务")}
                }
            }
            else->{
                item("progress"){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(if(day.options.hasCriteria)"匹配 "+visible.size+" 项" else if(unfinished.isEmpty())"这一天的任务都完成了" else "还剩 "+unfinished.size+" 项",style=MaterialTheme.typography.titleMedium);Text(day.tasks.count{it.completed}.toString()+" / "+day.tasks.size,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
                    LinearProgressIndicator(progress={day.tasks.count{it.completed}.toFloat()/day.tasks.size},modifier=Modifier.fillMaxWidth())
                }}
                if(unfinished.isNotEmpty())item("pendingHeader"){SectionTitle("待完成 · "+unfinished.size)}
                items(unfinished,key={it.taskId+":"+it.date}){task->OrganizedTaskCard(task,unfinished,canReorder,day.ordering,pending,model)}
                if(completed.isNotEmpty())item("doneHeader"){SectionTitle("已完成 · "+completed.size,"点击勾选框可撤销完成")}
                items(completed,key={it.taskId+":"+it.date}){task->OrganizedTaskCard(task,completed,canReorder,day.ordering,pending,model)}
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskCard(task:TaskOccurrence,pending:Boolean,onOpen:()->Unit,onCompleted:(Boolean)->Unit){
    Card(onClick=onOpen,modifier=Modifier.fillMaxWidth().testTag("task_"+task.taskId),
        colors=CardDefaults.cardColors(containerColor=if(task.completed)MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)else MaterialTheme.colorScheme.surface),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))){
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=16.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=task.completed,enabled=!pending,onCheckedChange=onCompleted,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp)
                .semantics{contentDescription=(if(task.completed)"撤销完成：" else "完成：")+task.title})
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(task.title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,maxLines=3,overflow=TextOverflow.Ellipsis,
                    color=if(task.completed)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration=if(task.completed)TextDecoration.LineThrough else TextDecoration.None)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    StatusPill(timeLabel(task.scheduledMinute))
                    StatusPill(priorityLabel(task.priority),error=task.priority==Priority.HIGH)
                    if(task.recurring)StatusPill("重复",emphasis=true)
                    if(task.overridden)StatusPill("仅此日")
                }
                if(task.note.isNotBlank())Text(task.note,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskDetails(task:TaskOccurrence,ui:TaskInteractionState,model:DailyViewModel,actionsEnabled:Boolean,readError:String?){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
        Text(task.title,style=MaterialTheme.typography.headlineMedium)
        Text(task.date.toString()+" · "+timeLabel(task.scheduledMinute),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        StatusPill(if(task.completed)"状态：已完成" else "状态：未完成",emphasis=task.completed)
        ui.error?.let{NoticeCard(it,error=true)}
        readError?.let{NoticeCard(it,error=true,action={TextButton(onClick=model::retryDay){Text("重试加载")}})}
        if(task.overridden)NoticeCard("已针对这一天单独修改，其他日期不受影响。")
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            SectionTitle("任务安排");Text("优先级："+priorityLabel(task.priority));Text("重复："+ruleLabel(task.recurrence))
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            SectionTitle("备注");Text(task.note.ifEmpty{"暂无备注"},style=MaterialTheme.typography.bodyLarge)
        }}
        OutlinedButton(onClick={model.requestDelete(task)},enabled=actionsEnabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("deleteTask"),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除任务")}
        if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class,ExperimentalFoundationApi::class)
@Composable
private fun TaskEditor(editor:EditorState,ui:TaskInteractionState,model:DailyViewModel){
    var datePicker by rememberSaveable{mutableStateOf(false)};var timePicker by rememberSaveable{mutableStateOf(false)}
    val draft=editor.draft;val scroll=rememberScrollState();val focus=LocalFocusManager.current
    val titleFocus=remember{FocusRequester()};val noteFocus=remember{FocusRequester()}
    val titleIntoView=remember{BringIntoViewRequester()};val noteIntoView=remember{BringIntoViewRequester()}
    val invalidTitle=editor.validationError!=null&&(draft.title.trim().isEmpty()||draft.title.trim().length>200)
    val invalidNote=editor.validationError!=null&&draft.note.length>4000
    LaunchedEffect(editor.validationError,ui.error){if(editor.validationError!=null||ui.error!=null){when{invalidTitle->{titleFocus.requestFocus();titleIntoView.bringIntoView()};invalidNote->{noteFocus.requestFocus();noteIntoView.bringIntoView()};else->scroll.scrollTo(scroll.maxValue)}}}
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        if(editor.taskId!=null&&draft.rule!=null)NoticeCard(if(editor.scope==EditScope.SINGLE)"仅修改 "+editor.occurrenceDate+"，其他日期不受影响。" else "正在修改整个系列，包含过去与未来；已有单日覆盖仍优先。",error=editor.scope==EditScope.SERIES)
        SectionTitle("想完成什么？","先写下标题，其他安排可以稍后补充。")
        OutlinedTextField(value=draft.title,onValueChange=model::setTitle,label={Text("任务标题（必填）")},singleLine=true,isError=invalidTitle,enabled=!ui.busy,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Down)}),
            modifier=Modifier.fillMaxWidth().testTag("taskTitle").focusRequester(titleFocus).bringIntoViewRequester(titleIntoView),
            supportingText={Text(if(invalidTitle)"请输入 1—200 字标题" else draft.title.trim().length.toString()+" / 200")})
        OutlinedTextField(value=draft.note,onValueChange=model::setNote,label={Text("备注")},minLines=3,maxLines=6,isError=invalidNote,enabled=!ui.busy,
            modifier=Modifier.fillMaxWidth().testTag("taskNote").focusRequester(noteFocus).bringIntoViewRequester(noteIntoView),supportingText={Text(draft.note.length.toString()+" / 4000")})
        SectionTitle("何时完成")
        OutlinedButton(onClick={datePicker=true},enabled=!ui.busy&&!editor.dateLocked,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("editorDate")){Icon(FlowIcons.Calendar,null);Spacer(Modifier.width(8.dp));Text("日期："+draft.date)}
        if(editor.dateLocked)Text("此页修改内容；间隔、周几及结束日期请到重复管理修改。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={timePicker=true},enabled=!ui.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("editorTime")){Text("时间："+timeLabel(draft.scheduledMinute))}
        SectionTitle("优先级")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){selectablePriorities.forEach{p->FilterChip(selected=draft.priority.organizedPriority()==p,onClick={model.setPriority(p)},enabled=!ui.busy,label={Text(priorityLabel(p))},modifier=Modifier.heightIn(min=48.dp).testTag("priority_"+p.name))}}
        SectionTitle("重复安排")
        if(editor.taskId==null){
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(null,Frequency.DAILY,Frequency.WEEKLY).forEach{mode->FilterChip(selected=draft.rule?.frequency==mode,onClick={model.setNewRepeat(mode)},enabled=!ui.busy,
                label={Text(when(mode){null->"不重复";Frequency.DAILY->"每天";Frequency.WEEKLY->"每周"})},modifier=Modifier.heightIn(min=48.dp))}}
            if(draft.rule?.frequency==Frequency.WEEKLY)FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                DayOfWeek.entries.forEach{d->FilterChip(selected=d in draft.rule.weekdays,onClick={model.toggleWeekday(d)},enabled=!ui.busy,label={Text(weekdayLabel(d))},modifier=Modifier.heightIn(min=48.dp))}
            }
        }else Text(ruleLabel(draft.rule),color=MaterialTheme.colorScheme.onSurfaceVariant)
        editor.validationError?.let{NoticeCard(it,error=true,modifier=Modifier.testTag("validationError"))}
        ui.error?.let{NoticeCard(it,error=true)}
        Spacer(Modifier.height(12.dp))
    }
    if(datePicker)CalendarDialog(draft.date,{datePicker=false}){model.setEditorDate(it);datePicker=false}
    if(timePicker)ClockDialog(draft.scheduledMinute,{timePicker=false}){model.setTime(it);timePicker=false}
}
