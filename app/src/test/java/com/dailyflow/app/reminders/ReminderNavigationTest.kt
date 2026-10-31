package com.dailyflow.app.reminders

import android.app.AlarmManager
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReminderNavigationTest {
    private val app get()=ApplicationProvider.getApplicationContext<DailyFlowApplication>()
    private val day get()=LocalDate.now().plusDays(1)
    private var activity:ActivityController<MainActivity>?=null
    private fun waitFor(predicate:()->Boolean){val end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){ShadowLooper.idleMainLooper();if(predicate())return;Thread.sleep(10)};assertTrue("condition timeout",predicate())}
    private fun open(id:String,date:LocalDate=day):DailyViewModel {
        val a=Robolectric.buildActivity(MainActivity::class.java,ReminderIntents.detail(app,ReminderKey(id,date))).setup();activity=a
        return ViewModelProvider(a.get())[DailyViewModel::class.java]
    }
    @After fun close(){activity?.pause()?.stop()?.destroy();app.database.close()}
    @Test fun clickOpensExactTaskEvenWithSameTitles() {
        val ids=runBlocking{listOf(app.repository.create("same","",day,null),app.repository.create("same","",day,null))}
        val model=open(ids[1]);waitFor{model.interaction.value.page==TaskPage.DETAILS}
        assertEquals(ids[1],model.interaction.value.detail!!.taskId);assertEquals(day,model.interaction.value.detail!!.date)
    }
    @Test fun deletedTaskOldNotificationDoesNotOpenWrongDetail() {
        val id=runBlocking{app.repository.create("deleted","",day,null).also{app.repository.delete(it,day,EditScope.SINGLE)}}
        val model=open(id);waitFor{model.interaction.value.message!=null}
        assertEquals(TaskPage.LIST,model.interaction.value.page);assertNull(model.interaction.value.detail)
    }
    @Test fun completedTaskOldNotificationIsHandledSafely() {
        val id=runBlocking{app.repository.create("done","",day,null).also{app.repository.setCompleted(it,day,true)}}
        val model=open(id);waitFor{model.interaction.value.message!=null}
        assertEquals(TaskPage.LIST,model.interaction.value.page);assertNull(model.interaction.value.detail)
    }
    @Test fun incomingReminderDoesNotDiscardDirtyEditor() {
        val id=runBlocking{app.repository.create("target","",day,null)}
        val model=open(id);waitFor{model.interaction.value.page==TaskPage.DETAILS}
        model.newTask();model.setTitle("unsaved");model.openReminderTask(id,day)
        assertEquals(TaskPage.EDITOR,model.interaction.value.page);assertEquals("unsaved",model.interaction.value.editor!!.draft.title)
    }
    @Test fun manifestBootAndUpgradeBroadcastRestoreCanceledSystemAlarm() {
        runBlocking{app.repository.createTask(TaskDraft("restore",date=day,reminder=ReminderSetting(true,480)))}
        val alarms=app.getSystemService(AlarmManager::class.java)
        for(action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED)) {
            val cursor=runBlocking{app.database.reminderDao().alarm()!!};AndroidReminderScheduler(app).cancel(cursor)
            assertTrue(Shadows.shadowOf(alarms).scheduledAlarms.isEmpty())
            app.sendBroadcast(Intent(app,ReminderBootReceiver::class.java).setAction(action))
            waitFor{Shadows.shadowOf(alarms).scheduledAlarms.size==1}
        }
        assertTrue(runBlocking{app.database.reminderDao().deliveries().isEmpty()})
    }
}
