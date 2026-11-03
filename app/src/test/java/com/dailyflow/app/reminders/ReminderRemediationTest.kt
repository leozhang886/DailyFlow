package com.dailyflow.app.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.reminderSummary
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderRemediationTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val day=LocalDate.of(2026,10,30)
    private val clock=MutableReminderClock(Instant.parse("2026-10-30T07:00:00Z"))
    private val scheduler=RecordingScheduler()
    private val notifier=RecordingNotifier()
    private lateinit var db:DailyFlowDatabase
    private lateinit var engine:ReminderEngine
    private lateinit var repo:TaskRepository
    @Before fun start(){db=Room.inMemoryDatabaseBuilder(context,DailyFlowDatabase::class.java).build();wire(notifier)}
    @After fun close(){db.close()}
    private fun wire(port:ReminderNotifier){engine=ReminderEngine(db,scheduler,port,clock,{ZoneOffset.UTC});repo=ReminderAwareRepository(RoomTaskRepository(db,clock),engine)}
    private suspend fun add(title:String="pending")=repo.createTask(TaskDraft(title,date=day,reminder=ReminderSetting(true,480)))
    private suspend fun fire(alarm:ReminderAlarmEntity){engine.fire(alarm.taskId,alarm.date,alarm.token)}
    private suspend fun edit(id:String,setting:ReminderSetting){repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SINGLE)!!.copy(reminder=setting,reminderEdited=true),EditScope.SINGLE)}
    private fun ackFailure(){db.openHelper.writableDatabase.execSQL("CREATE TRIGGER remediation_ack_failure BEFORE UPDATE OF status ON reminder_deliveries WHEN NEW.status='DELIVERED' BEGIN SELECT RAISE(ABORT,'synthetic ack failure'); END")}

    @Test fun repeatedRepairKeepsExactAlarmTokenAndRearmsWithoutPosting()=runBlocking {
        add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.parse("2026-10-30T08:10:00Z")
        repeat(3){engine.reconcile();assertEquals(old.token,db.reminderDao().alarm()!!.token)}
        assertEquals(clock.millis()+60_000,scheduler.active.values.single());assertTrue(notifier.posted.isEmpty())
        repeat(2){fire(old)};assertEquals(1,notifier.posted.size)
    }
    @Test fun missingCursorDoesNotScanAndReplayHistoricalReminders()=runBlocking {
        add();db.reminderDao().clearAlarm();scheduler.active.clear()
        clock.now=Instant.parse("2026-10-30T08:10:00Z");engine.reconcile()
        assertTrue(notifier.posted.isEmpty());assertEquals(ReminderEngine.MAINTENANCE,db.reminderDao().alarm()!!.taskId)
    }
    @Test fun overdueRearmFailureKeepsOriginalIdentityAndHealsOnce()=runBlocking {
        add();val old=db.reminderDao().alarm()!!;scheduler.fail=true
        clock.now=Instant.parse("2026-10-30T08:10:00Z");engine.reconcile()
        assertEquals(old.token,db.reminderDao().alarm()!!.token);assertEquals("ERROR",db.reminderDao().alarm()!!.state)
        scheduler.fail=false;repeat(2){engine.reconcile()};fire(old)
        assertEquals(1,notifier.posted.size)
    }
    @Test fun completionBeforeLateCallbackPreventsDelivery()=runBlocking {
        val id=add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.parse("2026-10-30T08:10:00Z");repo.setCompleted(id,day,true);fire(old)
        assertTrue(notifier.posted.isEmpty())
    }
    @Test fun disablingBeforeLateCallbackPreventsDelivery()=runBlocking {
        val id=add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.parse("2026-10-30T08:10:00Z");edit(id,ReminderSetting(false));fire(old)
        assertTrue(notifier.posted.isEmpty());assertNull(db.reminderDao().alarm())
    }
    @Test fun reschedulingDueTaskIntoFutureInvalidatesOldCallback()=runBlocking {
        val id=add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.parse("2026-10-30T08:10:00Z");edit(id,ReminderSetting(true,600));fire(old)
        assertTrue(notifier.posted.isEmpty());assertNotEquals(old.token,db.reminderDao().alarm()!!.token)
        assertEquals(Instant.parse("2026-10-30T10:00:00Z").toEpochMilli(),db.reminderDao().alarm()!!.dueAt)
    }
    @Test fun deletingCursorOwnerDoesNotDiscardOtherDueTasksInBatch()=runBlocking {
        val a=add("a");val b=add("b");val old=db.reminderDao().alarm()!!
        val owner=old.taskId;val survivor=if(owner==a)b else a
        clock.now=Instant.parse("2026-10-30T08:10:00Z");repo.delete(owner,day,EditScope.SINGLE);fire(old)
        assertEquals(listOf(survivor),notifier.posted.map{it.key.taskId})
    }
    @Test fun completingCursorOwnerDoesNotDiscardOtherDueTasksInBatch()=runBlocking {
        val a=add("a");val b=add("b");val old=db.reminderDao().alarm()!!
        val survivor=if(old.taskId==a)b else a
        clock.now=Instant.parse("2026-10-30T08:10:00Z");repo.setCompleted(old.taskId,day,true);fire(old)
        assertEquals(listOf(survivor),notifier.posted.map{it.key.taskId})
    }
    @Test fun exactTwentyFourHourBoundaryStillAllowsKnownPendingAlarm()=runBlocking {
        add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.ofEpochMilli(old.dueAt+ReminderEngine.LATE_WINDOW);engine.reconcile()
        assertEquals(clock.millis(),scheduler.active.values.single());fire(old)
        assertEquals(1,notifier.posted.size)
    }
    @Test fun oneMillisecondBeyondWindowDropsCursorAndDoesNotDeliver()=runBlocking {
        add();val old=db.reminderDao().alarm()!!
        clock.now=Instant.ofEpochMilli(old.dueAt+ReminderEngine.LATE_WINDOW+1);engine.reconcile();fire(old)
        assertTrue(notifier.posted.isEmpty());assertNotEquals(old.token,db.reminderDao().alarm()!!.token)
    }
    @Test fun failedPostDoesNotRetryAfterWindowExpires()=runBlocking {
        add();val old=db.reminderDao().alarm()!!;notifier.fail=true
        clock.now=Instant.ofEpochMilli(old.dueAt);fire(old)
        val retry=db.reminderDao().alarm()!!;assertEquals("FAILED",db.reminderDao().deliveries().single().status)
        notifier.fail=false;clock.now=Instant.ofEpochMilli(old.dueAt+ReminderEngine.LATE_WINDOW+1)
        engine.reconcile();fire(retry);assertTrue(notifier.posted.isEmpty())
    }
    @Test fun retrySummaryMatchesEngineWindowBoundary() {
        val task=TaskOccurrence("a",day,"task","",false,false,false,reminder=ReminderSetting(true,480),reminderDeliveryStatus="FAILED")
        val due=Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
        assertTrue(reminderSummary(task,Instant.ofEpochMilli(due+ReminderEngine.LATE_WINDOW),ZoneOffset.UTC).contains("等待重试"))
        val expired=reminderSummary(task,Instant.ofEpochMilli(due+ReminderEngine.LATE_WINDOW+1),ZoneOffset.UTC)
        assertTrue(expired.contains("过期"));assertFalse(expired.contains("等待重试"))
    }
    @Test fun ackFailureCannotReplayAfterRealDatabaseReopenAndDismissal()=runBlocking {
        db.close();val name="remediation-ack-reopen.db";context.deleteDatabase(name)
        db=DailyFlowDatabase.open(context,name);val real=AndroidReminderNotifier(context);wire(real)
        val id=add();val old=db.reminderDao().alarm()!!;ackFailure()
        clock.now=Instant.ofEpochMilli(old.dueAt);fire(old)
        assertEquals("CLAIMED",db.reminderDao().deliveries().single().status)
        val manager=context.getSystemService(NotificationManager::class.java)
        assertEquals(1,shadowOf(manager).size());manager.cancel(ReminderKey(id,day).tag,0)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER remediation_ack_failure")
        db.close();db=DailyFlowDatabase.open(context,name);wire(real)
        clock.now=clock.now.plusSeconds(120);repeat(2){engine.reconcile()};fire(old)
        assertEquals(0,shadowOf(manager).size());assertEquals("CLAIMED",db.reminderDao().deliveries().single().status)
    }
    @Test fun ackFailureStillAllowsOtherTasksInTheSameBatch()=runBlocking {
        val real=AndroidReminderNotifier(context);wire(real);add("a");add("b");ackFailure()
        clock.now=Instant.parse("2026-10-30T08:00:00Z");fire(db.reminderDao().alarm()!!)
        assertEquals(2,shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        assertEquals(listOf("CLAIMED","CLAIMED"),db.reminderDao().deliveries().map{it.status})
    }
    @Test fun completingAckFailedTaskCancelsAcceptedNotification()=runBlocking {
        val real=AndroidReminderNotifier(context);wire(real);val id=add();ackFailure()
        clock.now=Instant.parse("2026-10-30T08:00:00Z");fire(db.reminderDao().alarm()!!);repo.setCompleted(id,day,true)
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        assertEquals("CANCELED",db.reminderDao().deliveries().single().status)
    }
    @Test fun disablingAckFailedTaskCancelsAcceptedNotification()=runBlocking {
        val real=AndroidReminderNotifier(context);wire(real);val id=add();ackFailure()
        clock.now=Instant.parse("2026-10-30T08:00:00Z");fire(db.reminderDao().alarm()!!);edit(id,ReminderSetting(false))
        assertEquals(0,shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        assertEquals("CANCELED",db.reminderDao().deliveries().single().status)
    }
    @Test fun legacyFailedRecordDoesNotPreventNotificationCleanup()=runBlocking {
        val id=add();clock.now=Instant.parse("2026-10-30T08:00:00Z");fire(db.reminderDao().alarm()!!)
        db.reminderDao().saveDelivery(ReminderDeliveryEntity(id,day,clock.millis(),"FAILED"))
        repo.delete(id,day,EditScope.SINGLE)
        assertTrue(notifier.visible.isEmpty());assertEquals("CANCELED",db.reminderDao().deliveries().single().status)
    }
}
