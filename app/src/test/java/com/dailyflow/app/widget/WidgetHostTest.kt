package com.dailyflow.app.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalGlanceRemoteViewsApi::class)
class WidgetHostTest {
    private val app get() = ApplicationProvider.getApplicationContext<DailyFlowApplication>()
    private val date get() = LocalDate.now()
    private val dispatcher = UnconfinedTestDispatcher()
    private val fakeId = object: GlanceId {}
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { app.database.close(); Dispatchers.resetMain() }
    private suspend fun task() : TaskOccurrence {
        app.repository.create("DF-W-HOST","",date,RecurrenceRule(Frequency.DAILY))
        return app.repository.observeDay(date).first().single()
    }
    @Test fun realGlanceCallbackCompletesAndUndoesRoomWithoutStartingActivity() = runTest {
        val first=task(); CompleteWidgetAction().onAction(app,fakeId,WidgetActions.parameters(first))
        val completed=app.repository.observeDay(date).first().single();assertTrue(completed.completed)
        CompleteWidgetAction().onAction(app,fakeId,WidgetActions.parameters(completed))
        assertFalse(app.repository.observeDay(date).first().single().completed)
        assertFalse(app.repository.observeDay(date.plusDays(1)).first().single().completed)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun malformedParametersCannotChangeDatabase() = runTest {
        task(); assertNull(WidgetActions.command(actionParametersOf()))
        CompleteWidgetAction().onAction(app,fakeId,actionParametersOf(WidgetActions.date to "invalid"))
        assertEquals(0,app.database.taskDao().dayStateCount()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun titleIntentContainsExactTaskDateAndDistinctIdentity() = runTest {
        val t=task(); val intent=WidgetNavigation.detailIntent(app,t.taskId,t.date)
        assertEquals(t.taskId to t.date,WidgetNavigation.destination(intent))
        assertEquals("com.dailyflow.app.MainActivity",intent.component!!.className)
        assertNotEquals(intent.data,WidgetNavigation.detailIntent(app,"other",date).data)
    }
    @Test fun titleNavigationLoadsTaskDetailsThroughRealViewModelAndRoom() = runTest {
        val t=task();val store=ViewModelStore()
        val vm=ViewModelProvider(store,DailyViewModel.Factory(app.repository))[DailyViewModel::class.java]
        vm.openWidgetTask(t.taskId,t.date)
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.interaction.first { it.page==TaskPage.DETAILS } } }
        assertEquals(t.taskId,vm.interaction.value.detail!!.taskId);store.clear()
    }
    @Test fun deletedTitleNavigationShowsMessageInsteadOfStaleDetails() = runTest {
        val t=task();app.repository.delete(t.taskId,t.date,EditScope.SERIES);val store=ViewModelStore()
        val vm=ViewModelProvider(store,DailyViewModel.Factory(app.repository))[DailyViewModel::class.java]
        vm.openWidgetTask(t.taskId,t.date)
        withContext(Dispatchers.Default) { withTimeout(5000) { vm.interaction.first { it.message!=null } } }
        assertEquals(TaskPage.LIST,vm.interaction.value.page);assertNull(vm.interaction.value.detail);store.clear()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private suspend fun render(count: Int, height: Int): View {
        val tasks=(1..count).map { TaskOccurrence("id-"+it,date,"DF-W-ROW-"+it,"",it==1,false,false) }
        val result=GlanceRemoteViews().compose(app,DpSize(320.dp,height.dp)) { DailyWidgetContent(WidgetSnapshot(date,tasks)) }
        return result.remoteViews.apply(app,null)
    }
    @Test fun realRemoteViewsSmallLayoutShowsThreeRowsAndTotalCount() = runTest {
        val views=descendants(render(6,280));val text=views.filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(3,text.count { it.contains("DF-W-ROW-") });assertTrue(text.contains("已完成 1 / 6"));assertTrue(text.contains("还有 3 项 · 打开全部"))
    }
    @Test fun realRemoteViewsLargeLayoutShowsFiveRows() = runTest {
        val text=descendants(render(6,380)).filterIsInstance<TextView>().map { it.text.toString() }
        assertEquals(5,text.count { it.contains("DF-W-ROW-") })
    }
    @Test fun realRemoteViewsEmptyStateIsFriendly() = runTest {
        val text=descendants(render(0,280)).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(text.contains("今天暂无任务，点此打开 App 添加"));assertTrue(text.contains("已完成 0 / 0"))
    }
    @Test fun realRemoteViewsResolvesNightTextColor() = runTest {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-night")
        val title=descendants(render(1,280)).filterIsInstance<TextView>().first { it.text.toString().contains("DF-W-ROW-") }
        assertEquals(0xFFCBD5E1.toInt(),title.currentTextColor)
    }
    @Test fun renderedCompletionClickRunsBroadcastCallbackAndWritesRoom() = runTest {
        val t=task()
        val result=GlanceRemoteViews().compose(app,DpSize(320.dp,280.dp)) { DailyWidgetContent(WidgetSnapshot(date,listOf(t))) }
        val view=result.remoteViews.apply(app,null)
        val complete=descendants(view).first { it.contentDescription?.toString()=="完成："+t.title }
        var click: View? = complete
        while (click != null && !click.hasOnClickListeners()) click = click.parent as? View
        assertNotNull("Rendered completion control must have a click listener",click)
        assertTrue(click!!.performClick())
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { withTimeout(8000) {
            while(!app.repository.observeDay(date).first().single().completed) delay(25)
        } }
        assertTrue(app.repository.observeDay(date).first().single().completed)
        val completed=app.repository.observeDay(date).first().single()
        val undone=GlanceRemoteViews().compose(app,DpSize(320.dp,280.dp)) { DailyWidgetContent(WidgetSnapshot(date,listOf(completed))) }
        val undoView=undone.remoteViews.apply(app,null)
        var undo: View?=descendants(undoView).first { it.contentDescription?.toString()=="撤销完成："+t.title }
        while(undo!=null && !undo.hasOnClickListeners())undo=undo.parent as? View
        assertNotNull(undo);assertTrue(undo!!.performClick());Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        withContext(Dispatchers.Default) { withTimeout(8000) { while(app.repository.observeDay(date).first().single().completed)delay(25) } }
        assertEquals(0,app.database.taskDao().dayStateCount())
        assertFalse(app.repository.observeDay(date.plusDays(1)).first().single().completed)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun renderedTitleClickSendsCorrectActivityIntent() = runTest {
        val t=task();val rendered=GlanceRemoteViews().compose(app,DpSize(320.dp,280.dp)) { DailyWidgetContent(WidgetSnapshot(date,listOf(t))) }
        val root=rendered.remoteViews.apply(app,null)
        var title: View?=descendants(root).filterIsInstance<TextView>().first { it.text.toString()==t.title }
        while(title!=null && !title.hasOnClickListeners())title=title.parent as? View
        assertNotNull(title);assertTrue(title!!.performClick())
        assertEquals(t.taskId to t.date,WidgetNavigation.destination(requireNotNull(Shadows.shadowOf(app).nextStartedActivity)))
    }
    @Test fun allPlacedWidgetInstancesAreDiscoveredByProductionManager() = runTest {
        val manager=android.appwidget.AppWidgetManager.getInstance(app)
        val shadow=Shadows.shadowOf(manager)
        val info=android.appwidget.AppWidgetProviderInfo().apply {
            provider=android.content.ComponentName(app,DailyTaskWidgetReceiver::class.java)
            initialLayout=com.dailyflow.app.R.layout.widget_loading
        }
        shadow.addInstalledProvider(info);shadow.addBoundWidget(501,info);shadow.addBoundWidget(502,info)
        assertTrue(WidgetUpdates.hasWidgets(app))
        val glance=androidx.glance.appwidget.GlanceAppWidgetManager(app)
        val ids=WidgetUpdates.placedIds(app)
        assertEquals(setOf(501,502),ids.map { glance.getAppWidgetId(it) }.toSet())
        val outputs=mutableMapOf<Int,WidgetSnapshot>()
        WidgetUpdates.refreshAll(app) { outputs[glance.getAppWidgetId(it)]=WidgetController(app.repository).snapshot() }
        assertEquals(2,outputs.size);assertEquals(outputs[501],outputs[502])
    }
    @Test fun widgetHomeLeavesPristineEditorAndShowsList() = runTest {
        val store=ViewModelStore();val vm=ViewModelProvider(store,DailyViewModel.Factory(app.repository))[DailyViewModel::class.java]
        vm.newTask();assertEquals(TaskPage.EDITOR,vm.interaction.value.page)
        vm.openWidgetHome();assertEquals(TaskPage.LIST,vm.interaction.value.page);assertNull(vm.interaction.value.editor);store.clear()
    }
    @Test fun widgetNavigationNeverDiscardsDirtyDraft() = runTest {
        val store=ViewModelStore();val vm=ViewModelProvider(store,DailyViewModel.Factory(app.repository))[DailyViewModel::class.java]
        vm.newTask();vm.setTitle("保留草稿");vm.openWidgetHome()
        assertEquals(TaskPage.EDITOR,vm.interaction.value.page);assertEquals("保留草稿",vm.interaction.value.editor!!.draft.title)
        vm.openWidgetTask("missing",date);assertEquals(TaskPage.EDITOR,vm.interaction.value.page);assertNotNull(vm.interaction.value.error);store.clear()
    }
    @Test fun renderErrorDoesNotClaimZeroTasksSuccessfullyLoaded() = runTest {
        val result=GlanceRemoteViews().compose(app,DpSize(320.dp,280.dp)) { DailyWidgetContent(WidgetSnapshot(date,error=true)) }
        val text=descendants(result.remoteViews.apply(app,null)).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(text.contains("计数暂不可用"));assertTrue(text.contains("暂时无法读取任务，请点刷新重试"));assertFalse(text.contains("已完成 0 / 0"))
    }
    @Test fun encodedButtonContainsDateTargetAndRevision() = runTest {
        val t=task();val command=WidgetActions.command(WidgetActions.parameters(t))!!
        assertEquals(t.taskId,command.taskId);assertEquals(t.date,command.date);assertEquals(t.stateRevision,command.revision)
        assertFalse(command.expected);assertTrue(command.target)
    }
}
