package com.dailyflow.app.reminders

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Actual Android adapters through API35 Robolectric shadows, not a physical Android device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ReminderAndroidTest {
    private val ctx get()=ApplicationProvider.getApplicationContext<Application>()
    private val manager get()=ctx.getSystemService(NotificationManager::class.java)
    private val alarms get()=ctx.getSystemService(AlarmManager::class.java)
    private val day=LocalDate.of(2026,10,30)
    private lateinit var notifier:AndroidReminderNotifier
    @Before fun start(){shadowOf(ctx).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);notifier=AndroidReminderNotifier(ctx)}
    private fun cursor(id:String="a",date:LocalDate=day)=ReminderAlarmEntity(taskId=id,date=date,dueAt=System.currentTimeMillis()+60_000,token="token")
    @Test fun stableChannelIsCreatedOnlyOnce(){repeat(3){notifier.createChannel()};assertEquals(1,manager.notificationChannels.count{it.id==AndroidReminderNotifier.CHANNEL})}
    @Test fun deniedRuntimeNotificationPermissionIsNotSilentlyTreatedAsGranted(){
        shadowOf(ctx).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);assertFalse(notifier.allowed())
        try{notifier.post(ReminderPlan(ReminderKey("a",day),1,"title","note"));fail("permission") }catch(_:SecurityException){}
        assertEquals(0,shadowOf(manager).size())
    }
    @Test fun channelOffIsDetected(){shadowOf(manager).setNotificationsEnabled(false);assertFalse(notifier.allowed())}
    @Test fun exactDeniedSchedulesInexactAlarm(){
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals("INEXACT",AndroidReminderScheduler(ctx).schedule(cursor()))
        assertEquals(1,shadowOf(alarms).scheduledAlarms.size)
        assertFalse(ReminderPermissions.canExact(ctx))
    }
    @Test fun exactGrantedSchedulesExactAlarm(){
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals("EXACT",AndroidReminderScheduler(ctx).schedule(cursor()))
        assertEquals(1,shadowOf(alarms).scheduledAlarms.size)
    }
    @Test fun pendingIntentsAreImmutableDistinctAndCancelOnlyRequestedInstance(){
        val scheduler=AndroidReminderScheduler(ctx);val a=cursor();val b=cursor("b");val tomorrow=cursor("a",day.plusDays(1))
        scheduler.schedule(a);scheduler.schedule(b);scheduler.schedule(tomorrow);scheduler.schedule(a)
        assertEquals(3,shadowOf(alarms).scheduledAlarms.size)
        val intents=shadowOf(alarms).scheduledAlarms.map{shadowOf(it.operation)}
        assertTrue(intents.all{it.isImmutable});assertEquals(3,intents.map{it.savedIntent.dataString}.toSet().size)
        scheduler.cancel(a);assertEquals(2,shadowOf(alarms).scheduledAlarms.size)
        assertTrue(shadowOf(alarms).scheduledAlarms.none{ReminderIntents.alarmKey(shadowOf(it.operation).savedIntent)==ReminderKey("a",day)})
    }
    @Test fun notificationIdentityAndClickKeepExactTaskAndDate(){
        val key=ReminderKey("a",day);val plan=ReminderPlan(key,1,"same title","中文备注")
        notifier.post(plan);notifier.post(plan)
        notifier.post(plan.copy(key=ReminderKey("b",day)));assertEquals(2,shadowOf(manager).size())
        val notification=shadowOf(manager).getNotification(key.tag,0)
        assertEquals("same title",notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("中文备注",notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(key,ReminderIntents.destination(shadowOf(notification.contentIntent).savedIntent))
        assertTrue(shadowOf(notification.contentIntent).isImmutable)
        notifier.cancel(key);assertEquals(1,shadowOf(manager).size())
    }
    @Test fun malformedNavigationAndAlarmAreRejected(){
        assertNull(ReminderIntents.destination(Intent(ReminderIntents.OPEN)))
        val intent=ReminderIntents.detail(ctx,ReminderKey("a",day)).putExtra("reminderTaskId","b")
        assertNull(ReminderIntents.destination(intent));assertNull(ReminderIntents.alarmKey(intent))
    }
    @Test fun settingsIntentsSeparateNotificationAndExactAccess(){
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,ReminderPermissions.notificationSettings(ctx).action)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,ReminderPermissions.exactSettings(ctx).action)
        assertEquals("package:"+ctx.packageName,ReminderPermissions.exactSettings(ctx).dataString)
    }
    @Test fun recoveryFilterCoversBootUpgradeTimeZoneAndPermissionGrant(){
        for(action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))assertTrue(ReminderBootReceiver.handles(action))
        assertFalse(ReminderBootReceiver.handles("unknown"));assertFalse(ReminderBootReceiver.handles(null))
    }
}
