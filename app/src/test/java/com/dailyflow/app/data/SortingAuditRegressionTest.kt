package com.dailyflow.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Runs against the unmodified production DAO/repository, with real native SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SortingAuditRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = LocalDate.of(2026, 10, 29)
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    private val dao get() = db.taskDao()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DailyFlowDatabase::class.java).build()
        repo = RoomTaskRepository(db)
    }
    @After fun close() { db.close() }

    private suspend fun seed(id: String, order: Long = 0, date: LocalDate = day) {
        dao.insertTask(TaskEntity(id, "title $id", "note $id", date, sortOrder = order,
            createdAt = 1, updatedAt = 5, priority = Priority.HIGH, stateRevision = 3))
    }

    // A test-only trigger counts actual rows written, not a mock of updateSortOrder.
    private fun trackWrites() {
        db.openHelper.writableDatabase.execSQL("CREATE TABLE audit_sort_writes (id TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER audit_sort_changed AFTER UPDATE OF sortOrder ON tasks BEGIN INSERT INTO audit_sort_writes VALUES(NEW.id); END")
    }
    private fun writtenIds(): List<String> = db.openHelper.readableDatabase
        .query("SELECT id FROM audit_sort_writes ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    private fun preservedData(): Map<String, List<List<String?>>> =
        listOf("tasks", "categories", "recurrence_rules", "recurrence_versions", "task_day_states", "task_exceptions")
            .associateWith { table ->
                db.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    val columns = cursor.columnNames.indices.filter { cursor.columnNames[it] != "sortOrder" }
                    buildList<List<String?>> {
                        while (cursor.moveToNext()) add(columns.map { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }.sortedBy { it.joinToString("|") }
                }
            }

    @Test fun primaryKeyUpdateTouchesOnlyOneOfSeveralZeroRows() = runBlocking {
        for (id in listOf("a", "b", "c")) seed(id)
        val before = preservedData()
        trackWrites()
        assertEquals(1, dao.updateSortOrder("a", 7))
        assertEquals(0, dao.updateSortOrder("a", 7))
        assertEquals(0, dao.updateSortOrder("absent", 7))
        assertEquals(listOf("a"), writtenIds())
        assertEquals(0L, dao.task("b")!!.sortOrder)
        assertEquals(0L, dao.task("c")!!.sortOrder)
        assertEquals(before, preservedData())
    }

    @Test fun daoReportsExactRowCountsAtNegativeAndLongBounds() = runBlocking {
        seed("a"); seed("b")
        trackWrites()
        for (position in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
            assertEquals(1, dao.updateSortOrder("a", position))
            assertEquals(position, dao.task("a")!!.sortOrder)
            assertEquals(0, dao.updateSortOrder("a", position))
            assertEquals(0L, dao.task("b")!!.sortOrder)
        }
        assertEquals(listOf("a", "a", "a", "a"), writtenIds())
    }

    @Test fun zeroCollisionPreservesOtherDatesRulesExceptionsAndHistory() = runBlocking {
        seed("a"); seed("b"); seed("c", date = day.plusDays(2)); seed("d")
        for (id in listOf("a", "b")) {
            dao.insertRule(RecurrenceRuleEntity.from(id, RecurrenceRule(Frequency.DAILY)))
            dao.insertVersion(RecurrenceVersionEntity(id, day, Frequency.DAILY, 1, 0, null))
        }
        dao.saveDayState(TaskDayStateEntity("a", day, true, 8))
        dao.saveDayState(TaskDayStateEntity("b", day.plusDays(1), true, 9))
        dao.saveException(TaskExceptionEntity("a", day.plusDays(1), titleOverride = "one day", updatedAt = 10, priorityOverride = Priority.LOW))
        val before = preservedData()
        val history = repo.observeHistory(day, day.plusDays(1)).first().associateBy { it.taskId to it.date }
        trackWrites()
        assertTrue(repo.moveTask("a", "b", 0, 0))
        assertEquals(listOf("b", "a", "c", "d"), dao.tasksForOrdering().map { it.id })
        assertEquals(listOf("a", "c", "d"), writtenIds())
        assertEquals(before, preservedData())
        assertEquals(history, repo.observeHistory(day, day.plusDays(1)).first().associateBy { it.taskId to it.date }
            .mapValues { (key, row) -> row.copy(sortOrder = history.getValue(key).sortOrder) })
        assertEquals(listOf("b", "a"), repo.observeDay(day.plusDays(1)).first().map { it.taskId })
    }

    @Test fun negativeTiesUseStableIdOrderAndOnlyChangePositions() = runBlocking {
        for (id in listOf("c", "b", "a")) seed(id, -7)
        val before = preservedData()
        assertEquals(listOf("a", "b", "c"), dao.tasksForOrdering().map { it.id })
        trackWrites()
        assertTrue(repo.moveTask("b", "a", -7, -7))
        assertEquals(listOf("b", "a", "c"), dao.tasksForOrdering().map { it.id })
        assertEquals(listOf(0L, 1L, 2L), dao.tasksForOrdering().map { it.sortOrder })
        assertEquals(3, writtenIds().size)
        assertEquals(before, preservedData())
    }

    @Test fun distinctLongBoundsSwapExactlyTwoRowsWithoutArithmeticOverflow() = runBlocking {
        seed("a", Long.MIN_VALUE); seed("b", 0); seed("c", Long.MAX_VALUE)
        val before = preservedData()
        trackWrites()
        assertTrue(repo.moveTask("a", "c", Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(listOf("c", "b", "a"), dao.tasksForOrdering().map { it.id })
        assertEquals(listOf("a", "c"), writtenIds())
        assertEquals(Long.MAX_VALUE, dao.task("a")!!.sortOrder)
        assertEquals(Long.MIN_VALUE, dao.task("c")!!.sortOrder)
        assertEquals(before, preservedData())
    }

    @Test fun thousandZeroRowsAreReindexedIntentionallyWithoutOtherColumnWrites() = runBlocking {
        db.withTransaction { for (n in 0 until 1000) seed("task" + n.toString().padStart(4, '0')) }
        val before = preservedData()
        trackWrites()
        assertTrue(repo.moveTask("task0000", "task0001", 0, 0))
        val rows = dao.tasksForOrdering()
        val expected = (0 until 1000).map { "task" + it.toString().padStart(4, '0') }.toMutableList()
        java.util.Collections.swap(expected, 0, 1)
        assertEquals(expected, rows.map { it.id })
        assertEquals((0L until 1000L).toList(), rows.map { it.sortOrder })
        assertEquals(999, writtenIds().size)
        assertEquals(999, writtenIds().toSet().size)
        assertEquals(before, preservedData())
    }

    @Test fun collisionOrderSurvivesDiskReopenCompletionUndoAndDateChange() = runBlocking {
        db.close()
        val name = "audit-sort-reopen.db"
        db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db)
        for (id in listOf("a", "b")) {
            seed(id)
            dao.insertRule(RecurrenceRuleEntity.from(id, RecurrenceRule(Frequency.DAILY)))
        }
        assertTrue(repo.moveTask("a", "b", 0, 0))
        repo.setCompleted("b", day, true)
        val positions = dao.tasksForOrdering().map { it.id to it.sortOrder }
        db.close(); db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db)
        assertEquals(positions, dao.tasksForOrdering().map { it.id to it.sortOrder })
        assertTrue(repo.observeDay(day).first().single { it.taskId == "b" }.completed)
        assertFalse(repo.observeDay(day.plusDays(1)).first().single { it.taskId == "b" }.completed)
        repo.setCompleted("b", day, false)
        assertEquals(positions, dao.tasksForOrdering().map { it.id to it.sortOrder })
        for (date in listOf(day, day.plusDays(1))) {
            assertEquals(listOf("b", "a"), repo.observeDay(date).first().map { it.taskId })
        }
    }

    @Test fun staleMissingAndSelfMovesWriteNothing() = runBlocking {
        seed("a", 1); seed("b", 2)
        val before = dao.tasksForOrdering()
        trackWrites()
        assertFalse(repo.moveTask("a", "a", 1, 1))
        assertFalse(repo.moveTask("a", "missing", 1, 1))
        try {
            repo.moveTask("a", "b", 0, 0)
            fail("Stale snapshot must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(writtenIds().isEmpty())
        assertEquals(before, dao.tasksForOrdering())
    }
}
