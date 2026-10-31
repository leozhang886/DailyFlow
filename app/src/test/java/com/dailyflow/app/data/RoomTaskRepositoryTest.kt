package com.dailyflow.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.domain.*
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomTaskRepositoryTest {
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    private val today = LocalDate.of(2026, 10, 27)
    private val tomorrow = today.plusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"), ZoneOffset.UTC)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, DailyFlowDatabase::class.java).build()
        repo = RoomTaskRepository(db, clock)
    }
    @After fun tearDown() { db.close() }
    private suspend fun tasks(date: LocalDate = today) = repo.observeDay(date).first()
    private suspend fun daily() = repo.create("每日测试", "备注", today, RecurrenceRule(Frequency.DAILY))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected invalid operation to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun createIsVisibleAndOneTimeNotInTomorrow() = runBlocking {
        val id = repo.create("  一次任务  ", "", today)
        assertEquals(id, tasks().single().taskId)
        assertEquals("一次任务", tasks().single().title)
        assertTrue(tasks(tomorrow).isEmpty())
    }
    @Test fun editOneTimePersistsContent() = runBlocking {
        val id = repo.create("原题", "原备注", today)
        repo.updateContent(id, today, "新题", "新备注", EditScope.SINGLE)
        assertEquals("新题", tasks().single().title)
        assertEquals("新备注", db.taskDao().task(id)!!.note)
    }
    @Test fun deleteOneTimeRemovesIt() = runBlocking {
        val id = repo.create("删除", "", today)
        repo.delete(id, today, EditScope.SINGLE)
        assertTrue(tasks().isEmpty()); assertEquals(0, db.taskDao().taskCount())
    }
    @Test fun completionOnTodayLeavesTomorrowIncomplete() = runBlocking {
        val id = daily()
        repo.setCompleted(id, today, true)
        assertTrue(tasks().single().completed)
        assertFalse(tasks(tomorrow).single().completed)
    }
    @Test fun undoCompletionDoesNotAffectAnotherDate() = runBlocking {
        val id = daily()
        repo.setCompleted(id, today, true); repo.setCompleted(id, tomorrow, true)
        repo.setCompleted(id, today, false)
        assertFalse(tasks().single().completed)
        assertTrue(tasks(tomorrow).single().completed)
        assertEquals(1, db.taskDao().dayStateCount())
    }
    @Test fun completionIsIdempotent() = runBlocking {
        val id = daily()
        repeat(3) { repo.setCompleted(id, today, true) }
        assertEquals(1, db.taskDao().dayStateCount())
        repeat(3) { repo.setCompleted(id, today, false) }
        assertEquals(0, db.taskDao().dayStateCount())
    }
    @Test fun singleEditDoesNotChangeSeriesOrOtherDates() = runBlocking {
        val id = daily()
        repo.updateContent(id, today, "仅今天", "", EditScope.SINGLE)
        assertEquals("仅今天", tasks().single().title)
        assertEquals("", tasks().single().note)
        assertTrue(tasks().single().overridden)
        assertEquals("每日测试", tasks(tomorrow).single().title)
        assertEquals("备注", tasks(tomorrow).single().note)
    }
    @Test fun seriesEditKeepsExceptionAndCompletion() = runBlocking {
        val id = daily()
        repo.updateContent(id, today, "单次覆盖", "", EditScope.SINGLE)
        repo.setCompleted(id, today, true)
        repo.updateContent(id, today, "系列新题", "系列备注", EditScope.SERIES)
        assertEquals("单次覆盖", tasks().single().title)
        assertTrue(tasks().single().completed)
        assertEquals("系列新题", tasks(tomorrow).single().title)
    }
    @Test fun deleteSingleKeepsTomorrowAndRemovesOnlyItsState() = runBlocking {
        val id = daily()
        repo.setCompleted(id, today, true); repo.setCompleted(id, tomorrow, true)
        repo.delete(id, today, EditScope.SINGLE)
        assertTrue(tasks().isEmpty()); assertTrue(tasks(tomorrow).single().completed)
        assertEquals(1, db.taskDao().taskCount()); assertEquals(1, db.taskDao().dayStateCount())
    }
    @Test fun skippedOccurrenceCannotBeCompleted() = runBlocking {
        val id = daily(); repo.delete(id, today, EditScope.SINGLE)
        rejects { repo.setCompleted(id, today, true) }
        assertEquals(0, db.taskDao().dayStateCount())
    }
    @Test fun deleteSeriesCascadesAllDependentRows() = runBlocking {
        val id = daily(); repo.setCompleted(id, today, true)
        repo.updateContent(id, tomorrow, "明天修改", "", EditScope.SINGLE)
        repo.delete(id, today, EditScope.SERIES)
        assertEquals(0, db.taskDao().taskCount()); assertEquals(0, db.taskDao().ruleCount())
        assertEquals(0, db.taskDao().dayStateCount()); assertEquals(0, db.taskDao().exceptionCount())
    }
    @Test fun invalidInputAndEarlierEndDateNeverLeavePartialTask() = runBlocking {
        rejects { repo.create(" ", "", today) }
        rejects { repo.create("结束错误", "", today, RecurrenceRule(Frequency.DAILY, untilDate = today.minusDays(1))) }
        assertEquals(0, db.taskDao().taskCount())
    }
    @Test fun invalidOccurrenceDateCannotBeCompleted() = runBlocking {
        val id = repo.create("一次", "", today)
        rejects { repo.setCompleted(id, tomorrow, true) }
        rejects { repo.updateContent(id, tomorrow, "错误日期", "", EditScope.SINGLE) }
        assertEquals(0, db.taskDao().dayStateCount())
    }
    @Test fun foreignKeysRejectOrphanDayState() = runBlocking {
        var rejected = false
        try { db.taskDao().saveDayState(TaskDayStateEntity("missing", today, true, 1)) }
        catch (_: android.database.sqlite.SQLiteConstraintException) { rejected = true }
        assertTrue(rejected); assertEquals(0, db.taskDao().dayStateCount())
    }
    @Test fun transactionRollsBackWhenLaterInsertFails() = runBlocking {
        try {
            db.withTransaction {
                db.taskDao().insertTask(TaskEntity("rollback", "回滚", "", today, createdAt = 1, updatedAt = 1))
                db.taskDao().insertRule(RecurrenceRuleEntity("orphan", Frequency.DAILY, 1, 0, null))
            }
            fail("Expected FK failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(0, db.taskDao().taskCount())
    }
    @Test fun deletingCategoryUnlinksTaskInsteadOfDeletingIt() = runBlocking {
        val dao = db.taskDao(); dao.insertCategory(CategoryEntity("work", "工作"))
        dao.insertTask(TaskEntity("categorized", "任务", "", today, "work", createdAt = 1, updatedAt = 1))
        dao.deleteCategory("work")
        assertNull(dao.task("categorized")!!.categoryId); assertEquals(1, dao.taskCount())
    }
    @Test fun weeklyRuleIsStoredAndFiltersSelectedDays() = runBlocking {
        repo.create("周任务", "", today, RecurrenceRule(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)))
        assertEquals(1, tasks().size); assertTrue(tasks(tomorrow).isEmpty())
        assertEquals(1, tasks(today.plusDays(2)).size)
    }
    @Test fun futureReadsDoNotGeneratePermanentRows() = runBlocking {
        daily(); assertEquals(1, tasks(LocalDate.of(2040, 2, 29)).size)
        assertEquals(1, db.taskDao().taskCount()); assertEquals(0, db.taskDao().dayStateCount())
    }
    @Test fun flowEmitsAfterWriteWithoutReload() = runBlocking {
        assertTrue(tasks().isEmpty())
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) { repo.observeDay(today).first { it.isNotEmpty() } }
        }
        val id = repo.create("响应式", "", today)
        assertEquals(id, next.await().single().taskId)
    }
    @Test fun largeHistoryReturnsSelectedDateWithStableOrder() = runBlocking {
        db.withTransaction {
            repeat(1000) { index ->
                db.taskDao().insertTask(TaskEntity("history-$index", "历史$index", "", today.minusDays((index + 1).toLong()), createdAt = 1, updatedAt = 1))
            }
            db.taskDao().insertTask(TaskEntity("z", "先显示", "", today, sortOrder = -1, createdAt = 1, updatedAt = 1))
            db.taskDao().insertTask(TaskEntity("b", "后显示", "", today, createdAt = 1, updatedAt = 1))
            db.taskDao().insertTask(TaskEntity("a", "中间", "", today, createdAt = 1, updatedAt = 1))
        }
        assertEquals(listOf("z", "a", "b"), tasks().map { it.taskId })
        assertEquals(1, tasks(today.minusDays(1000)).size)
    }
    @Test fun fileDatabaseSurvivesCloseAndReopen() = runBlocking {
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        db.close(); db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db, clock)
        val id = daily(); repo.setCompleted(id, today, true)
        repo.updateContent(id, tomorrow, "明天覆盖", "持久备注", EditScope.SINGLE)
        db.close(); db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db, clock)
        assertTrue(tasks().single().completed)
        assertEquals("明天覆盖", tasks(tomorrow).single().title)
        assertFalse(tasks(tomorrow).single().completed)
        assertEquals("持久备注", tasks(tomorrow).single().note)
        db.close(); context.deleteDatabase(name); Unit
    }
}
