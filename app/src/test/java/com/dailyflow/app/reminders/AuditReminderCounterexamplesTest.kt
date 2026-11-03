package com.dailyflow.app.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.reminderSummary
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

private class AuditClock(var value:Instant):Clock(){override fun instant()=value;override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId):Clock=Clock.fixed(value,zone)}
private class AuditScheduler:ReminderScheduler{
    val pending=linkedMapOf<ReminderKey,Long>()
    val canceled=mutableListOf<ReminderKey>()
    var failing=false
    override fun schedule(alarm:ReminderAlarmEntity,atMillis:Long):String{if(failing)throw IllegalStateException("audit registration failure");pending[ReminderKey(alarm.taskId,alarm.date)]=atMillis;return "INEXACT"}
    override fun cancel(alarm:ReminderAlarmEntity){val key=ReminderKey(alarm.taskId,alarm.date);canceled.add(key);pending.remove(key)}
}
private class AuditNotifier:ReminderNotifier{
    val posts=mutableListOf<ReminderPlan>()
    override fun allowed()=true
    override fun post(plan:ReminderPlan){posts.add(plan)}
    override fun cancel(key:ReminderKey){}
}

/** Audit regressions. Original audit source and failing run are preserved in evidence.
 * The post/ack fault now retains CLAIMED; all six behavioral acceptance assertions are unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AuditReminderCounterexamplesTest{
    private val ctx get()=ApplicationProvider.getApplicationContext<Context>()
    private val day=LocalDate.of(2026,10,30)
    private val clock=AuditClock(Instant.parse("2026-10-30T07:00:00Z"))
    private val scheduler=AuditScheduler()
    private val notifier=AuditNotifier()
    private lateinit var db:DailyFlowDatabase
    private lateinit var engine:ReminderEngine
    private lateinit var repo:TaskRepository
    @Before fun start(){db=Room.inMemoryDatabaseBuilder(ctx,DailyFlowDatabase::class.java).build();wire(notifier)}
    @After fun close(){db.close()}
    private fun wire(port:ReminderNotifier){engine=ReminderEngine(db,scheduler,port,clock,{ZoneOffset.UTC});repo=ReminderAwareRepository(RoomTaskRepository(db,clock),engine)}
    private suspend fun add(rule:RecurrenceRule?=null,date:LocalDate=day)=repo.createTask(TaskDraft("audit synthetic",date=date,rule=rule,reminder=ReminderSetting(true,480)))
    private suspend fun fire(a:ReminderAlarmEntity){engine.fire(a.taskId,a.date,a.token)}
    private fun trace(name:String,vararg facts:Pair<String,Any?>){
        val dir=File("build/audit-probe-traces");dir.mkdirs()
        val json=JSONObject();facts.forEach{json.put(it.first,it.second?:JSONObject.NULL)}
        File(dir,"$name.json").writeText(json.toString(2),Charsets.UTF_8)
    }

    @Test fun delayedInexactAlarmMustSurviveRoutineRepair()=runBlocking{
        val id=add();val before=db.reminderDao().alarm()!!
        assertTrue(scheduler.pending.containsKey(ReminderKey(id,day)))
        clock.value=Instant.parse("2026-10-30T08:10:00Z");engine.reconcile()
        val after=db.reminderDao().alarm()!!
        clock.value=Instant.parse("2026-10-30T08:11:00Z");fire(before)
        trace("late-alarm-vs-repair","old_state" to before.state,"old_due" to before.dueAt,"after_task" to after.taskId,
            "after_due" to after.dueAt,"old_token_replaced" to (before.token!=after.token),"original_alarm_still_pending" to scheduler.pending.containsKey(ReminderKey(id,day)),
            "notifications_after_late_callback" to notifier.posts.size,"delivery_rows" to db.reminderDao().deliveries().size)
        assertEquals("A registered inexact alarm delayed 11 minutes must not be discarded by routine repair",1,notifier.posts.size)
    }
    @Test fun unrelatedTaskWriteMustNotDiscardAnAlreadyDueAlarm()=runBlocking{
        add();val before=db.reminderDao().alarm()!!;clock.value=Instant.parse("2026-10-30T08:01:00Z")
        repo.create("unrelated synthetic","",day,null)
        clock.value=Instant.parse("2026-10-30T08:02:00Z");fire(before)
        trace("late-alarm-vs-unrelated-write","notifications" to notifier.posts.size,"task_count" to db.taskDao().taskCount(),"new_cursor" to db.reminderDao().alarm()!!.taskId)
        assertEquals("Unrelated task edit must not silently consume a pending reminder",1,notifier.posts.size)
    }
    @Test fun schedulingFailureRecoveryMustNotForgetKnownDueReminder()=runBlocking{
        scheduler.failing=true;add();assertEquals("ERROR",db.reminderDao().alarm()!!.state)
        scheduler.failing=false;clock.value=Instant.parse("2026-10-30T08:10:00Z");engine.reconcile()
        trace("registration-recovery-after-due","notifications" to notifier.posts.size,"new_cursor" to db.reminderDao().alarm()!!.taskId,"state" to db.reminderDao().alarm()!!.state)
        assertEquals("Recovering a known failed registration within the lateness window must retain its reminder",1,notifier.posts.size)
    }
    @Test fun lateBroadcastWithoutInterveningRepairDoesDeliver_control()=runBlocking{
        add();val before=db.reminderDao().alarm()!!;clock.value=Instant.parse("2026-10-30T08:11:00Z");fire(before)
        trace("late-delivery-control","notifications" to notifier.posts.size,"delivery_status" to db.reminderDao().deliveries().single().status)
        assertEquals(1,notifier.posts.size)
    }

    private fun failDeliveryAck(){db.openHelper.writableDatabase.execSQL("CREATE TRIGGER audit_fail_ack BEFORE UPDATE OF status ON reminder_deliveries WHEN NEW.status='DELIVERED' BEGIN SELECT RAISE(ABORT,'audit acknowledgement storage failure'); END")}
    @Test fun dismissedAcceptedNotificationMustNotReappearAfterDatabaseAckFailure()=runBlocking{
        val real=AndroidReminderNotifier(ctx);wire(real);val id=add();val before=db.reminderDao().alarm()!!;failDeliveryAck()
        clock.value=Instant.parse("2026-10-30T08:00:00Z");fire(before)
        val manager=ctx.getSystemService(NotificationManager::class.java)
        assertEquals(1,shadowOf(manager).size());assertEquals("CLAIMED",db.reminderDao().deliveries().single().status)
        manager.cancel(ReminderKey(id,day).tag,0);assertEquals(0,shadowOf(manager).size())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER audit_fail_ack")
        clock.value=Instant.parse("2026-10-30T08:02:00Z");engine.reconcile()
        trace("accepted-notification-replayed","visible_after_user_dismissed_and_retry" to shadowOf(manager).size(),"ledger_after_retry" to db.reminderDao().deliveries().single().status)
        assertEquals("Already accepted and dismissed notification must not re-alert solely because its DB acknowledgement failed",0,shadowOf(manager).size())
    }
    @Test fun deletingTaskMustCancelVisibleNotificationEvenWhenItsAckFailed()=runBlocking{
        val real=AndroidReminderNotifier(ctx);wire(real);val id=add();val before=db.reminderDao().alarm()!!;failDeliveryAck()
        clock.value=Instant.parse("2026-10-30T08:00:00Z");fire(before)
        val manager=ctx.getSystemService(NotificationManager::class.java);assertEquals(1,shadowOf(manager).size())
        repo.delete(id,day,EditScope.SINGLE)
        trace("failed-ack-notification-after-delete","remaining_tasks" to db.taskDao().taskCount(),"remaining_notifications" to shadowOf(manager).size(),"ledger" to db.reminderDao().deliveries().single().status)
        assertEquals("Deleting task must cancel its visible notification, including uncertain post/ack states",0,shadowOf(manager).size())
    }
    @Test fun failedReminderBeyondRetryWindowMustNotSayWaitingForRetry(){
        val task=TaskOccurrence("audit",day,"audit","",false,false,false,reminder=ReminderSetting(true,480),reminderDeliveryStatus="FAILED")
        val text=reminderSummary(task,Instant.parse("2026-10-31T10:00:00Z"),ZoneOffset.UTC)
        trace("expired-failed-status","text" to text,"hours_after_due" to 26)
        assertTrue("After the 24-hour dispatch window, UI must identify expiration instead of an indefinite retry promise",text.contains("过期"))
    }
    @Test fun claimedCrashWindowPersistsAcrossActualDatabaseReopen_observation()=runBlocking{
        db.close();val name="audit-claimed-reopen.db";db=DailyFlowDatabase.open(ctx,name);wire(notifier)
        val id=add();db.reminderDao().saveDelivery(ReminderDeliveryEntity(id,day,Instant.parse("2026-10-30T08:00:00Z").toEpochMilli(),"CLAIMED"))
        db.close();db=DailyFlowDatabase.open(ctx,name);wire(notifier);scheduler.pending.clear()
        clock.value=Instant.parse("2026-10-30T08:01:00Z");repeat(2){engine.reconcile()}
        trace("claimed-crash-reopen","notification_count" to notifier.posts.size,"persisted_status" to db.reminderDao().deliveries().single().status)
        assertEquals("CLAIMED",db.reminderDao().deliveries().single().status);assertTrue(notifier.posts.isEmpty())
    }
    @Test fun dailyReminderCrossesLeapDayAndMonthEnd_control()=runBlocking{
        clock.value=Instant.parse("2028-02-28T07:00:00Z");add(RecurrenceRule(Frequency.DAILY),LocalDate.of(2028,2,28))
        for(time in listOf("2028-02-28T08:00:00Z","2028-02-29T08:00:00Z","2028-03-01T08:00:00Z")){
            clock.value=Instant.parse(time);fire(db.reminderDao().alarm()!!)
        }
        val dates=notifier.posts.map{it.key.date.toString()}
        trace("leap-and-month-boundary-control","notified_dates" to dates.joinToString(","))
        assertEquals(listOf("2028-02-28","2028-02-29","2028-03-01"),dates)
    }
    @Test fun invalidV4MigrationFailsWithoutDeletingOldData_control()=runBlocking{
        val name="audit-invalid-v4.db"
        val schema=JSONObject(javaClass.classLoader!!.getResourceAsStream("com.dailyflow.app.data.DailyFlowDatabase/4.json")!!.bufferedReader().use{it.readText()}).getJSONObject("database")
        ctx.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val item=entities.getJSONObject(i);val table=item.getString("tableName")
                old.execSQL(item.getString("createSql").replace("$"+"{TABLE_NAME}",table));val indices=item.getJSONArray("indices")
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("$"+"{TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("ALTER TABLE tasks ADD COLUMN audit_extra TEXT NOT NULL DEFAULT 'kept'")
            old.execSQL("INSERT INTO tasks(id,title,note,startDate,sortOrder,createdAt,updatedAt) VALUES('sentinel','migration sentinel','preserve note',0,42,1,1)")
            old.version=4
        }
        val broken=DailyFlowDatabase.open(ctx,name);var rejected=false
        try{broken.openHelper.writableDatabase}catch(_:IllegalStateException){rejected=true}finally{broken.close()}
        assertTrue("Room must reject invalid schema",rejected)
        ctx.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{old->
            assertEquals(4,old.version)
            val note=old.rawQuery("SELECT note,audit_extra FROM tasks WHERE id='sentinel'",null).use{c->assertTrue(c.moveToFirst());assertEquals("kept",c.getString(1));c.getString(0)}
            assertEquals("preserve note",note)
            val tables=old.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='reminder_rules'",null).use{it.moveToFirst();it.getInt(0)}
            assertEquals(0,tables);trace("migration-failure-control","version" to old.version,"note_preserved" to (note=="preserve note"),"new_table_count" to tables)
        }
    }
}
