package com.dailyflow.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PlanningComposeTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DailyFlowApplication
    private val planner get()=ViewModelProvider(compose.activity)[PlanningViewModel::class.java]
    private val date get()=LocalDate.now()
    private fun create():String=runBlocking{app.repository.create("UI-重复管理","",date.minusDays(1),RecurrenceRule(Frequency.DAILY))}
    private fun manage(id:String){compose.onNodeWithTag("openPlanning").performClick();compose.waitUntil(10000){planner.series.value.items.any{it.taskId==id}}}
    private fun edit(id:String){manage(id);compose.onNodeWithTag("rule_"+id).performClick();compose.onNodeWithTag("ruleInterval").performTextReplacement("2")}
    @Test fun unchangedRuleSaveButtonIsDisabled(){
        val id=create();manage(id);compose.onNodeWithTag("rule_"+id).performClick()
        compose.onNodeWithTag("previewRule").assertIsNotEnabled();assertTrue(runBlocking{app.database.taskDao().versions(id).isEmpty()})
    }
    @Test fun actualRuleFormSavesVersionAndPreservesYesterday(){
        val id=create();edit(id);compose.onNodeWithTag("previewRule").performClick();compose.onNodeWithTag("saveRule").performClick()
        compose.waitUntil(10000){planner.state.value.page==PlanningPage.MANAGE&&!planner.state.value.busy}
        val rows=runBlocking{app.repository.observeDay(date.plusDays(1)).first()};assertTrue(rows.isEmpty())
        assertEquals(1,runBlocking{app.repository.observeDay(date.minusDays(1)).first().size})
    }
    @Test fun cancellingRuleConfirmationDoesNotWrite(){
        val id=create();edit(id);compose.onNodeWithTag("previewRule").performClick();compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking{app.database.taskDao().versions(id).isEmpty()});assertEquals("2",planner.state.value.form!!.settings.intervalText)
    }
    @Test fun restoreDeletedDayThroughUiWritesSharedRoom(){
        val id=create();runBlocking{app.repository.delete(id,date,EditScope.SINGLE)};manage(id)
        compose.onNodeWithTag("exceptions_"+id).performClick();compose.waitUntil(10000){planner.exceptions.value.items.isNotEmpty()}
        compose.onNodeWithTag("recover_"+date).performClick();compose.onNodeWithTag("confirmRecovery").performClick()
        compose.waitUntil(10000){!planner.state.value.busy&&planner.state.value.confirmRecovery==null}
        assertFalse(runBlocking{app.repository.observeDay(date).first().single().completed})
    }
    @Test fun resettingOverrideKeepsCompletion(){
        val id=create();runBlocking{app.repository.updateContent(id,date,"单日修改","",EditScope.SINGLE);app.repository.setCompleted(id,date,true)};manage(id)
        compose.onNodeWithTag("exceptions_"+id).performClick();compose.waitUntil(10000){planner.exceptions.value.items.isNotEmpty()}
        compose.onNodeWithTag("recover_"+date).performClick();compose.onNodeWithTag("confirmRecovery").performClick()
        compose.waitUntil(10000){!planner.state.value.busy&&planner.state.value.confirmRecovery==null}
        val row=runBlocking{app.repository.observeDay(date).first().single()};assertEquals("UI-重复管理",row.title);assertTrue(row.completed)
    }
    @Test fun historyRowOpensCorrectCompletedDetails(){
        val past=date.minusDays(1);val id=runBlocking{app.repository.create("UI-历史任务","",past).also{app.repository.setCompleted(it,past,true)}}
        compose.onNodeWithTag("openHistory").performClick()
        if(past.month!=date.month){compose.onNodeWithText("上个月").performClick()}
        compose.waitUntil(10000){planner.history.value.items.any{it.taskId==id}}
        compose.onNodeWithTag("history_"+id+"_"+past).performClick();compose.onNodeWithText("状态：已完成").assertIsDisplayed();compose.onNodeWithText("UI-历史任务").assertIsDisplayed()
    }
    @Test fun dirtyRuleBackAsksBeforeDiscard(){
        val id=create();edit(id);compose.onNodeWithText("返回").performClick();compose.onNodeWithTag("discardRule").performClick()
        assertEquals(PlanningPage.MANAGE,planner.state.value.page);assertTrue(runBlocking{app.database.taskDao().versions(id).isEmpty()})
    }
}
