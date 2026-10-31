package com.dailyflow.app.reminders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReminderUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DailyFlowApplication
    private val model get()=ViewModelProvider(compose.activity)[DailyViewModel::class.java]
    private fun waitFor(predicate:()->Boolean){compose.waitUntil(10000){ShadowLooper.idleMainLooper();predicate()}}
    @Test fun enableSaveReloadAndDetailUseSamePersistentReminder() {
        val date=LocalDate.now().plusDays(1)
        compose.runOnUiThread{model.selectDate(date);model.newTask()}
        compose.onNodeWithTag("taskTitle").performTextInput("UI提醒")
        compose.onNodeWithTag("reminderEnabled").performScrollTo().performClick()
        compose.runOnUiThread{model.setReminderMinute(600)}
        compose.onNodeWithTag("saveTask").performClick()
        waitFor{model.state.value.tasks.size==1}
        val task=model.state.value.tasks.single()
        assertEquals(ReminderSetting(true,600,0),task.reminder)
        assertEquals(600,runBlocking{app.database.reminderDao().rule(task.taskId)!!.minute})
        compose.onNodeWithTag("dailyList").performScrollToNode(hasTestTag("task_"+task.taskId))
        compose.onNodeWithTag("task_"+task.taskId).performClick()
        compose.onNodeWithTag("reminderDetailStatus").assertTextEquals("提醒："+date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))+" 10:00")
        compose.onNodeWithTag("editTask").performClick()
        waitFor{model.interaction.value.editor!=null&&!model.interaction.value.busy}
        assertEquals(ReminderSetting(true,600,0),model.interaction.value.editor!!.draft.reminder)
    }
    @Test fun pastReminderHasExplicitValidationAndCreatesNoTask() {
        compose.runOnUiThread{model.newTask();model.setTitle("past");model.setReminderEnabled(true);model.setReminderDate(LocalDate.now().minusDays(1));model.setReminderMinute(1)}
        compose.onNodeWithTag("saveTask").performClick()
        waitFor{model.interaction.value.editor?.validationError!=null}
        assertTrue(model.interaction.value.editor!!.validationError!!.contains("过去"))
        assertEquals(0,runBlocking{app.database.taskDao().taskCount()})
        assertTrue(runBlocking{app.database.reminderDao().rules().isEmpty()})
    }
    @Test fun normalTitleEditPreservesReminderWithoutRequestingNewPermission() {
        val date=LocalDate.now().plusDays(1)
        runBlocking{app.repository.createTask(TaskDraft("original",date=date,reminder=ReminderSetting(true,600)))}
        compose.runOnUiThread{model.selectDate(date)}
        waitFor{model.state.value.tasks.size==1}
        val task=model.state.value.tasks.single()
        compose.runOnUiThread{model.requestEdit(task)}
        waitFor{model.interaction.value.editor!=null&&!model.interaction.value.busy}
        compose.onNodeWithTag("taskTitle").performTextReplacement("edited")
        compose.onNodeWithTag("saveTask").performClick()
        waitFor{model.state.value.tasks.singleOrNull()?.title=="edited"}
        assertEquals(600,model.state.value.tasks.single().reminder!!.minute)
    }
}
