package com.dailyflow.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dailyflow.app.domain.*
import com.dailyflow.app.reminders.ReminderDeviceClock
import com.dailyflow.app.reminders.reminderTime
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DayUiState(val date: LocalDate, val tasks: List<TaskOccurrence> = emptyList(), val error: String? = null,
    val loading: Boolean = false, val readFailed: Boolean = false, val options: TaskListOptions = TaskListOptions(), val ordering: Boolean = false) {
    val visibleTasks: List<TaskOccurrence> get() = organizeTasks(tasks, options)
}

@OptIn(ExperimentalCoroutinesApi::class)
class DailyViewModel(private val repository: TaskRepository, private val clock: Clock = ReminderDeviceClock(), private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val selected = MutableStateFlow(runCatching { LocalDate.parse(saved.get<String>("selectedDate")) }.getOrElse { LocalDate.now(clock) })
    private val actionError = MutableStateFlow<String?>(null)
    private val refresh = MutableStateFlow(0)
    private val options = MutableStateFlow(TaskListOptions(
        query = saved.get<String>("listQuery").orEmpty().take(4000),
        completion = runCatching { CompletionFilter.valueOf(saved.get<String>("listCompletion").orEmpty()) }.getOrDefault(CompletionFilter.ALL),
        priority = runCatching { Priority.valueOf(saved.get<String>("listPriority").orEmpty()).organizedPriority() }.getOrNull(),
        sort = runCatching { TaskSort.valueOf(saved.get<String>("listSort").orEmpty()) }.getOrDefault(TaskSort.MANUAL)))
    private val ordering = MutableStateFlow(false)
    private val restoredEditor = DraftStateCodec.decodeEditor(saved["editorDraft"])
    private val mutableInteraction = MutableStateFlow(restoredEditor?.let { TaskInteractionState(page=TaskPage.EDITOR,editor=it,message="已恢复未保存的任务草稿") } ?: TaskInteractionState())
    val interaction: StateFlow<TaskInteractionState> = mutableInteraction.asStateFlow()
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val completionPending: StateFlow<Set<String>> = pending.asStateFlow()
    private val dayData = combine(selected, refresh) { date, _ -> date }.flatMapLatest { date ->
        repository.observeDay(date).map { DayUiState(date, it) }
            .onStart { emit(DayUiState(date, loading = true)) }
            .catch { emit(DayUiState(date, error = "无法读取本地任务", readFailed = true)) }
    }
    val state: StateFlow<DayUiState> = combine(dayData, actionError, options, ordering) { data, error, selection, busy ->
        data.copy(error = error ?: data.error, options = selection, ordering = busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayUiState(selected.value))

    init { viewModelScope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { interaction.collect { saved["editorDraft"]=DraftStateCodec.encodeEditor(it.editor) } } }
    fun selectDate(date: LocalDate) { actionError.value = null; selected.value = date; saved["selectedDate"]=date.toString() }
    fun today() = selectDate(LocalDate.now(clock))
    fun previousDay() = selectDate(selected.value.minusDays(1))
    fun nextDay() = selectDate(selected.value.plusDays(1))
    fun dismissActionError() { actionError.value = null }
    fun retryDay() { actionError.value = null; refresh.value += 1 }
    private fun setOptions(value: TaskListOptions) {
        options.value = value
        saved["listQuery"] = value.query; saved["listCompletion"] = value.completion.name
        saved["listPriority"] = value.priority?.name; saved["listSort"] = value.sort.name
    }
    fun setSearchQuery(value: String) = setOptions(options.value.copy(query = value.take(4000)))
    fun setCompletionFilter(value: CompletionFilter) = setOptions(options.value.copy(completion = value))
    fun setPriorityFilter(value: Priority?) = setOptions(options.value.copy(priority = value?.organizedPriority()))
    fun setSort(value: TaskSort) = setOptions(options.value.copy(sort = value))
    fun clearListCriteria() = setOptions(TaskListOptions(sort = options.value.sort))
    fun moveTask(task: TaskOccurrence, neighbour: TaskOccurrence) {
        if (ordering.value || interaction.value.busy || options.value.sort != TaskSort.MANUAL || options.value.hasCriteria ||
            state.value.loading || state.value.readFailed || task.date != selected.value || neighbour.date != selected.value) return
        ordering.value = true
        viewModelScope.launch {
            try {
                val moved = repository.moveTask(task.taskId, neighbour.taskId, task.sortOrder, neighbour.sortOrder)
                if (moved) {
                    actionError.value = null
                    mutableInteraction.value = interaction.value.copy(message = "已保存全局顺序，其他日期的同一任务也会沿用")
                } else actionError.value = "任务已变化，请重新选择排序位置"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError.value = "顺序未保存或已变化，请刷新后重试" }
            finally { ordering.value = false }
        }
    }
    fun clearMessage() { mutableInteraction.value = interaction.value.copy(message = null) }
    fun openDetails(task: TaskOccurrence) {
        if (interaction.value.busy) return
        mutableInteraction.value = TaskInteractionState(page = TaskPage.DETAILS, detail = task)
    }
    fun openWidgetHome() {
        if (interaction.value.busy || interaction.value.editor?.dirty == true) {
            mutableInteraction.value = interaction.value.copy(error = "请先保存或取消当前编辑，再打开今日清单")
            return
        }
        mutableInteraction.value = TaskInteractionState(); today()
    }
    fun openReminderTask(taskId: String, date: LocalDate) {
        if (interaction.value.busy || interaction.value.editor?.dirty == true) {
            mutableInteraction.value = interaction.value.copy(error = "请先保存或取消当前编辑，再打开提醒")
            return
        }
        selectDate(date)
        viewModelScope.launch {
            try {
                val task = repository.observeDay(date).first().firstOrNull { it.taskId == taskId && !it.completed }
                mutableInteraction.value = if (task != null) TaskInteractionState(page = TaskPage.DETAILS, detail = task)
                    else TaskInteractionState(message = "该提醒对应任务已完成、删除或不再有效")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableInteraction.value = TaskInteractionState(error = "无法读取提醒对应任务，请重试") }
        }
    }
    fun openWidgetTask(taskId: String, date: LocalDate) {
        if (interaction.value.busy || interaction.value.editor?.dirty == true) {
            mutableInteraction.value = interaction.value.copy(error = "请先保存或取消当前编辑，再打开桌面任务")
            return
        }
        selectDate(date)
        viewModelScope.launch {
            try {
                val task = repository.observeDay(date).first().firstOrNull { it.taskId == taskId }
                mutableInteraction.value = if (task != null) TaskInteractionState(page = TaskPage.DETAILS, detail = task)
                    else TaskInteractionState(message = "该任务已删除或移动")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableInteraction.value = TaskInteractionState(error = "无法打开任务，请重试") }
        }
    }
    fun newTask() {
        if (interaction.value.busy) return
        mutableInteraction.value = TaskInteractionState(page = TaskPage.EDITOR, editor = EditorState(TaskDraft(date = selected.value)))
    }
    private fun changeDraft(change: (TaskDraft) -> TaskDraft) {
        val current = interaction.value
        if (current.busy) return
        val editor = current.editor ?: return
        mutableInteraction.value = current.copy(editor = editor.copy(draft = change(editor.draft), validationError = null), error = null)
    }
    fun setTitle(title: String) = changeDraft { it.copy(title = title) }
    fun setNote(note: String) = changeDraft { it.copy(note = note) }
    fun setTime(minute: Int?) = changeDraft { it.copy(scheduledMinute = minute) }
    fun setPriority(priority: Priority) = changeDraft { it.copy(priority = priority) }
    fun setReminderEnabled(enabled: Boolean) = changeDraft { draft ->
        val next = clock.instant().atZone(clock.zone).plusHours(1).withSecond(0).withNano(0)
        val series = draft.rule != null && (interaction.value.editor?.taskId == null || interaction.value.editor?.scope == EditScope.SERIES)
        val default = ReminderSetting(false, if (draft.date <= next.toLocalDate()) next.hour * 60 + next.minute else 480,
            if (series) 0 else java.time.temporal.ChronoUnit.DAYS.between(draft.date, next.toLocalDate()).coerceAtLeast(0).toInt())
        draft.copy(reminder = (draft.reminder ?: default).copy(enabled = enabled), reminderEdited = true)
    }
    fun setReminderMinute(minute: Int) = changeDraft { it.copy(reminder = (it.reminder ?: ReminderSetting()).copy(enabled=true,minute=minute), reminderEdited=true) }
    fun setReminderDate(date: LocalDate) = changeDraft { it.copy(reminder = (it.reminder ?: ReminderSetting()).copy(enabled=true,offsetDays=java.time.temporal.ChronoUnit.DAYS.between(it.date,date).toInt()), reminderEdited=true) }
    fun setEditorDate(date: LocalDate) {
        if (interaction.value.editor?.dateLocked == true) return
        changeDraft { it.copy(date = date) }
    }
    fun setNewRepeat(frequency: Frequency?) {
        if (interaction.value.editor?.taskId != null) return
        changeDraft { draft -> draft.copy(rule = frequency?.let {
            RecurrenceRule(it, weekdays = if (it == Frequency.WEEKLY) setOf(draft.date.dayOfWeek) else emptySet())
        }, reminder = draft.reminder?.let { if (frequency != null) it.copy(offsetDays=0) else it }) }
    }
    fun toggleWeekday(day: java.time.DayOfWeek) {
        if (interaction.value.editor?.taskId != null) return
        val editor = interaction.value.editor ?: return
        val rule = editor.draft.rule ?: return
        if (rule.frequency != Frequency.WEEKLY) return
        val days = if (day in rule.weekdays) rule.weekdays - day else rule.weekdays + day
        if (days.isEmpty()) {
            mutableInteraction.value = interaction.value.copy(editor = editor.copy(validationError = "每周至少选择一天")); return
        }
        changeDraft { it.copy(rule = rule.copy(weekdays = days)) }
    }
    fun requestEdit(task: TaskOccurrence) {
        if (interaction.value.busy) return
        if (task.recurring) mutableInteraction.value = interaction.value.copy(editPrompt = ScopePrompt(task), error = null)
        else loadEditor(task, EditScope.SINGLE)
    }
    fun selectEditScope(scope: EditScope) {
        val prompt = interaction.value.editPrompt ?: return
        mutableInteraction.value = interaction.value.copy(editPrompt = prompt.copy(scope = scope))
    }
    fun confirmEditScope() {
        val prompt = interaction.value.editPrompt ?: return
        loadEditor(prompt.task, prompt.scope)
    }
    fun dismissEditScope() { if (!interaction.value.busy) mutableInteraction.value = interaction.value.copy(editPrompt = null) }
    private fun loadEditor(task: TaskOccurrence, scope: EditScope) {
        mutableInteraction.value = interaction.value.copy(busy = true, editPrompt = null, error = null)
        viewModelScope.launch {
            try {
                val draft = requireNotNull(repository.loadDraft(task.taskId, task.date, scope)) { "任务不存在" }
                mutableInteraction.value = interaction.value.copy(page = TaskPage.EDITOR, busy = false,
                    editor = EditorState(draft, taskId = task.taskId, occurrenceDate = task.date, scope = scope))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableInteraction.value = interaction.value.copy(busy = false, error = "无法打开任务，请返回清单后重试") }
        }
    }
    fun saveEditor() {
        val current = interaction.value
        val editor = current.editor ?: return
        if (current.busy) return
        try {
            editor.draft.validate(allowOutOfRuleDate=editor.taskId!=null && editor.scope==EditScope.SINGLE)
            val reminder = editor.draft.reminder
            val series = editor.draft.rule != null && (editor.taskId == null || editor.scope == EditScope.SERIES)
            if (reminder?.enabled == true && (editor.taskId == null || editor.draft.reminderEdited)) {
                if (series) require(reminder.offsetDays == 0) { "系列提醒在每个有效实例当天" }
                else require(reminderTime(editor.draft.date,reminder,clock.zone) > clock.instant()) { "提醒时间已过去，请选择未来时间" }
            }
        }
        catch (invalid: IllegalArgumentException) {
            mutableInteraction.value = current.copy(editor = editor.copy(validationError = invalid.message)); return
        }
        mutableInteraction.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                if (editor.taskId == null) repository.createTask(editor.draft)
                else repository.updateTask(editor.taskId, requireNotNull(editor.occurrenceDate), editor.draft, editor.scope)
                selectDate(if (editor.dateLocked) requireNotNull(editor.occurrenceDate) else editor.draft.date)
                mutableInteraction.value = TaskInteractionState(message = if (editor.taskId == null) "已创建任务" else "已保存修改")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableInteraction.value = interaction.value.copy(busy = false, error = "保存失败，内容已保留，请重试") }
        }
    }
    fun requestDelete(task: TaskOccurrence) {
        if (!interaction.value.busy) mutableInteraction.value = interaction.value.copy(deletePrompt = ScopePrompt(task), error = null)
    }
    fun selectDeleteScope(scope: EditScope) {
        val prompt = interaction.value.deletePrompt ?: return
        mutableInteraction.value = interaction.value.copy(deletePrompt = prompt.copy(scope = scope))
    }
    fun dismissDelete() { if (!interaction.value.busy) mutableInteraction.value = interaction.value.copy(deletePrompt = null) }
    fun confirmDelete() {
        val current = interaction.value
        val prompt = current.deletePrompt ?: return
        if (current.busy) return
        mutableInteraction.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                repository.delete(prompt.task.taskId, prompt.task.date, if (prompt.task.recurring) prompt.scope else EditScope.SINGLE)
                mutableInteraction.value = TaskInteractionState(message = "已删除任务")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableInteraction.value = interaction.value.copy(busy = false, error = "删除失败，请重试") }
        }
    }
    fun requestBack() {
        val current = interaction.value
        if (current.busy) return
        when {
            current.deletePrompt != null -> dismissDelete()
            current.editPrompt != null -> dismissEditScope()
            current.page == TaskPage.EDITOR && current.editor?.dirty == true -> mutableInteraction.value = current.copy(discardPrompt = true)
            current.page == TaskPage.EDITOR -> discardEditor()
            else -> mutableInteraction.value = TaskInteractionState()
        }
    }
    fun stayInEditor() { mutableInteraction.value = interaction.value.copy(discardPrompt = false) }
    fun discardEditor() {
        val current = interaction.value
        mutableInteraction.value = if (current.editor?.taskId != null && current.detail != null)
            TaskInteractionState(page = TaskPage.DETAILS, detail = current.detail) else TaskInteractionState()
    }
    fun setCompleted(task: TaskOccurrence, completed: Boolean) {
        val key = task.taskId + ":" + task.date
        if (key in pending.value || interaction.value.busy) return
        pending.value = pending.value + key
        viewModelScope.launch {
            try {
                repository.setCompleted(task.taskId, task.date, completed); actionError.value = null
                mutableInteraction.value = interaction.value.copy(message = if (completed) "已完成" else "已撤销完成", error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError.value = "保存失败，请重试"; mutableInteraction.value = interaction.value.copy(error = "保存失败，请重试") }
            finally { pending.value = pending.value - key }
        }
    }
    class Factory(private val repository: TaskRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>,extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(DailyViewModel::class.java))
            return DailyViewModel(repository,saved=runCatching { extras.createSavedStateHandle() }.getOrElse { SavedStateHandle() }) as T
        }
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DailyViewModel::class.java))
            return DailyViewModel(repository) as T
        }
    }
}
