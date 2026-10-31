package com.dailyflow.app.data
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OrganizationPersistenceTest {
 private val ctx get()=ApplicationProvider.getApplicationContext<Context>()
 private val day=LocalDate.of(2026,10,29)
 private lateinit var db:DailyFlowDatabase
 private lateinit var repo:RoomTaskRepository
 @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ctx,DailyFlowDatabase::class.java).build();repo=RoomTaskRepository(db)}
 @After fun close(){db.close()}
 private suspend fun rows(d:LocalDate=day)=repo.observeDay(d).first()
 private suspend fun add(title:String,p:Priority=Priority.NORMAL,rule:RecurrenceRule?=null)=repo.createTask(TaskDraft(title,date=day,priority=p,rule=rule))
 @Test fun priorityAndCreationComeFromPersistentRows()=runBlocking{val id=add("default");val t=db.taskDao().task(id)!!;assertEquals(Priority.NORMAL,rows().single().priority);assertEquals(t.createdAt,rows().single().createdAt);repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SINGLE)!!.copy(priority=Priority.HIGH),EditScope.SINGLE);assertEquals(Priority.HIGH,db.taskDao().task(id)!!.priority)}
 @Test fun singlePriorityDoesNotEscapeItsDate()=runBlocking{val id=add("series",Priority.LOW,RecurrenceRule(Frequency.DAILY));repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SINGLE)!!.copy(priority=Priority.HIGH),EditScope.SINGLE);assertEquals(Priority.HIGH,rows().single().priority);assertEquals(Priority.LOW,rows(day.plusDays(1)).single().priority)}
 @Test fun seriesPriorityPreservesExplicitException()=runBlocking{val id=add("series",rule=RecurrenceRule(Frequency.DAILY));repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SINGLE)!!.copy(priority=Priority.LOW),EditScope.SINGLE);repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SERIES)!!.copy(priority=Priority.HIGH),EditScope.SERIES);assertEquals(Priority.LOW,rows().single().priority);assertEquals(Priority.HIGH,rows(day.plusDays(1)).single().priority)}
 @Test fun manualMovePreservesCompletionAndWidgetSnapshot()=runBlocking{val a=add("a",rule=RecurrenceRule(Frequency.DAILY));val b=add("b",rule=RecurrenceRule(Frequency.DAILY));val original=rows();assertTrue(repo.moveTask(a,b,original[0].sortOrder,original[1].sortOrder));assertEquals(listOf(b,a),rows().map{it.taskId});assertEquals(listOf(b,a),rows(day.plusDays(1)).map{it.taskId});assertEquals(CompletionResult.APPLIED,repo.completeFromSnapshot(a,day,false,original[0].stateRevision,true){day});assertTrue(rows().single{it.taskId==a}.completed);assertFalse(rows(day.plusDays(1)).single{it.taskId==a}.completed)}
 @Test fun staleRepeatCannotInvertMove()=runBlocking{val a=add("a");val b=add("b");val before=rows();repo.moveTask(a,b,before[0].sortOrder,before[1].sortOrder);try{repo.moveTask(a,b,before[0].sortOrder,before[1].sortOrder);fail()}catch(_:IllegalArgumentException){};assertEquals(listOf(b,a),rows().map{it.taskId})}
 @Test fun selfAndMissingMovesAreNoOps()=runBlocking{val a=add("a");val before=db.taskDao().task(a);assertFalse(repo.moveTask(a,a,0,0));assertFalse(repo.moveTask(a,"missing",0,0));assertEquals(before,db.taskDao().task(a))}
 @Test fun legacyTiesAndOverflowAreSafe()=runBlocking{db.taskDao().insertTask(TaskEntity("a","a","",day,createdAt=1,updatedAt=1));db.taskDao().insertTask(TaskEntity("b","b","",day,createdAt=1,updatedAt=1));repo.moveTask("a","b",0,0);assertEquals(listOf("b","a"),rows().map{it.taskId});db.taskDao().updateSortOrder("a",Long.MAX_VALUE);val c=add("c");assertEquals(c,rows().last().taskId);assertTrue(rows().all{it.sortOrder>=0})}
 @Test fun deletedExceptionsStayOutOfSearch()=runBlocking{val id=add("search",rule=RecurrenceRule(Frequency.DAILY));repo.delete(id,day,EditScope.SINGLE);assertTrue(organizeTasks(rows(),TaskListOptions(query="search")).isEmpty());assertEquals(1,organizeTasks(rows(day.plusDays(1)),TaskListOptions(query="search")).size)}
 @Test fun refreshFiresOnlyAfterSuccessfulMove()=runBlocking{val a=add("a");val b=add("b");var notifications=0;val updating=UpdatingTaskRepository(repo){notifications++};assertFalse(updating.moveTask(a,a,0,0));assertEquals(0,notifications);assertTrue(updating.moveTask(a,b,0,1));assertEquals(1,notifications)}
 private suspend fun upgrade(version:Int){
  val name="organization-upgrade-$version.db"
  // Build an actual historical SQLite file from its frozen Room schema, not a new current Room DB.
  val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/$version.json")!!.bufferedReader().use{it.readText()}).getJSONObject("database")
  ctx.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->
   val entities=schema.getJSONArray("entities");for(i in 0 until entities.length()){val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("$"+"{TABLE_NAME}",table));val indices=e.getJSONArray("indices");for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))}
   val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
   old.execSQL("INSERT INTO tasks(id,title,note,startDate,sortOrder,createdAt,updatedAt) VALUES('old','old','note',?,0,1,1)",arrayOf(day.toEpochDay()))
   old.execSQL("INSERT INTO recurrence_rules VALUES('old','DAILY',1,0,NULL)")
   old.execSQL("INSERT INTO task_day_states VALUES('old',?,1,1)",arrayOf(day.toEpochDay()))
   old.execSQL("INSERT INTO task_exceptions(taskId,date,deleted,titleOverride,noteOverride,updatedAt) VALUES('old',?,0,'override','old note',1)",arrayOf(day.plusDays(1).toEpochDay()))
   if(version>=2)old.execSQL("UPDATE tasks SET priority=3")
   if(version>=4)old.execSQL("INSERT INTO recurrence_versions VALUES('old',?,'DAILY',1,0,NULL)",arrayOf(day.toEpochDay()))
   old.version=version
  }
  db.close();db=DailyFlowDatabase.open(ctx,name);repo=RoomTaskRepository(db)
  assertTrue(rows().single().completed);assertEquals(if(version==1)Priority.NORMAL else Priority.HIGH,rows().single().priority)
  assertEquals("override",rows(day.plusDays(1)).single().title);assertEquals(1,db.taskDao().dayStateCount());assertEquals(1,db.taskDao().exceptionCount())
  assertEquals(2,repo.observeHistory(day,day.plusDays(1)).first().size)
  val second=add("second");repo.moveTask("old",second,0,1)
  repo.updateTask("old",day,repo.loadDraft("old",day,EditScope.SINGLE)!!.copy(priority=Priority.LOW),EditScope.SINGLE)
  db.close();db=DailyFlowDatabase.open(ctx,name);repo=RoomTaskRepository(db)
  assertEquals(listOf(second,"old"),rows().map{it.taskId});assertEquals(Priority.LOW,rows().single{it.taskId=="old"}.priority);assertTrue(rows().single{it.taskId=="old"}.completed)
  assertEquals("override",rows(day.plusDays(1)).single().title)
 }
 @Test fun v1FileMigratesWithData()=runBlocking{upgrade(1)}
 @Test fun v2FileMigratesWithData()=runBlocking{upgrade(2)}
 @Test fun v3FileMigratesWithData()=runBlocking{upgrade(3)}
 @Test fun v4FileReopensWithoutMigrationAndKeepsData()=runBlocking{upgrade(4)}
}
