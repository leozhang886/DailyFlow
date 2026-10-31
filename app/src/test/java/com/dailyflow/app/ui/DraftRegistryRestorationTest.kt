package com.dailyflow.app.ui

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DraftRegistryRestorationTest{
    @Test fun actualActivitySavedRegistryRestoresTaskDraftWithoutWritingTask(){
        val first=Robolectric.buildActivity(MainActivity::class.java).setup()
        val app=first.get().application as DailyFlowApplication
        val vm=ViewModelProvider(first.get())[DailyViewModel::class.java]
        val date=LocalDate.now().plusDays(2);vm.selectDate(date);vm.newTask();vm.setTitle("注册表恢复");vm.setNote("未保存备注")
        val bundle=Bundle();first.pause().saveInstanceState(bundle).stop().destroy()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup(bundle)
        val restored=ViewModelProvider(second.get())[DailyViewModel::class.java]
        assertEquals(TaskPage.EDITOR,restored.interaction.value.page);assertEquals("注册表恢复",restored.interaction.value.editor!!.draft.title)
        assertEquals(date,restored.interaction.value.editor!!.draft.date);assertEquals(0,runBlocking{app.database.taskDao().taskCount()})
        second.pause().stop().destroy();app.database.close()
    }
    @Test fun actualActivitySavedRegistryRestoresRuleDraft(){
        val first=Robolectric.buildActivity(MainActivity::class.java).setup();val app=first.get().application as DailyFlowApplication
        val summary=runBlocking{app.repository.create("规则草稿","",LocalDate.now(),RecurrenceRule(Frequency.DAILY));app.repository.observeSeries().first().single()}
        val planner=ViewModelProvider(first.get())[PlanningViewModel::class.java];planner.openRule(summary);planner.interval("4")
        val bundle=Bundle();first.pause().saveInstanceState(bundle).stop().destroy()
        val second=Robolectric.buildActivity(MainActivity::class.java).setup(bundle)
        val restored=ViewModelProvider(second.get())[PlanningViewModel::class.java]
        assertEquals(PlanningPage.RULE,restored.state.value.page);assertEquals("4",restored.state.value.form!!.settings.intervalText)
        assertTrue(runBlocking{app.database.taskDao().versions(summary.taskId).isEmpty()});second.pause().stop().destroy();app.database.close()
    }
}
