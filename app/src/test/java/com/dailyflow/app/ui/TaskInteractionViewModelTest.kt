package com.dailyflow.app.ui

import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TaskInteractionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val date = LocalDate.of(2026, 10, 27)
    private val clock = Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private class FakeRepo(private val date: LocalDate) : TaskRepository {
        var creates = 0; var updates = mutableListOf<EditScope>(); var deletes = mutableListOf<EditScope>()
        var draft: TaskDraft? = null; var failWrite = false; var gate: CompletableDeferred<Unit>? = null
        var failRead = false
        var loadedScopes = mutableListOf<EditScope>()
        override fun observeDay(date: LocalDate): Flow<List<TaskOccurrence>> = flow { if(failRead) error("read failed"); emit(emptyList()) }
        override suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule?) = error("Unused")
        override suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope) = error("Unused")
        override suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean) = Unit
        override suspend fun createTask(draft: TaskDraft): String { creates++; gate?.await(); if(failWrite)error("disk"); this.draft = draft; return "new" }
        override suspend fun loadDraft(taskId: String, date: LocalDate, scope: EditScope): TaskDraft {
            loadedScopes += scope
            return TaskDraft(if(scope==EditScope.SINGLE)"当天" else "系列", date = date, rule = RecurrenceRule(Frequency.DAILY))
        }
        override suspend fun updateTask(taskId: String, occurrenceDate: LocalDate, draft: TaskDraft, scope: EditScope) { if(failWrite)error("disk"); updates += scope; this.draft = draft }
        override suspend fun delete(taskId: String, date: LocalDate, scope: EditScope) { if(failWrite)error("disk"); deletes += scope }
    }
    private fun recurring() = TaskOccurrence("id",date,"任务","",false,true,false)
    @Test fun newTaskUsesSelectedDateAndValidatesBeforeWriting() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.selectDate(date.plusDays(2)); vm.newTask(); vm.saveEditor(); runCurrent()
        assertEquals(0,repo.creates); assertNotNull(vm.interaction.value.editor!!.validationError)
        assertEquals(date.plusDays(2),vm.interaction.value.editor!!.draft.date)
        vm.setTitle("新任务"); vm.setTime(720); vm.setPriority(Priority.HIGH); vm.saveEditor(); runCurrent()
        assertEquals(1,repo.creates); assertEquals(720,repo.draft!!.scheduledMinute)
        assertEquals(Priority.HIGH,repo.draft!!.priority); assertEquals(TaskPage.LIST,vm.interaction.value.page)
    }
    @Test fun cancelDirtyDraftRequiresConfirmationAndNeverWrites() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.newTask(); vm.setTitle("未保存"); vm.requestBack()
        assertTrue(vm.interaction.value.discardPrompt)
        vm.stayInEditor(); assertEquals("未保存",vm.interaction.value.editor!!.draft.title)
        vm.requestBack(); vm.discardEditor(); runCurrent()
        assertEquals(0,repo.creates); assertEquals(TaskPage.LIST,vm.interaction.value.page)
    }
    @Test fun repeatedSaveWhileBusyCreatesExactlyOneTask() = runTest(dispatcher) {
        val repo=FakeRepo(date); repo.gate=CompletableDeferred()
        val vm=DailyViewModel(repo,clock); vm.newTask(); vm.setTitle("一次")
        vm.saveEditor(); vm.saveEditor(); runCurrent(); assertEquals(1,repo.creates)
        vm.requestBack(); assertTrue(vm.interaction.value.busy)
        repo.gate!!.complete(Unit); runCurrent(); assertEquals(TaskPage.LIST,vm.interaction.value.page)
    }
    @Test fun failedSaveRetainsDraftAndCanBeRetried() = runTest(dispatcher) {
        val repo=FakeRepo(date); repo.failWrite=true
        val vm=DailyViewModel(repo,clock); vm.newTask(); vm.setTitle("内容保留"); vm.saveEditor(); runCurrent()
        assertFalse(vm.interaction.value.busy); assertNotNull(vm.interaction.value.error)
        assertEquals("内容保留",vm.interaction.value.editor!!.draft.title)
        repo.failWrite=false; vm.saveEditor(); runCurrent(); assertEquals(TaskPage.LIST,vm.interaction.value.page)
    }
    @Test fun recurringEditWaitsForExplicitScopeAndDefaultsToSingle() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.openDetails(recurring()); vm.requestEdit(recurring()); runCurrent()
        assertTrue(repo.loadedScopes.isEmpty()); assertEquals(EditScope.SINGLE,vm.interaction.value.editPrompt!!.scope)
        vm.confirmEditScope(); runCurrent(); vm.setTitle("只改今天"); vm.saveEditor(); runCurrent()
        assertEquals(listOf(EditScope.SINGLE),repo.updates)
    }
    @Test fun seriesEditLoadsSeriesDefinitionAndRequiresSelection() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.requestEdit(recurring()); vm.selectEditScope(EditScope.SERIES); vm.confirmEditScope(); runCurrent()
        assertEquals("系列",vm.interaction.value.editor!!.draft.title)
        vm.setTitle("系列修改"); vm.saveEditor(); runCurrent()
        assertEquals(listOf(EditScope.SERIES),repo.updates)
    }
    @Test fun deleteCancelNeverWritesAndSingleIsTheDefault() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.requestDelete(recurring()); assertEquals(EditScope.SINGLE,vm.interaction.value.deletePrompt!!.scope)
        vm.dismissDelete(); runCurrent(); assertTrue(repo.deletes.isEmpty())
        vm.requestDelete(recurring()); vm.confirmDelete(); runCurrent()
        assertEquals(listOf(EditScope.SINGLE),repo.deletes)
    }
    @Test fun seriesDeleteAndFailureKeepExplicitScope() = runTest(dispatcher) {
        val repo=FakeRepo(date); repo.failWrite=true; val vm=DailyViewModel(repo,clock)
        vm.requestDelete(recurring()); vm.selectDeleteScope(EditScope.SERIES); vm.confirmDelete(); runCurrent()
        assertEquals(EditScope.SERIES,vm.interaction.value.deletePrompt!!.scope); assertFalse(vm.interaction.value.busy)
        repo.failWrite=false; vm.confirmDelete(); runCurrent(); assertEquals(listOf(EditScope.SERIES),repo.deletes)
    }
    @Test fun recurringEditorPreventsDateAndRuleChanges() = runTest(dispatcher) {
        val repo=FakeRepo(date); val vm=DailyViewModel(repo,clock)
        vm.requestEdit(recurring()); vm.confirmEditScope(); runCurrent()
        vm.setEditorDate(date.plusDays(1)); vm.setNewRepeat(null)
        assertEquals(date,vm.interaction.value.editor!!.draft.date)
        assertEquals(Frequency.DAILY,vm.interaction.value.editor!!.draft.rule!!.frequency)
    }
    @Test fun weeklyNewTaskCannotHaveNoWeekdays() = runTest(dispatcher) {
        val vm=DailyViewModel(FakeRepo(date),clock); vm.newTask(); vm.setNewRepeat(Frequency.WEEKLY)
        vm.toggleWeekday(date.dayOfWeek)
        assertNotNull(vm.interaction.value.editor!!.validationError)
        assertEquals(setOf(date.dayOfWeek),vm.interaction.value.editor!!.draft.rule!!.weekdays)
    }
    @Test fun pickerRoundTripDoesNotDependOnLocalTimezone() {
        for(zone in listOf("Pacific/Honolulu","Asia/Hong_Kong","Pacific/Kiritimati")) {
            val original=java.util.TimeZone.getDefault()
            try { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                val leap=LocalDate.of(2028,2,29); assertEquals(leap,pickerDate(pickerMillis(leap)))
            } finally { java.util.TimeZone.setDefault(original) }
        }
    }
    @Test fun readFailureCanBeRetriedWithoutRewritingData() = runTest(dispatcher) {
        val repo=FakeRepo(date); repo.failRead=true; val vm=DailyViewModel(repo,clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); assertTrue(vm.state.value.readFailed)
        repo.failRead=false; vm.retryDay(); runCurrent()
        assertNull(vm.state.value.error); assertFalse(vm.state.value.readFailed); assertEquals(0,repo.creates)
    }

}
