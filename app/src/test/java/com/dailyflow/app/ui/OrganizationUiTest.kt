package com.dailyflow.app.ui
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OrganizationUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val app get()=compose.activity.application as DailyFlowApplication
 private val model get()=ViewModelProvider(compose.activity)[DailyViewModel::class.java]
 private fun waitFor(predicate:()->Boolean){compose.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();predicate()}}
 @Test fun searchFiltersAndClearUseActualDayDatabase(){
  val d=LocalDate.now();runBlocking{app.repository.createTask(TaskDraft("Kotlin学习",note="中文笔记",date=d,priority=Priority.HIGH));app.repository.createTask(TaskDraft("other",date=d))}
  waitFor{model.state.value.tasks.size==2};compose.onNodeWithTag("openSearch").performClick();compose.onNodeWithTag("taskSearch").performTextInput("  kOtLiN  ")
  waitFor{model.state.value.visibleTasks.size==1};assertEquals("Kotlin学习",model.state.value.visibleTasks.single().title)
  compose.onNodeWithTag("openFilters").performClick();compose.onNodeWithTag("priorityFilter_LOW").performClick();compose.onNodeWithTag("closeFilters").performClick()
  waitFor{model.state.value.visibleTasks.isEmpty()};compose.onNodeWithTag("dailyList").performScrollToNode(hasTestTag("clearListCriteria"));compose.onNodeWithTag("clearListCriteria").performClick();waitFor{model.state.value.visibleTasks.size==2};assertEquals(2,runBlocking{app.database.taskDao().taskCount()})
 }
 @Test fun manualMoveButtonsWriteAndDateChangesKeepGlobalOrder(){
  val d=LocalDate.now();val ids=runBlocking{listOf(app.repository.create("a","",d,RecurrenceRule(Frequency.DAILY)),app.repository.create("b","",d,RecurrenceRule(Frequency.DAILY)))}
  waitFor{model.state.value.tasks.size==2};compose.onNodeWithTag("openSort").performClick();compose.onNodeWithTag("startReordering").performClick()
  compose.onNodeWithTag("dailyList").performScrollToNode(hasTestTag("moveDown_"+ids[0]));compose.onNodeWithTag("moveDown_"+ids[0]).performClick();waitFor{model.state.value.tasks.first().taskId==ids[1]}
  compose.runOnUiThread{model.nextDay()};waitFor{!model.state.value.loading&&model.state.value.date==d.plusDays(1)&&model.state.value.tasks.size==2};assertEquals(ids.reversed(),model.state.value.visibleTasks.map{it.taskId})
 }
 @Test fun priorityEditorHasThreeChoicesAndPersists(){
  compose.onNodeWithTag("newTask").performClick();compose.onNodeWithTag("taskTitle").performTextInput("priority ui");compose.onNodeWithTag("priority_HIGH").performScrollTo().performClick();compose.onNodeWithTag("priority_MEDIUM").assertDoesNotExist();compose.onNodeWithTag("saveTask").performClick();waitFor{model.state.value.tasks.size==1};assertEquals(Priority.HIGH,model.state.value.tasks.single().priority)
 }
}
