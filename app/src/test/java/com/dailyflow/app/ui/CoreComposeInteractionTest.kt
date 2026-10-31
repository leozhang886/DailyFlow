package com.dailyflow.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.MainActivity
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.domain.Priority
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.LooperMode
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CoreComposeInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun configureHostIdling() {
        IdlingPolicies.setMasterPolicyTimeout(5, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(5, TimeUnit.SECONDS)
    }
    @After fun dumpHostState() {
        try { println("Host UI state: "+model.state.value+" / "+model.interaction.value) } catch (_: Exception) { }
    }
    private val app get() = compose.activity.application as DailyFlowApplication
    private val model get() = ViewModelProvider(compose.activity)[DailyViewModel::class.java]
    private fun create(title: String, daily: Boolean = false, high: Boolean = false) {
        compose.onNodeWithTag("newTask").performClick()
        compose.onNodeWithTag("taskTitle").performTextInput(title)
        compose.onNodeWithTag("taskNote").performTextInput("UI 输入备注")
        if(high) compose.onNodeWithTag("priority_HIGH").performScrollTo().performClick()
        if(daily) compose.onNodeWithText("每天").performScrollTo().performClick()
        compose.onNodeWithTag("saveTask").performClick()
        compose.waitUntil(10000) { model.interaction.value.page == TaskPage.LIST && model.state.value.tasks.any { it.title == title } }
        compose.onNodeWithText(title).assertIsDisplayed()
    }
    @Test fun createViaRealFormWritesRoomAndShowsList() {
        create("UI-新任务", high = true)
        val row = runBlocking { app.repository.observeDay(model.state.value.date).first().single() }
        assertEquals("UI 输入备注", row.note); assertEquals(Priority.HIGH, row.priority)
    }
    @Test fun discardViaDialogDoesNotCreateTask() {
        compose.onNodeWithTag("newTask").performClick()
        compose.onNodeWithTag("taskTitle").performTextInput("UI-不应保存")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("discardDraft").performClick()
        compose.onNodeWithTag("listDatePicker").assertExists()
        assertEquals(0, runBlocking { app.database.taskDao().taskCount() })
    }
    @Test fun recurringSingleEditViaScopeDialogLeavesTomorrowUnchanged() {
        create("UI-每日任务", daily = true)
        compose.onNodeWithText("UI-每日任务").performClick()
        compose.onNodeWithTag("editTask").performClick()
        compose.onNodeWithText("仅这一天").assertExists()
        compose.onNodeWithTag("confirmEditScope").performClick()
        compose.onNodeWithTag("taskTitle").performTextClearance()
        compose.onNodeWithTag("taskTitle").performTextInput("UI-只改当天")
        compose.onNodeWithTag("saveTask").performClick()
        compose.waitUntil(10000) { model.interaction.value.page == TaskPage.LIST && model.state.value.tasks.any { it.title == "UI-只改当天" } }
        val tomorrow = runBlocking { app.repository.observeDay(model.state.value.date.plusDays(1)).first().single() }
        assertEquals("UI-每日任务", tomorrow.title); assertFalse(tomorrow.completed)
    }
    @Test fun cancellingCalendarKeepsSelectedDate() {
        val before = model.state.value.date
        compose.onNodeWithTag("listDatePicker").performClick()
        compose.onNodeWithText("确定日期").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(before, model.state.value.date)
    }
    @Test fun deleteConfirmationReallyRemovesTask() {
        create("UI-删除任务")
        compose.onNodeWithText("UI-删除任务").performClick()
        compose.onNodeWithTag("deleteTask").performClick()
        compose.onNodeWithTag("confirmDelete").performClick()
        compose.waitUntil(10000) { model.interaction.value.page == TaskPage.LIST && model.state.value.tasks.isEmpty() }
        assertEquals(0, runBlocking { app.database.taskDao().taskCount() })
    }
    @Test fun completionAndUndoInDetailsReflectDatabaseState() {
        create("UI-完成切换")
        compose.onNodeWithText("UI-完成切换").performClick()
        compose.onNodeWithTag("detailCompletion").performClick()
        compose.waitUntil(10000) { model.state.value.tasks.singleOrNull()?.completed == true }
        compose.onNodeWithText("状态：已完成").assertIsDisplayed()
        compose.onNodeWithTag("detailCompletion").performClick()
        compose.waitUntil(10000) { model.state.value.tasks.singleOrNull()?.completed == false }
        assertEquals(0, runBlocking { app.database.taskDao().dayStateCount() })
    }
    @Test fun entireSeriesDeleteRequiresExplicitChoice() {
        create("UI-整个系列", daily = true)
        compose.onNodeWithText("UI-整个系列").performClick()
        compose.onNodeWithTag("deleteTask").performClick()
        compose.onNodeWithText("整个系列（包含历史）").performClick()
        compose.onNodeWithText("整个系列及其历史完成记录将被删除，无法撤销。").assertExists()
        compose.onNodeWithTag("confirmDelete").performClick()
        compose.waitUntil(10000) { model.interaction.value.page == TaskPage.LIST && model.state.value.tasks.isEmpty() }
        assertEquals(0, runBlocking { app.database.taskDao().taskCount() })
    }

}
