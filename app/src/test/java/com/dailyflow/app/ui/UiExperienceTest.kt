package com.dailyflow.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import java.time.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiExperienceTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DailyFlowApplication
    private var injected:DailyViewModel?=null
    private val stores=mutableListOf<ViewModelStore>()
    private val model get()=injected?:ViewModelProvider(compose.activity)[DailyViewModel::class.java]
    private val planner get()=ViewModelProvider(compose.activity)[PlanningViewModel::class.java]
    @After fun cleanup(){stores.forEach{it.clear()}}
    private fun inject(repo:TaskRepository){
        compose.waitForIdle()
        compose.runOnUiThread {
            val store=ViewModelStore();stores+=store
            val m=ViewModelProvider(store,DailyViewModel.Factory(repo))[DailyViewModel::class.java];injected=m
            val p=ViewModelProvider(store,PlanningViewModel.Factory(repo))[PlanningViewModel::class.java]
            val container=compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            val view=container.getChildAt(0) as androidx.compose.ui.platform.ComposeView
            view.disposeComposition()
            view.setContent { androidx.compose.runtime.key(m){FlowTheme{DailyFlowApp(m,p)}} }
        }
        compose.waitForIdle()
    }
    @Test fun emptyStateOffersDirectCreation(){
        compose.onNodeWithTag("dailyList").performScrollToNode(hasTestTag("emptyCreate"));compose.onNodeWithTag("emptyCreate").performClick()
        compose.onNodeWithTag("taskTitle").assertIsDisplayed();assertEquals(TaskPage.EDITOR,model.interaction.value.page)
    }
    @Test fun invalidTitleFocusesVisibleFieldAndNeverWrites(){
        compose.onNodeWithTag("newTask").performClick();compose.onNodeWithTag("priority_HIGH").performScrollTo().performClick();compose.onNodeWithTag("saveTask").performClick()
        compose.waitUntil(10000){model.interaction.value.editor?.validationError!=null}
        compose.onNodeWithTag("taskTitle").assertIsFocused().assertIsDisplayed();compose.onNodeWithTag("saveTask").assertIsDisplayed()
        assertEquals(0,runBlocking{app.database.taskDao().taskCount()})
    }
    @Test fun noteErrorKeepsContentAndFocusesNote(){
        compose.onNodeWithTag("newTask").performClick();compose.onNodeWithTag("taskTitle").performTextInput("备注验证")
        compose.onNodeWithTag("taskNote").performTextInput("长".repeat(4001));compose.onNodeWithTag("saveTask").performClick()
        compose.waitUntil(10000){model.interaction.value.editor?.validationError!=null}
        compose.onNodeWithTag("taskNote").assertIsFocused().assertIsDisplayed();assertEquals(4001,model.interaction.value.editor!!.draft.note.length)
        assertEquals(0,runBlocking{app.database.taskDao().taskCount()})
    }
    @Test fun failedReadIsNotEmptyAndRetryWorks(){
        val actual=app.repository;var fail=true;val repo=object:TaskRepository by actual{
            override fun observeDay(date:LocalDate):Flow<List<TaskOccurrence>> = flow{if(fail)throw IOException("test failure");emitAll(actual.observeDay(date))}
        };inject(repo);compose.waitUntil(10000){model.state.value.readFailed};compose.onNodeWithText("这一天暂无任务").assertDoesNotExist()
        fail=false;compose.onNodeWithText("重试加载").performClick();compose.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();!model.state.value.loading&&!model.state.value.readFailed}
        compose.onNodeWithText("这一天暂无任务").assertExists()
    }
    @Test fun detailsRemovedElsewhereHaveNoStaleEditOrDeleteButtons(){
        val day=LocalDate.now();val id=runBlocking{app.repository.create("已移除测试","",day)}
        compose.waitUntil(10000){model.state.value.tasks.any{it.taskId==id}};compose.onNodeWithText("已移除测试").performClick()
        runBlocking{app.repository.delete(id,day,EditScope.SINGLE)};compose.waitUntil(10000){model.state.value.tasks.isEmpty()&&!model.state.value.loading}
        compose.onNodeWithText("该任务已删除或移动，请返回清单查看。").assertIsDisplayed();compose.onNodeWithTag("editTask").assertDoesNotExist();compose.onNodeWithTag("deleteTask").assertDoesNotExist()
    }
    @Test fun completionInFlightDisablesConflictingDetailsActions(){
        val day=LocalDate.now();val id=runBlocking{app.repository.create("正在保存测试","",day)}
        val actual=app.repository;val gate=CompletableDeferred<Unit>();val repo=object:TaskRepository by actual{
            override suspend fun setCompleted(taskId:String,date:LocalDate,completed:Boolean){gate.await();actual.setCompleted(taskId,date,completed)}
        };inject(repo);compose.waitUntil(10000){model.state.value.tasks.any{it.taskId==id}}
        compose.onNodeWithText("正在保存测试").performClick();compose.onNodeWithTag("detailCompletion").performClick();compose.waitUntil(10000){model.completionPending.value.isNotEmpty()}
        compose.onNodeWithTag("editTask").assertIsNotEnabled();compose.onNodeWithTag("deleteTask").assertIsNotEnabled()
        compose.runOnIdle{gate.complete(Unit)};compose.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();model.completionPending.value.isEmpty()};compose.onNodeWithText("状态：已完成").assertIsDisplayed()
    }
    @Test fun historyDetailsBackKeepsMonthAndScrollPosition(){
        val past=YearMonth.now().minusMonths(1);val target=runBlocking{repeat(16){app.repository.create("历史前排-"+it,"",past.atDay(10))};app.repository.create("历史返回目标","",past.atDay(1))}
        compose.onNodeWithTag("openHistory").performClick();compose.onNodeWithText("上个月").performClick();compose.waitUntil(10000){planner.history.value.items.any{it.taskId==target}}
        val tag="history_"+target+"_"+past.atDay(1);compose.onNodeWithTag("historyList").performScrollToNode(hasTestTag(tag));compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithText("返回").performClick();compose.waitUntil(10000){planner.state.value.page==PlanningPage.HISTORY}
        assertEquals(past,planner.historyMonth.value);compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    @Test fun completedTaskIsClearlySeparatedAndCanUndo(){
        val day=LocalDate.now();val id=runBlocking{app.repository.create("分组验证","",day).also{app.repository.setCompleted(it,day,true)}}
        compose.waitUntil(10000){model.state.value.tasks.any{it.taskId==id&&it.completed}};compose.onNodeWithText("已完成 · 1").assertExists()
        compose.onNodeWithContentDescription("撤销完成：分组验证").performClick();compose.waitUntil(10000){model.state.value.tasks.any{it.taskId==id&&!it.completed}}
        compose.onNodeWithText("待完成 · 1").assertExists()
    }
}
