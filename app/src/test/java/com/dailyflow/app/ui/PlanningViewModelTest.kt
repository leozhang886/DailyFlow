package com.dailyflow.app.ui

import androidx.lifecycle.SavedStateHandle
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val date=LocalDate.of(2026,10,27)
    private val clock=Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"),ZoneOffset.UTC)
    private val series=SeriesSummary("s","系列",date.minusDays(3),RecurrenceRule(Frequency.DAILY),3)
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    private class Repo:TaskRepository{
        var changes=0;var recoveries=0;var fail=false;var savedRule:RecurrenceRule?=null
        override fun observeDay(date:LocalDate)=flowOf(emptyList<TaskOccurrence>())
        override suspend fun create(title:String,note:String,date:LocalDate,rule:RecurrenceRule?)="new"
        override suspend fun updateContent(taskId:String,date:LocalDate,title:String,note:String,scope:EditScope)=Unit
        override suspend fun delete(taskId:String,date:LocalDate,scope:EditScope)=Unit
        override suspend fun setCompleted(taskId:String,date:LocalDate,completed:Boolean)=Unit
        override suspend fun changeRecurrence(taskId:String,effectiveFrom:LocalDate,rule:RecurrenceRule,expectedRevision:Long,today:()->LocalDate){if(fail)error("保存失败");changes++;savedRule=rule}
        override suspend fun recoverException(taskId:String,date:LocalDate,restoreDeleted:Boolean){recoveries++}
    }
    @Test fun unchangedRuleCannotResetIntervalAnchor()=runTest{
        val repo=Repo();val vm=PlanningViewModel(repo,clock);vm.openRule(series.copy(rule=RecurrenceRule(Frequency.DAILY,2)))
        vm.confirmRule();vm.saveRule();runCurrent();assertFalse(vm.state.value.confirmRule);assertEquals(0,repo.changes);assertNotNull(vm.state.value.error)
    }
    @Test fun savingRequiresExplicitConfirmation()=runTest{
        val repo=Repo();val vm=PlanningViewModel(repo,clock);vm.openRule(series);vm.interval("2");vm.saveRule();runCurrent();assertEquals(0,repo.changes)
        vm.confirmRule();assertTrue(vm.state.value.confirmRule);vm.saveRule();runCurrent();assertEquals(1,repo.changes);assertEquals(2,repo.savedRule!!.interval)
    }
    @Test fun invalidIntervalAndEmptyWeeklySelectionDoNotWrite()=runTest{
        val repo=Repo();val vm=PlanningViewModel(repo,clock);vm.openRule(series)
        for(input in listOf("","0","366","x")){vm.interval(input);vm.confirmRule();assertFalse(vm.state.value.confirmRule);assertNotNull(vm.state.value.error)}
        vm.interval("1");vm.frequency(Frequency.WEEKLY);vm.weekday(date.dayOfWeek);vm.confirmRule();assertNotNull(vm.state.value.error);assertEquals(0,repo.changes)
    }
    @Test fun earlierEffectiveDateAndEndDateRejected()=runTest{
        val vm=PlanningViewModel(Repo(),clock);vm.openRule(series);vm.effectiveDate(date.minusDays(1));vm.confirmRule();assertNotNull(vm.state.value.error)
        vm.effectiveDate(date);vm.until(date.minusDays(1));vm.confirmRule();assertNotNull(vm.state.value.error)
    }
    @Test fun dirtyBackNeedsDiscardAndCancelDoesNotWrite()=runTest{
        val repo=Repo();val vm=PlanningViewModel(repo,clock);vm.openRule(series);vm.interval("2");vm.back();assertTrue(vm.state.value.discard)
        vm.keepDraft();assertFalse(vm.state.value.discard);assertEquals("2",vm.state.value.form!!.settings.intervalText)
        vm.discard();assertEquals(PlanningPage.MANAGE,vm.state.value.page);assertEquals(0,repo.changes)
    }
    @Test fun saveFailureKeepsRuleDraft()=runTest{
        val repo=Repo();repo.fail=true;val vm=PlanningViewModel(repo,clock);vm.openRule(series);vm.interval("3");vm.confirmRule();vm.saveRule();runCurrent()
        assertFalse(vm.state.value.busy);assertEquals("3",vm.state.value.form!!.settings.intervalText);assertNotNull(vm.state.value.error)
    }
    @Test fun recoveryRequiresConfirmationAndCancelHasNoEffect()=runTest{
        val repo=Repo();val vm=PlanningViewModel(repo,clock);vm.openExceptions(series);vm.requestRecovery(TaskException("s",date,true,null));vm.dismissConfirm();vm.recover();runCurrent();assertEquals(0,repo.recoveries)
        vm.requestRecovery(TaskException("s",date,true,null));vm.recover();runCurrent();assertEquals(1,repo.recoveries)
    }
    @Test fun ruleDraftRestoresThroughSavedStateHandle()=runTest{
        val saved=SavedStateHandle();val vm=PlanningViewModel(Repo(),clock,saved);vm.openRule(series);vm.interval("5");vm.until(date.plusDays(20));runCurrent()
        val clone=SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)})
        val restored=PlanningViewModel(Repo(),clock,clone)
        assertEquals(PlanningPage.RULE,restored.state.value.page);assertEquals("5",restored.state.value.form!!.settings.intervalText);assertTrue(restored.state.value.form!!.dirty)
        assertEquals(3L,restored.state.value.form!!.series.revision)
    }
    @Test fun taskDraftRestoresWithoutDatabaseWriteAndDiscardClearsState()=runTest{
        val saved=SavedStateHandle();val repo=Repo();val vm=DailyViewModel(repo,clock,saved);vm.selectDate(date.plusDays(1));vm.newTask();vm.setTitle("恢复标题");vm.setNote("备注");vm.setNewRepeat(Frequency.WEEKLY);runCurrent()
        val clone=SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)})
        val restored=DailyViewModel(repo,clock,clone);assertEquals(TaskPage.EDITOR,restored.interaction.value.page);assertEquals("恢复标题",restored.interaction.value.editor!!.draft.title)
        assertEquals(date.plusDays(1),restored.interaction.value.editor!!.draft.date);restored.discardEditor();runCurrent();assertNull(clone.get<String>("editorDraft"))
    }
    @Test fun corruptSavedStateAndOversizedDraftAreRejectedSafely(){
        assertNull(DraftStateCodec.decodeEditor("not-base64"));assertNull(DraftStateCodec.decodeRule("eA=="))
        assertNull(DraftStateCodec.encodeEditor(EditorState(TaskDraft("x".repeat(40000),date=date))))
    }
    @Test fun codecRetainsOriginalScopeTimePriorityAndDirtyFlag(){
        val d=TaskDraft("原","",date,700,Priority.HIGH,RecurrenceRule(Frequency.DAILY,3,untilDate=date.plusDays(20)))
        val e=EditorState(d.copy(title="新"),d,"id",date,EditScope.SERIES)
        assertEquals(e,DraftStateCodec.decodeEditor(DraftStateCodec.encodeEditor(e)))
    }
    @Test fun widgetNavigationCannotDiscardRuleDraft()=runTest{
        val vm=PlanningViewModel(Repo(),clock);vm.openRule(series);vm.interval("2");assertFalse(vm.canNavigateAway());assertNotNull(vm.state.value.form)
    }
    @Test fun historyCannotAdvancePastCurrentMonth()=runTest{
        val vm=PlanningViewModel(Repo(),clock);vm.openHistory();vm.moveMonth(1);assertEquals(YearMonth.of(2026,10),vm.historyMonth.value)
        vm.moveMonth(-1);assertEquals(YearMonth.of(2026,9),vm.historyMonth.value)
    }
}
