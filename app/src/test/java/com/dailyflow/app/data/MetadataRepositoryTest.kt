package com.dailyflow.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetadataRepositoryTest {
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    private val date = LocalDate.of(2026, 10, 27)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, DailyFlowDatabase::class.java).build(); repo = RoomTaskRepository(db) }
    @After fun close() { db.close() }
    private suspend fun row(day: LocalDate = date) = repo.observeDay(day).first().single()

    @Test fun titleTimePriorityAndNoteArePersisted() = runBlocking {
        val id = repo.createTask(TaskDraft(" 标题 ", "备注", date, 754, Priority.HIGH))
        assertEquals("标题", row().title); assertEquals(754, row().scheduledMinute)
        assertEquals(Priority.HIGH, row().priority)
        assertEquals(754, repo.loadDraft(id, date, EditScope.SINGLE)!!.scheduledMinute)
    }
    @Test fun singleEditCanClearTimeWithoutChangingTomorrowOrCompletion() = runBlocking {
        val id = repo.createTask(TaskDraft("系列", "原备注", date, 600, Priority.LOW, RecurrenceRule(Frequency.DAILY)))
        repo.setCompleted(id, date, true)
        repo.updateTask(id, date, TaskDraft("仅当天", "", date, null, Priority.HIGH, RecurrenceRule(Frequency.DAILY)), EditScope.SINGLE)
        assertNull(row().scheduledMinute); assertEquals("", row().note); assertTrue(row().completed)
        assertEquals(Priority.HIGH, row().priority)
        assertEquals(600, row(date.plusDays(1)).scheduledMinute); assertEquals(Priority.LOW, row(date.plusDays(1)).priority)
        assertFalse(row(date.plusDays(1)).completed)
    }
    @Test fun legacyContentUpdatePreservesTimeOverride() = runBlocking {
        val rule = RecurrenceRule(Frequency.DAILY)
        val id = repo.createTask(TaskDraft("系列", "", date, 600, rule = rule))
        repo.updateTask(id, date, TaskDraft("第一次", "", date, 720, rule = rule), EditScope.SINGLE)
        repo.updateContent(id, date, "第二次", "新备注", EditScope.SINGLE)
        assertEquals(720, row().scheduledMinute)
    }
    @Test fun seriesEditorLoadsBaseInsteadOfCopyingSingleOverride() = runBlocking {
        val id = repo.createTask(TaskDraft("系列标题", "系列备注", date, 600, rule = RecurrenceRule(Frequency.DAILY)))
        repo.updateContent(id, date, "单次标题", "单次备注", EditScope.SINGLE)
        assertEquals("系列标题", repo.loadDraft(id, date, EditScope.SERIES)!!.title)
        assertEquals("单次标题", repo.loadDraft(id, date, EditScope.SINGLE)!!.title)
    }
    @Test fun movingOneTimeTaskMovesItsCompletionOnly() = runBlocking {
        val id = repo.createTask(TaskDraft("移动", date = date))
        repo.setCompleted(id, date, true)
        repo.updateTask(id, date, TaskDraft("移动后", date = date.plusDays(1)), EditScope.SINGLE)
        assertTrue(repo.observeDay(date).first().isEmpty())
        assertTrue(row(date.plusDays(1)).completed); assertEquals(1, db.taskDao().dayStateCount())
    }
    @Test fun recurringDateAndRuleChangesAreRejectedAtomically() = runBlocking {
        val rule = RecurrenceRule(Frequency.DAILY)
        val id = repo.createTask(TaskDraft("系列", date = date, rule = rule))
        try { repo.updateTask(id, date, TaskDraft("不应保存", date = date.plusDays(1), rule = rule), EditScope.SINGLE); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals("系列", row().title)
        try { repo.updateTask(id, date, TaskDraft("不应保存", date = date), EditScope.SERIES); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, db.taskDao().exceptionCount())
    }
    @Test fun invalidTimeDoesNotCreateATask() = runBlocking {
        for (minute in listOf(-1, 1440)) {
            try { repo.createTask(TaskDraft("错误时间", date = date, scheduledMinute = minute)); fail() }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, db.taskDao().taskCount())
    }
    @Test fun v1MigrationPreservesExistingTaskRuleStateAndException() = runBlocking {
        val name = "migration-preservation.db"
        context.deleteDatabase(name)
        val resource = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/1.json"))
        val schema = JSONObject(resource.bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$" + "{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,categoryId,sortOrder,createdAt,updatedAt) VALUES('old','原任务','备注',?,NULL,0,1,1)", arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO recurrence_rules VALUES('old','DAILY',1,0,NULL)")
            old.execSQL("INSERT INTO task_day_states VALUES('old',?,1,1)", arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO task_exceptions VALUES('old',?,0,'旧单次覆盖','旧备注',1)", arrayOf(date.plusDays(1).toEpochDay()))
            old.version = 1
        }
        db.close(); db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db)
        assertEquals("原任务", row().title); assertTrue(row().completed)
        assertEquals("旧单次覆盖", row(date.plusDays(1)).title)
        assertEquals("旧备注", row(date.plusDays(1)).note)
        assertNull(row().scheduledMinute); assertEquals(Priority.NORMAL, row().priority)
        repo.updateTask("old", date, TaskDraft("新单次", date = date, scheduledMinute = 100, priority = Priority.HIGH, rule = RecurrenceRule(Frequency.DAILY)), EditScope.SINGLE)
        assertEquals(100, row().scheduledMinute); assertTrue(row().completed)
        db.close(); db = DailyFlowDatabase.open(context, name); repo = RoomTaskRepository(db)
        assertEquals(Priority.HIGH, row().priority)
        db.close(); context.deleteDatabase(name); Unit
    }
}
