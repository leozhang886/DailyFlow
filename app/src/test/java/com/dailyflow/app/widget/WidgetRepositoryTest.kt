package com.dailyflow.app.widget

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WidgetRepositoryTest {
    private val date = LocalDate.of(2026,10,27)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    private lateinit var clock: MutableClock
    private lateinit var controller: WidgetController
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, DailyFlowDatabase::class.java).build()
        repo = RoomTaskRepository(db); clock = MutableClock(date.atTime(12,0).toInstant(ZoneOffset.UTC))
        controller = WidgetController(repo, clock)
    }
    @After fun close() { db.close() }
    private suspend fun create(repeat: Boolean = false) = repo.createTask(TaskDraft("DF-W 今日", date = date,
        rule = if (repeat) RecurrenceRule(Frequency.DAILY) else null))
    private suspend fun command(): WidgetCommand { val t = controller.snapshot().tasks.single(); return WidgetCommand(t.taskId,t.date,t.completed,t.stateRevision,!t.completed) }

    @Test fun readsOnlyTodayAndCountsActualCompletion() = runBlocking {
        create(); repo.create("昨天", "", date.minusDays(1)); repo.create("明天", "", date.plusDays(1))
        val snapshot = controller.snapshot(); assertEquals(date,snapshot.date); assertEquals(1,snapshot.tasks.size); assertEquals(0,snapshot.completedCount)
    }
    @Test fun completeWritesRealDayStateWithoutOpeningActivity() = runBlocking {
        val id = create(); assertEquals(WidgetActionResult.APPLIED, controller.execute(command()))
        assertTrue(repo.observeDay(date).first().single().completed)
        assertTrue(db.taskDao().dayState(id,date)!!.completed); assertEquals(1,db.taskDao().dayStateCount())
    }
    @Test fun undoDeletesCompletedStateAndRefreshesCount() = runBlocking {
        create(); controller.execute(command()); assertEquals(1,controller.snapshot().completedCount)
        assertEquals(WidgetActionResult.APPLIED,controller.execute(command()))
        assertEquals(0,db.taskDao().dayStateCount()); assertEquals(0,controller.snapshot().completedCount)
    }
    @Test fun dailyRepeatOnlyCompletesCurrentDate() = runBlocking {
        create(true); controller.execute(command())
        assertTrue(repo.observeDay(date).first().single().completed)
        assertFalse(repo.observeDay(date.plusDays(1)).first().single().completed)
    }
    @Test fun appAndWidgetUseSameRepositoryAndUpdatesNotifyAfterCommit() = runBlocking {
        val id = create(); var notifications = 0
        val appRepo = UpdatingTaskRepository(repo) { notifications++ }
        appRepo.setCompleted(id,date,true); assertTrue(controller.snapshot().tasks.single().completed)
        controller.execute(command()); assertFalse(appRepo.observeDay(date).first().single().completed); assertEquals(1,notifications)
    }
    @Test fun deletionRemovesRowAndStaleCommandCannotResurrectIt() = runBlocking {
        val id=create(); val old=command(); repo.delete(id,date,EditScope.SINGLE)
        assertTrue(controller.snapshot().tasks.isEmpty()); assertEquals(WidgetActionResult.MISSING,controller.execute(old)); assertEquals(0,db.taskDao().dayStateCount())
    }
    @Test fun singleDeletionOfRepeatHidesOnlyToday() = runBlocking {
        val id=create(true); val old=command(); repo.delete(id,date,EditScope.SINGLE)
        assertTrue(controller.snapshot().tasks.isEmpty()); assertEquals(WidgetActionResult.MISSING,controller.execute(old))
        assertEquals(1,repo.observeDay(date.plusDays(1)).first().size)
    }
    @Test fun crossingMidnightReadsNewDayAndRejectsOldButton() = runBlocking {
        create(true); val old=command(); clock.current=clock.current.plusSeconds(86400)
        assertEquals(date.plusDays(1),controller.snapshot().date)
        assertEquals(WidgetActionResult.EXPIRED,controller.execute(old)); assertEquals(0,db.taskDao().dayStateCount())
    }
    @Test fun transactionChecksDateAgainAfterPrecheck() = runBlocking {
        val id=create(); val calls=AtomicInteger()
        val moving=object: Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = clock.current.plusSeconds(if(calls.getAndIncrement()==0)0 else 86400)
        }
        assertEquals(WidgetActionResult.EXPIRED,WidgetController(repo,moving).execute(WidgetCommand(id,date,false,0,true)))
        assertEquals(0,db.taskDao().dayStateCount())
    }
    @Test fun repeatedSameButtonIsIdempotent() = runBlocking {
        create(); val old=command(); val results=(1..20).map { async(Dispatchers.Default) { controller.execute(old) } }.awaitAll()
        assertEquals(1,results.count { it==WidgetActionResult.APPLIED }); assertEquals(19,results.count { it==WidgetActionResult.STALE })
        assertEquals(1,db.taskDao().dayStateCount()); assertTrue(controller.snapshot().tasks.single().completed)
    }
    @Test fun oldButtonCannotReverseAfterAppCompletesAndUndoes() = runBlocking {
        val id=create(); val old=command(); repo.setCompleted(id,date,true); repo.setCompleted(id,date,false)
        assertEquals(WidgetActionResult.STALE,controller.execute(old)); assertFalse(controller.snapshot().tasks.single().completed)
    }
    @Test fun twoInstancesWithSameOldButtonWriteOnlyOnce() = runBlocking {
        create(); val a=command(); val b=command()
        assertEquals(WidgetActionResult.APPLIED,controller.execute(a)); assertEquals(WidgetActionResult.STALE,controller.execute(b))
        val rendered=mutableMapOf<Int,WidgetSnapshot>(); refreshWidgetInstances(listOf(10,20)) { rendered[it]=controller.snapshot() }
        assertEquals(rendered[10],rendered[20]); assertEquals(1,rendered[20]!!.completedCount)
    }
    @Test fun oneRefreshFailureDoesNotSkipRemainingInstances() = runBlocking {
        val attempted=mutableListOf<Int>()
        try { refreshWidgetInstances(listOf(1,2,3)) { attempted+=it; if(it==2) error("host unavailable") }; fail() } catch(_: IllegalStateException) { }
        assertEquals(listOf(1,2,3),attempted)
    }
    @Test fun explicitTargetCannotBeBlindToggle() = runBlocking {
        val id=create(); assertEquals(WidgetActionResult.STALE,controller.execute(WidgetCommand(id,date,false,0,false)))
        assertEquals(0,db.taskDao().dayStateCount())
    }
    @Test fun appChangingAnotherDateNeverCompletesTodayFromOldSnapshot() = runBlocking {
        val id=create(true); val old=command(); repo.setCompleted(id,date.plusDays(1),true)
        assertEquals(WidgetActionResult.STALE,controller.execute(old)); assertFalse(controller.snapshot().tasks.single().completed)
    }
    @Test fun revisionAndCompletionPersistAcrossDatabaseReopen() = runBlocking {
        db.close(); val name="widget-restart.db"; context.deleteDatabase(name)
        db=DailyFlowDatabase.open(context,name); repo=RoomTaskRepository(db); controller=WidgetController(repo,clock)
        create(); val old=command(); controller.execute(old); db.close()
        db=DailyFlowDatabase.open(context,name); repo=RoomTaskRepository(db); controller=WidgetController(repo,clock)
        assertTrue(controller.snapshot().tasks.single().completed); assertEquals(WidgetActionResult.STALE,controller.execute(old))
        assertEquals(WidgetActionResult.APPLIED,controller.execute(command())); db.close(); context.deleteDatabase(name); Unit
    }
    @Test fun v2MigrationPreservesMetadataRulesStatesAndExceptions() = runBlocking {
        val name="widget-migration-v2.db"; context.deleteDatabase(name)
        val resource=javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/2.json")!!
        val schema=JSONObject(resource.bufferedReader().use { it.readText() }).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) { val e=entities.getJSONObject(i); val table=e.getString("tableName")
                old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",table)); val indices=e.getJSONArray("indices")
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))
            }
            val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,sortOrder,createdAt,updatedAt,scheduledMinute,priority) VALUES('old','旧任务','备注',?,0,1,1,600,3)",arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO recurrence_rules VALUES('old','DAILY',1,0,NULL)")
            old.execSQL("INSERT INTO task_day_states VALUES('old',?,1,1)",arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO task_exceptions(taskId,date,deleted,titleOverride,updatedAt,timeOverridden) VALUES('old',?,0,'单次例外',1,0)",arrayOf(date.plusDays(1).toEpochDay()))
            old.version=2
        }
        db.close();db=DailyFlowDatabase.open(context,name);repo=RoomTaskRepository(db);controller=WidgetController(repo,clock)
        val row=controller.snapshot().tasks.single();assertTrue(row.completed);assertEquals(600,row.scheduledMinute);assertEquals(Priority.HIGH,row.priority)
        assertEquals("单次例外",repo.observeDay(date.plusDays(1)).first().single().title)
        assertEquals(WidgetActionResult.APPLIED,controller.execute(command()));db.close();context.deleteDatabase(name);Unit
    }
    @Test fun notificationsCoverAllAppWritePathsWithoutDoubleDelegation() = runBlocking {
        var n=0;val r=UpdatingTaskRepository(repo){n++}
        val id=r.createTask(TaskDraft("一",date=date)); assertEquals(1,n)
        r.updateTask(id,date,TaskDraft("二",date=date),EditScope.SINGLE)
        r.updateContent(id,date,"三","",EditScope.SINGLE);r.setCompleted(id,date,true);r.delete(id,date,EditScope.SINGLE)
        r.create("四","",date);assertEquals(6,n)
    }
    @Test fun schedulerFailureCannotMisreportCommittedSaveAsFailed() = runBlocking {
        val r=UpdatingTaskRepository(repo){error("scheduler unavailable")};r.create("已保存","",date)
        assertEquals(1,controller.snapshot().tasks.size)
    }
    @Test fun midnightSchedulingUsesLocalZoneAndDstBoundaries() {
        val zone=ZoneId.of("America/New_York")
        val spring=Clock.fixed(LocalDate.of(2026,3,8).atStartOfDay(zone).toInstant(),zone)
        val fall=Clock.fixed(LocalDate.of(2026,11,1).atStartOfDay(zone).toInstant(),zone)
        assertEquals(23*3600000L,nextWidgetMidnight(spring)-spring.millis())
        assertEquals(25*3600000L,nextWidgetMidnight(fall)-fall.millis())
    }
    @Test fun timezoneChangeChangesDisplayedDateAndRejectsOldDate() = runBlocking {
        create(true);val old=command();val other=WidgetController(repo,Clock.fixed(clock.current,ZoneOffset.ofHours(14)))
        assertEquals(date.plusDays(1),other.snapshot().date);assertEquals(WidgetActionResult.EXPIRED,other.execute(old))
    }
    @Test fun readFailureIsNotReportedAsEmptySuccess() = runBlocking {
        val failing=object: TaskRepository by repo {
            override fun observeDay(date: LocalDate): kotlinx.coroutines.flow.Flow<List<TaskOccurrence>> = kotlinx.coroutines.flow.flow { error("read failed") }
        }
        val state=WidgetController(failing,clock).snapshot();assertTrue(state.error);assertTrue(state.tasks.isEmpty())
    }
    @Test fun writeFailureReportsFailedWithoutOptimisticCompletion() = runBlocking {
        create();val before=command()
        val failing=object: TaskRepository by repo {
            override suspend fun completeFromSnapshot(taskId: String, date: LocalDate, expectedCompleted: Boolean, expectedRevision: Long, target: Boolean, today: () -> LocalDate): CompletionResult = error("write failed")
        }
        assertEquals(WidgetActionResult.FAILED,WidgetController(failing,clock).execute(before))
        assertFalse(controller.snapshot().tasks.single().completed);assertEquals(0,db.taskDao().dayStateCount())
    }
    private class MutableClock(var current: Instant): Clock() {
        override fun getZone(): ZoneId=ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock=Clock.fixed(current,zone)
        override fun instant(): Instant=current
    }
}
