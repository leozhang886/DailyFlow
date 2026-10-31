package com.dailyflow.app.ui

import com.dailyflow.app.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DailyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val date = LocalDate.of(2026, 10, 27)
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class FakeRepository : TaskRepository {
        val lists = mutableMapOf<LocalDate, MutableStateFlow<List<TaskOccurrence>>>()
        var failWrites = false
        var lastCompletion: Pair<LocalDate, Boolean>? = null
        override fun observeDay(date: LocalDate) = lists.getOrPut(date) { MutableStateFlow(emptyList()) }
        override suspend fun create(title: String, note: String, date: LocalDate, rule: RecurrenceRule?) = error("Not used")
        override suspend fun updateContent(taskId: String, date: LocalDate, title: String, note: String, scope: EditScope) = error("Not used")
        override suspend fun delete(taskId: String, date: LocalDate, scope: EditScope) = error("Not used")
        override suspend fun setCompleted(taskId: String, date: LocalDate, completed: Boolean) {
            if (failWrites) error("Write failed")
            lastCompletion = date to completed
        }
    }
    @Test fun dateChangeSwitchesObservedListWithoutMixingOldDate() = runTest(dispatcher) {
        val repo = FakeRepository()
        repo.observeDay(date).value = listOf(TaskOccurrence("today", date, "今天", "", false, true, false))
        repo.observeDay(date.plusDays(1)).value = listOf(TaskOccurrence("tomorrow", date.plusDays(1), "明天", "", false, true, false))
        val model = DailyViewModel(repo, fixedClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        runCurrent(); assertEquals("today", model.state.value.tasks.single().taskId)
        model.nextDay(); runCurrent()
        assertEquals(date.plusDays(1), model.state.value.date)
        assertEquals("tomorrow", model.state.value.tasks.single().taskId)
        repo.observeDay(date).value = emptyList(); runCurrent()
        assertEquals("tomorrow", model.state.value.tasks.single().taskId)
    }
    @Test fun todayReturnsToClockDateAfterHistoryNavigation() = runTest(dispatcher) {
        val model = DailyViewModel(FakeRepository(), fixedClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        model.selectDate(LocalDate.of(2028, 2, 29)); runCurrent()
        assertEquals(LocalDate.of(2028, 2, 29), model.state.value.date)
        model.previousDay(); runCurrent(); assertEquals(LocalDate.of(2028, 2, 28), model.state.value.date)
        model.today(); runCurrent(); assertEquals(date, model.state.value.date)
    }
    @Test fun completionUsesOccurrenceDateAndSurfacesWriteFailure() = runTest(dispatcher) {
        val repo = FakeRepository(); val model = DailyViewModel(repo, fixedClock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        val task = TaskOccurrence("id", date.plusDays(1), "任务", "", false, true, false)
        model.setCompleted(task, true); runCurrent()
        assertEquals(date.plusDays(1) to true, repo.lastCompletion)
        repo.failWrites = true; model.setCompleted(task, false); runCurrent()
        assertEquals("保存失败，请重试", model.state.value.error)
    }
}
