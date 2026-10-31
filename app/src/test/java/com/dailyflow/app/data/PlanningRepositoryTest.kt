package com.dailyflow.app.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlanningRepositoryTest {
    private val date=LocalDate.of(2026,10,27)
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(context,DailyFlowDatabase::class.java).build();repo=RoomTaskRepository(db)}
    @After fun close(){db.close()}
    private suspend fun series(start:LocalDate=date.minusDays(7))=repo.create("系列","",start,RecurrenceRule(Frequency.DAILY))
    private suspend fun revision(id:String)=db.taskDao().task(id)!!.stateRevision
    private suspend fun change(id:String,day:LocalDate=date,rule:RecurrenceRule=RecurrenceRule(Frequency.DAILY,2))=repo.changeRecurrence(id,day,rule,revision(id)){date}
    private suspend fun rows(day:LocalDate)=repo.observeDay(day).first()
    @Test fun ruleChangePreservesPastAndUsesNewAnchor()=runBlocking{
        val id=series();change(id)
        assertEquals(1,rows(date.minusDays(1)).size);assertEquals(1,rows(date).size)
        assertTrue(rows(date.plusDays(1)).isEmpty());assertEquals(1,rows(date.plusDays(2)).size)
    }
    @Test fun dailyToWeeklyAndUntilAreApplied()=runBlocking{
        val id=series();change(id,rule=RecurrenceRule(Frequency.WEEKLY,weekdays=setOf(DayOfWeek.WEDNESDAY),untilDate=date.plusDays(1)))
        assertTrue(rows(date).isEmpty());assertEquals(1,rows(date.plusDays(1)).size);assertTrue(rows(date.plusDays(8)).isEmpty())
    }
    @Test fun twoWeekIntervalsCrossYearCorrectly()=runBlocking{
        val start=LocalDate.of(2026,12,28);val id=series(start)
        repo.changeRecurrence(id,start,RecurrenceRule(Frequency.WEEKLY,2,setOf(DayOfWeek.MONDAY)),revision(id)){date}
        assertTrue(rows(start.plusWeeks(1)).isEmpty());assertEquals(1,rows(start.plusWeeks(2)).size)
    }
    @Test fun completedAndOverriddenFutureInstancesRemainVisible()=runBlocking{
        val id=series();repo.setCompleted(id,date.plusDays(1),true)
        repo.updateContent(id,date.plusDays(3),"单次保留","",EditScope.SINGLE);change(id)
        assertTrue(rows(date.plusDays(1)).single().completed);assertEquals("单次保留",rows(date.plusDays(3)).single().title)
    }
    @Test fun pastRuleChangeRejectedWithoutWriting()=runBlocking{
        val id=series();try{change(id,date.minusDays(1));fail()}catch(_:IllegalArgumentException){}
        assertTrue(db.taskDao().versions(id).isEmpty());assertEquals(1,rows(date.plusDays(1)).size)
    }
    @Test fun staleRuleEditorRejectedAtomically()=runBlocking{
        val id=series();val revision=revision(id);repo.setCompleted(id,date,true)
        try{repo.changeRecurrence(id,date,RecurrenceRule(Frequency.DAILY,2),revision){date};fail()}catch(_:IllegalArgumentException){}
        assertTrue(db.taskDao().versions(id).isEmpty())
    }
    @Test fun newRuleReplacesOnlyVersionsOnOrAfterEffectiveDate()=runBlocking{
        val id=series();change(id);change(id,date.plusDays(5),RecurrenceRule(Frequency.DAILY,3));change(id,date.plusDays(2),RecurrenceRule(Frequency.DAILY,4))
        assertEquals(listOf(date,date.plusDays(2)),db.taskDao().versions(id).map{it.effectiveFrom})
    }
    @Test fun untilMayBeRemovedWithoutBeingClampedByBaseUntil()=runBlocking{
        val id=repo.create("结束","",date,RecurrenceRule(Frequency.DAILY,untilDate=date))
        change(id,rule=RecurrenceRule(Frequency.DAILY));assertEquals(1,rows(date.plusDays(10)).size)
    }
    @Test fun restoreSingleDeleteDoesNotRestoreLostCompletionOrOtherDates()=runBlocking{
        val id=series();repo.setCompleted(id,date,true);repo.delete(id,date,EditScope.SINGLE)
        assertTrue(rows(date).isEmpty());repo.recoverException(id,date,true)
        assertFalse(rows(date).single().completed);assertEquals(1,rows(date.plusDays(1)).size)
    }
    @Test fun resetOverrideRestoresInheritedValuesAndPreservesCompletion()=runBlocking{
        val id=series();repo.updateContent(id,date,"单次","",EditScope.SINGLE);repo.setCompleted(id,date,true)
        repo.recoverException(id,date,false);assertEquals("系列",rows(date).single().title);assertTrue(rows(date).single().completed)
    }
    @Test fun explicitRestoreCanKeepInstanceExcludedByNewRule()=runBlocking{
        val id=series();repo.delete(id,date.plusDays(1),EditScope.SINGLE);change(id)
        repo.recoverException(id,date.plusDays(1),true);assertEquals(1,rows(date.plusDays(1)).size)
    }
    @Test fun mismatchedRecoveryDoesNotWrite()=runBlocking{
        val id=series();repo.delete(id,date,EditScope.SINGLE)
        try{repo.recoverException(id,date,false);fail()}catch(_:IllegalArgumentException){}
        assertTrue(rows(date).isEmpty())
    }
    @Test fun historyCoversLeapDayAndPreservesDailyStatus()=runBlocking{
        val feb=LocalDate.of(2028,2,1);val id=series(feb);repo.setCompleted(id,LocalDate.of(2028,2,29),true)
        val history=repo.observeHistory(feb,LocalDate.of(2028,2,29)).first()
        assertEquals(29,history.size);assertEquals(1,history.count{it.completed});assertEquals(LocalDate.of(2028,2,29),history.last().date)
    }
    @Test fun oversizedHistoryQueryRejected()=runBlocking{
        try{repo.observeHistory(date,date.plusDays(31));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun oldWidgetTokenInvalidatedByRuleChangeAndRestore()=runBlocking{
        val id=series();val old=rows(date).single();change(id)
        assertEquals(CompletionResult.STALE,repo.completeFromSnapshot(id,date,false,old.stateRevision,true){date})
        repo.delete(id,date,EditScope.SINGLE);val before=revision(id);repo.recoverException(id,date,true);assertTrue(revision(id)>before)
    }
    @Test fun retainedCompletedInstanceAfterNewUntilStillAllowsSingleEditing()=runBlocking{
        val id=series();val future=date.plusDays(3);repo.setCompleted(id,future,true)
        change(id,rule=RecurrenceRule(Frequency.DAILY,untilDate=date))
        val draft=repo.loadDraft(id,future,EditScope.SINGLE)!!
        repo.updateTask(id,future,draft.copy(title="保留的完成实例"),EditScope.SINGLE)
        assertEquals("保留的完成实例",rows(future).single().title);assertTrue(rows(future).single().completed)
    }
    @Test fun legacyContentEditorCanEditEffectiveSingleInstance()=runBlocking{
        val id=series();change(id);val draft=repo.loadDraft(id,date,EditScope.SINGLE)!!
        assertEquals(2,draft.rule!!.interval);repo.updateTask(id,date,draft.copy(title="新单次"),EditScope.SINGLE)
        assertEquals("新单次",rows(date).single().title)
    }
    @Test fun wholeSeriesDeleteCascadesVersions()=runBlocking{
        val id=series();change(id);repo.delete(id,date,EditScope.SERIES);assertTrue(db.taskDao().versions(id).isEmpty())
    }
    @Test fun v3MigrationRetainsOldDataAndWidgetRevision()=runBlocking{
        val name="planning-v3.db";context.deleteDatabase(name)
        val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/3.json")!!.bufferedReader().use{it.readText()}).getJSONObject("database")
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",table));val indices=e.getJSONArray("indices");for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))}
            val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length())old.execSQL(queries.getString(i))
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,sortOrder,createdAt,updatedAt,priority,stateRevision) VALUES('old','旧系列','备注',?,0,1,1,3,7)",arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO recurrence_rules VALUES('old','DAILY',1,0,NULL)")
            old.execSQL("INSERT INTO task_day_states VALUES('old',?,1,1)",arrayOf(date.toEpochDay()))
            old.execSQL("INSERT INTO task_exceptions(taskId,date,deleted,titleOverride,updatedAt,timeOverridden) VALUES('old',?,0,'旧例外',1,0)",arrayOf(date.plusDays(1).toEpochDay()));old.version=3
        }
        db.close();db=DailyFlowDatabase.open(context,name);repo=RoomTaskRepository(db)
        assertTrue(rows(date).single().completed);assertEquals(7L,revision("old"));assertEquals("旧例外",rows(date.plusDays(1)).single().title)
        change("old");db.close();db=DailyFlowDatabase.open(context,name);repo=RoomTaskRepository(db)
        assertEquals(1,db.taskDao().versions("old").size);assertTrue(rows(date).single().completed);db.close();context.deleteDatabase(name);Unit
    }
}
