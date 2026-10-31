package com.dailyflow.app.reminders

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.data.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderDatabaseTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = LocalDate.of(2026, 10, 30)
    private lateinit var db: DailyFlowDatabase
    @Before fun start() { db = Room.inMemoryDatabaseBuilder(ctx, DailyFlowDatabase::class.java).build() }
    @After fun close() { db.close() }
    private fun dump(sqlite: androidx.sqlite.db.SupportSQLiteDatabase, table: String): List<List<String?>> =
        sqlite.query("SELECT * FROM $table").use { c -> buildList<List<String?>> { while (c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } }

    @Test fun populatedV4FileUpgradesWithoutLosingAnyOldColumn() = runBlocking {
        val name = "reminder-v4-upgrade.db"
        val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/4.json")!!.bufferedReader().use { it.readText() }).getJSONObject("database")
        val before = mutableMapOf<String,List<List<String?>>>()
        ctx.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("$"+"{TABLE_NAME}",table))
                val indices=entity.getJSONArray("indices");for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO categories VALUES('cat','本地分类')")
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,categoryId,sortOrder,createdAt,updatedAt,scheduledMinute,priority,stateRevision) VALUES('a','旧任务','旧备注',?,'cat',42,10,11,480,3,7)",arrayOf(day.toEpochDay()))
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,sortOrder,createdAt,updatedAt,priority) VALUES('b','repeat','note',?,-7,12,13,1)",arrayOf(day.toEpochDay()))
            old.execSQL("INSERT INTO recurrence_rules VALUES('b','DAILY',1,0,NULL)")
            old.execSQL("INSERT INTO recurrence_versions VALUES('b',?,'DAILY',2,0,NULL)",arrayOf(day.plusDays(4).toEpochDay()))
            old.execSQL("INSERT INTO task_day_states VALUES('b',?,1,20)",arrayOf(day.toEpochDay()))
            old.execSQL("INSERT INTO task_exceptions(taskId,date,deleted,titleOverride,noteOverride,updatedAt,timeOverridden,priorityOverride) VALUES('b',?,0,'仅当天','覆盖备注',30,0,2)",arrayOf(day.plusDays(1).toEpochDay()))
            for(i in 0 until entities.length()) {
                val table=entities.getJSONObject(i).getString("tableName")
                before[table]=old.rawQuery("SELECT * FROM $table",null).use{c->buildList<List<String?>>{while(c.moveToNext())add((0 until c.columnCount).map{if(c.isNull(it))null else c.getString(it)})}}
            }
            old.version=4
        }
        db.close();db=DailyFlowDatabase.open(ctx,name)
        assertEquals(5,db.openHelper.writableDatabase.version)
        before.forEach{(table,rows)->assertEquals(table,rows,dump(db.openHelper.readableDatabase,table))}
        assertTrue(db.reminderDao().rules().isEmpty())
        assertTrue(db.reminderDao().overrides().isEmpty())
        val repo=RoomTaskRepository(db)
        assertEquals(2,repo.observeHistory(day,day.plusDays(1)).first().count { it.taskId=="b" })
        db.reminderDao().saveRule(ReminderRuleEntity("b",true,480,0))
        db.reminderDao().saveOverride(ReminderOverrideEntity("b",day.plusDays(1),true,600,0))
        db.close();db=DailyFlowDatabase.open(ctx,name)
        assertEquals(480,db.reminderDao().rule("b")!!.minute)
        assertEquals(600,db.reminderDao().override("b",day.plusDays(1))!!.minute)
        before.forEach{(table,rows)->assertEquals(table,rows,dump(db.openHelper.readableDatabase,table))}
    }

    @Test fun settingsAndSingleOffArePersistedSeparately()=runBlocking {
        db.taskDao().insertTask(TaskEntity("a","a","",day,createdAt=1,updatedAt=1))
        db.reminderDao().saveRule(ReminderRuleEntity("a",true,480,0))
        db.reminderDao().saveOverride(ReminderOverrideEntity("a",day,false,600,0))
        assertTrue(db.reminderDao().rule("a")!!.enabled)
        assertFalse(db.reminderDao().override("a",day)!!.enabled)
    }
    @Test fun taskDeleteCascadesConfigurationButKeepsCancellationLedger()=runBlocking {
        db.taskDao().insertTask(TaskEntity("a","a","",day,createdAt=1,updatedAt=1))
        db.reminderDao().saveRule(ReminderRuleEntity("a",true,480,0))
        db.reminderDao().saveOverride(ReminderOverrideEntity("a",day,false,600,0))
        db.reminderDao().saveDelivery(ReminderDeliveryEntity("a",day,1,"DELIVERED"))
        db.reminderDao().saveAlarm(ReminderAlarmEntity(taskId="a",date=day,dueAt=5,token="old"))
        db.taskDao().deleteTask("a")
        assertTrue(db.reminderDao().rules().isEmpty());assertTrue(db.reminderDao().overrides().isEmpty())
        assertEquals(1,db.reminderDao().deliveries().size);assertNotNull(db.reminderDao().alarm())
    }
    @Test fun missingTaskConfigurationFailsForeignKeyCheck()=runBlocking {
        try{db.reminderDao().saveRule(ReminderRuleEntity("missing",true,480,0));fail("foreign key")}
        catch(_:android.database.sqlite.SQLiteConstraintException){}
    }
    @Test fun onePersistentAlarmSlotReplacesPreviousCursor()=runBlocking {
        db.reminderDao().saveAlarm(ReminderAlarmEntity(taskId="a",date=day,dueAt=5,token="one"))
        db.reminderDao().saveAlarm(ReminderAlarmEntity(taskId="b",date=day,dueAt=6,token="two"))
        assertEquals("b",db.reminderDao().alarm()!!.taskId)
        assertEquals(1,dump(db.openHelper.readableDatabase,"reminder_alarm").size)
    }
    @Test fun deliveryIdentityUsesTaskAndDateNotTitle()=runBlocking {
        for(id in listOf("a","b"))db.reminderDao().saveDelivery(ReminderDeliveryEntity(id,day,1,"DELIVERED"))
        db.reminderDao().saveDelivery(ReminderDeliveryEntity("a",day.plusDays(1),2,"DELIVERED"))
        assertEquals(3,db.reminderDao().deliveries().size)
    }
}
