package com.dailyflow.app.reminders

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.data.*
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

class MutableReminderClock(var now: Instant, private val clockZone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone() = clockZone
    override fun withZone(zone: ZoneId): Clock = MutableReminderClock(now, zone)
    override fun instant() = now
}
class RecordingScheduler : ReminderScheduler {
    val active = mutableMapOf<ReminderKey,Long>()
    var mode = "EXACT"
    var fail = false
    override fun schedule(alarm: ReminderAlarmEntity, atMillis: Long): String {
        if(fail)throw IllegalStateException("fake scheduling failure")
        active[ReminderKey(alarm.taskId,alarm.date)] = atMillis
        return mode
    }
    override fun cancel(alarm: ReminderAlarmEntity) { active.remove(ReminderKey(alarm.taskId,alarm.date)) }
}
class RecordingNotifier : ReminderNotifier {
    var permission = true
    var fail = false
    val posted = mutableListOf<ReminderPlan>()
    val visible = mutableMapOf<ReminderKey,ReminderPlan>()
    override fun allowed() = permission
    override fun post(plan: ReminderPlan) {
        if(fail)throw IllegalStateException("fake notification failure")
        posted.add(plan);visible[plan.key]=plan
    }
    override fun cancel(key: ReminderKey) { visible.remove(key) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReminderFlowTest {
    private val day = LocalDate.of(2026,10,30)
    private val clock = MutableReminderClock(Instant.parse("2026-10-30T07:00:00Z"))
    private var currentZone: ZoneId = ZoneOffset.UTC
    private lateinit var db: DailyFlowDatabase
    private lateinit var engine: ReminderEngine
    private lateinit var repo: TaskRepository
    private val scheduler = RecordingScheduler()
    private val notifications = RecordingNotifier()
    @Before fun start() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),DailyFlowDatabase::class.java).build()
        engine=ReminderEngine(db,scheduler,notifications,clock,{currentZone})
        repo=ReminderAwareRepository(RoomTaskRepository(db,clock),engine)
    }
    @After fun close(){db.close()}
    private suspend fun add(title:String="task",rule:RecurrenceRule?=null,minute:Int=480,offset:Int=0):String =
        repo.createTask(TaskDraft(title,date=day,rule=rule,reminder=ReminderSetting(true,minute,offset)))
    private suspend fun fire(){val a=db.reminderDao().alarm()!!;engine.fire(a.taskId,a.date,a.token)}
    private suspend fun edit(id:String,date:LocalDate=day,scope:EditScope=EditScope.SINGLE,setting:ReminderSetting?) {
        val d=repo.loadDraft(id,date,scope)!!
        repo.updateTask(id,date,d.copy(reminder=setting,reminderEdited=true),scope)
    }
    @Test fun ordinaryReminderCommitsBeforeRegisteringAndReloads()=runBlocking {
        val id=add();assertEquals(ReminderSetting(true,480),repo.loadDraft(id,day,EditScope.SINGLE)!!.reminder)
        assertEquals(id,db.reminderDao().alarm()!!.taskId);assertEquals(1,scheduler.active.size)
    }
    @Test fun defaultOffCreatesNoReminderOrAlarm()=runBlocking {
        repo.create("no reminder","",day,null)
        assertTrue(db.reminderDao().rules().isEmpty());assertTrue(scheduler.active.isEmpty())
    }
    @Test fun changeTimeReplacesOldAlarm()=runBlocking {
        val id=add();edit(id,setting=ReminderSetting(true,600))
        assertEquals(Instant.parse("2026-10-30T10:00:00Z").toEpochMilli(),scheduler.active.values.single())
        assertEquals(600,db.reminderDao().rule(id)!!.minute)
    }
    @Test fun closeReminderCancelsRegisteredAlarm()=runBlocking {
        val id=add();edit(id,setting=ReminderSetting(false))
        assertTrue(scheduler.active.isEmpty());assertNull(db.reminderDao().alarm())
    }
    @Test fun deletingTaskCancelsWithoutLeavingOrphanConfiguration()=runBlocking {
        val id=add();repo.delete(id,day,EditScope.SINGLE)
        assertTrue(scheduler.active.isEmpty());assertTrue(db.reminderDao().rules().isEmpty())
    }
    @Test fun completeAndUndoRestoreOnlyFutureReminder()=runBlocking {
        val id=add();repo.setCompleted(id,day,true);assertTrue(scheduler.active.values.none{it==Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()})
        repo.setCompleted(id,day,false);assertEquals(id,db.reminderDao().alarm()!!.taskId)
    }
    @Test fun completingDeliveredTaskCancelsItsNotification()=runBlocking {
        val id=add();clock.now=Instant.parse("2026-10-30T08:00:00Z");fire();assertEquals(1,notifications.visible.size)
        repo.setCompleted(id,day,true);assertTrue(notifications.visible.isEmpty())
    }
    @Test fun ordinaryCustomReminderDateIsIndependentOfTaskDate()=runBlocking {
        val id=add(offset=2);assertEquals(2,repo.loadDraft(id,day,EditScope.SINGLE)!!.reminder!!.offsetDays)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(),db.reminderDao().snapshot().next(clock.now,currentZone)!!.dueAt)
    }
    @Test fun seriesUsesEveryEffectiveOccurrence()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));clock.now=Instant.parse("2026-10-30T08:00:00Z");fire()
        assertEquals(1,notifications.posted.size);assertEquals(day.plusDays(1),db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
        assertEquals(id,notifications.posted.single().key.taskId)
    }
    @Test fun singleOverrideDoesNotChangeSeriesOrTomorrow()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));edit(id,setting=ReminderSetting(true,600))
        assertEquals(480,repo.loadDraft(id,day.plusDays(1),EditScope.SINGLE)!!.reminder!!.minute)
        assertEquals(600,repo.loadDraft(id,day,EditScope.SINGLE)!!.reminder!!.minute)
    }
    @Test fun singleOffKeepsFutureSeriesEnabled()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));edit(id,setting=ReminderSetting(false))
        assertTrue(db.reminderDao().rule(id)!!.enabled)
        assertEquals(day.plusDays(1),db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
    }
    @Test fun closeEntireSeriesAlsoClearsSingleReminderOverrides()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));edit(id,date=day.plusDays(1),setting=ReminderSetting(true,600))
        edit(id,scope=EditScope.SERIES,setting=ReminderSetting(false))
        assertTrue(db.reminderDao().overrides().isEmpty());assertTrue(scheduler.active.isEmpty())
    }
    @Test fun deletionAndRecoveryOfSingleExceptionResynchronize()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));repo.delete(id,day,EditScope.SINGLE)
        assertEquals(day.plusDays(1),db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
        repo.recoverException(id,day,true);assertEquals(day,db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
    }
    @Test fun recurrenceChangeRecomputesFutureInstances()=runBlocking {
        val id=add(rule=RecurrenceRule(Frequency.DAILY));repo.changeRecurrence(id,day,RecurrenceRule(Frequency.WEEKLY,weekdays=setOf(day.plusDays(1).dayOfWeek)),0){day}
        assertEquals(day.plusDays(1),db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
    }
    @Test fun pastReminderIsRejectedWithoutCreatingTask()=runBlocking {
        try{add(minute=360);fail("past") }catch(_:IllegalArgumentException){}
        assertEquals(0,db.taskDao().taskCount());assertTrue(db.reminderDao().rules().isEmpty())
    }
    @Test fun titleAndNoteEditsDoNotRemoveOrResetReminder()=runBlocking {
        val id=add();clock.now=Instant.parse("2026-10-30T09:00:00Z")
        repo.updateTask(id,day,repo.loadDraft(id,day,EditScope.SINGLE)!!.copy(title="renamed",note="new"),EditScope.SINGLE)
        assertEquals(480,db.reminderDao().rule(id)!!.minute)
        assertTrue(notifications.posted.isEmpty())
    }
    @Test fun notificationPermissionRefusalPreservesSettingsAndBlocksAlarms()=runBlocking {
        notifications.permission=false;val id=add()
        assertTrue(db.reminderDao().rule(id)!!.enabled);assertTrue(scheduler.active.isEmpty())
        assertEquals("NOTIFICATIONS_BLOCKED",db.reminderDao().alarm()!!.state)
        notifications.permission=true;engine.reconcile();assertEquals(id,db.reminderDao().alarm()!!.taskId);assertEquals(1,scheduler.active.size)
    }
    @Test fun exactPermissionFallbackIsRecordedHonestly()=runBlocking {
        scheduler.mode="INEXACT";add();assertEquals("INEXACT",db.reminderDao().alarm()!!.state)
        assertTrue(db.reminderDao().alarm()!!.detail.contains("延迟"))
    }
    @Test fun scheduleFailureDoesNotUndoCommittedTaskAndCanHeal()=runBlocking {
        scheduler.fail=true;val id=add();assertNotNull(db.taskDao().task(id));assertEquals("ERROR",db.reminderDao().alarm()!!.state)
        scheduler.fail=false;engine.reconcile();assertEquals("EXACT",db.reminderDao().alarm()!!.state)
    }
    @Test fun duplicateReconcileAndDuplicateBroadcastDoNotDuplicateNotifications()=runBlocking {
        add();repeat(4){engine.reconcile()};assertEquals(1,scheduler.active.size)
        val a=db.reminderDao().alarm()!!;clock.now=Instant.parse("2026-10-30T08:00:00Z")
        repeat(3){engine.fire(a.taskId,a.date,a.token)}
        assertEquals(1,notifications.posted.size);assertEquals(1,db.reminderDao().deliveries().size)
    }
    @Test fun staleTokenAfterTimeEditCannotFire()=runBlocking {
        val id=add();val old=db.reminderDao().alarm()!!;edit(id,setting=ReminderSetting(true,600))
        clock.now=Instant.parse("2026-10-30T08:00:00Z");engine.fire(old.taskId,old.date,old.token)
        assertTrue(notifications.posted.isEmpty());assertEquals(Instant.parse("2026-10-30T10:00:00Z").toEpochMilli(),db.reminderDao().alarm()!!.dueAt)
    }
    @Test fun sameTitleAndSameTimeHaveDistinctIdentitiesAndOneWakeup()=runBlocking {
        val a=add("same");val b=add("same");assertNotEquals(a,b);assertEquals(1,scheduler.active.size)
        clock.now=Instant.parse("2026-10-30T08:00:00Z");fire()
        assertEquals(setOf(a,b),notifications.posted.map{it.key.taskId}.toSet());assertEquals(2,notifications.visible.size)
    }
    @Test fun restartReschedulesFutureWithoutReplayingDeliveredInstances()=runBlocking {
        add(rule=RecurrenceRule(Frequency.DAILY));clock.now=Instant.parse("2026-10-30T08:00:00Z");fire();scheduler.active.clear()
        val restarted=ReminderEngine(db,scheduler,notifications,clock,{currentZone});restarted.reconcile()
        assertEquals(1,scheduler.active.size);assertEquals(1,notifications.posted.size)
        assertEquals(day.plusDays(1),db.reminderDao().snapshot().next(clock.now,currentZone)!!.key.date)
    }
    @Test fun bootAfterMissedTimeDoesNotImmediatelyNotifyOldReminder()=runBlocking {
        add();clock.now=Instant.parse("2026-10-30T10:00:00Z");scheduler.active.clear();engine.reconcile()
        assertTrue(notifications.posted.isEmpty());assertNull(db.reminderDao().snapshot().next(clock.now,currentZone))
    }
    @Test fun timeZoneChangeRecomputesInstantFromLocalRule()=runBlocking {
        add(rule=RecurrenceRule(Frequency.DAILY));val utc=db.reminderDao().snapshot().next(clock.now,currentZone)!!.dueAt
        currentZone=ZoneId.of("Europe/Paris");engine.reconcile()
        val paris=db.reminderDao().snapshot().next(clock.now,currentZone)!!.dueAt
        assertNotEquals(utc,paris);assertTrue(paris>clock.millis());assertTrue(scheduler.active.size==1)
    }
    @Test fun notificationFailureIsRecordedAndRetryIsIdempotent()=runBlocking {
        add();notifications.fail=true;clock.now=Instant.parse("2026-10-30T08:00:00Z");fire()
        assertEquals("FAILED",db.reminderDao().deliveries().single().status);assertEquals("NOTIFY_ERROR",db.reminderDao().alarm()!!.state)
        notifications.fail=false;engine.reconcile();engine.reconcile()
        assertEquals(1,notifications.posted.size);assertEquals("DELIVERED",db.reminderDao().deliveries().single().status)
    }
    @Test fun lateDailyMaintenanceStillDispatchesTasksDueSinceItsScheduledTime()=runBlocking {
        add(rule=RecurrenceRule(Frequency.DAILY));clock.now=Instant.parse("2026-10-30T08:00:00Z");fire()
        assertEquals(ReminderEngine.MAINTENANCE,db.reminderDao().alarm()!!.taskId)
        clock.now=Instant.parse("2026-10-31T09:00:00Z");fire()
        assertEquals(listOf(day,day.plusDays(1)),notifications.posted.map{it.key.date})
    }
    @Test fun earlyOrForgedBroadcastDoesNotPost()=runBlocking {
        add();val a=db.reminderDao().alarm()!!;engine.fire(a.taskId,a.date,"fake");engine.fire(a.taskId,a.date,a.token)
        assertTrue(notifications.posted.isEmpty())
    }
}
