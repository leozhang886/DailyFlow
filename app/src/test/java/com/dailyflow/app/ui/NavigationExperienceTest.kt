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
class NavigationExperienceTest{
    private val clock=Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"),ZoneOffset.UTC)
    private val repo=object:TaskRepository{
        override fun observeDay(date:LocalDate)=flowOf(emptyList<TaskOccurrence>())
        override suspend fun create(title:String,note:String,date:LocalDate,rule:RecurrenceRule?)="unused"
        override suspend fun updateContent(taskId:String,date:LocalDate,title:String,note:String,scope:EditScope)=Unit
        override suspend fun delete(taskId:String,date:LocalDate,scope:EditScope)=Unit
        override suspend fun setCompleted(taskId:String,date:LocalDate,completed:Boolean)=Unit
    }
    @Before fun main(){Dispatchers.setMain(StandardTestDispatcher())}
    @After fun reset(){Dispatchers.resetMain()}
    @Test fun returnFromHistoricalDetailsKeepsMonth(){val vm=PlanningViewModel(repo,clock);vm.openHistory();vm.selectHistoryMonth(YearMonth.of(2025,2));vm.openHistoryDetails();assertTrue(vm.returnsToHistory);vm.resumeHistory();assertEquals(YearMonth.of(2025,2),vm.historyMonth.value);assertEquals(PlanningPage.HISTORY,vm.state.value.page);assertFalse(vm.returnsToHistory)}
    @Test fun explicitExternalNavigationClearsHistoryReturn(){val vm=PlanningViewModel(repo,clock);vm.openHistory();vm.openHistoryDetails();assertTrue(vm.canNavigateAway());assertFalse(vm.returnsToHistory);assertNull(vm.state.value.page)}
    @Test fun selectedMonthRestoresFromSavedState(){val saved=SavedStateHandle();val vm=PlanningViewModel(repo,clock,saved);vm.selectHistoryMonth(YearMonth.of(2024,2));val restored=PlanningViewModel(repo,clock,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}));assertEquals(YearMonth.of(2024,2),restored.historyMonth.value)}
    @Test fun freshHistoryEntrySavesCurrentMonthInsteadOfStaleMonth(){val saved=SavedStateHandle(mapOf("historyMonth" to "2024-02"));val vm=PlanningViewModel(repo,clock,saved);vm.openHistory();val restored=PlanningViewModel(repo,clock,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}));assertEquals(YearMonth.of(2026,10),restored.historyMonth.value)}
    @Test fun futureAndOutOfRangeMonthsDoNotReplaceValidMonth(){val vm=PlanningViewModel(repo,clock);vm.selectHistoryMonth(YearMonth.of(2024,2));vm.selectHistoryMonth(YearMonth.of(2027,1));vm.selectHistoryMonth(YearMonth.of(0,1));assertEquals(YearMonth.of(2024,2),vm.historyMonth.value)}
}
