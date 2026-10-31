package com.dailyflow.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import com.dailyflow.app.domain.*
import com.dailyflow.app.ui.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskUiDatabaseIntegrationTest {
    private lateinit var db: DailyFlowDatabase
    private lateinit var repo: RoomTaskRepository
    private lateinit var vm: DailyViewModel
    private val store = ViewModelStore()
    private val date=LocalDate.of(2026,10,27)
    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),DailyFlowDatabase::class.java).build()
        repo=RoomTaskRepository(db)
        vm=DailyViewModel(repo,Clock.fixed(Instant.parse("2026-10-27T04:00:00Z"),ZoneOffset.UTC)); store.put("ui",vm)
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }
    private suspend fun message(text:String) = withTimeout(10000) { vm.interaction.first { it.message==text } }
    @Test fun createFromViewModelReallySavesAndRefreshesDay() = runBlocking {
        vm.newTask(); vm.setTitle("真实数据库任务"); vm.setNote("已连接"); vm.setTime(754); vm.setPriority(Priority.HIGH)
        vm.saveEditor(); message("已创建任务")
        val row=withTimeout(10000) { vm.state.first { it.tasks.isNotEmpty() } }.tasks.single()
        assertEquals("真实数据库任务",row.title); assertEquals(754,row.scheduledMinute); assertEquals(Priority.HIGH,row.priority)
    }
    @Test fun singleEditThroughUiKeepsOtherDateAndCompletedState() = runBlocking {
        val id=repo.createTask(TaskDraft("原系列",date=date,rule=RecurrenceRule(Frequency.DAILY)))
        repo.setCompleted(id,date,true)
        val row=repo.observeDay(date).first().single()
        vm.openDetails(row); vm.requestEdit(row); vm.confirmEditScope()
        withTimeout(10000) { vm.interaction.first { it.page==TaskPage.EDITOR && !it.busy } }
        vm.setTitle("只改今天"); vm.saveEditor(); message("已保存修改")
        assertEquals("只改今天",repo.observeDay(date).first().single().title)
        assertTrue(repo.observeDay(date).first().single().completed)
        assertEquals("原系列",repo.observeDay(date.plusDays(1)).first().single().title)
    }
    @Test fun deleteSingleThroughUiPreservesSeriesTomorrow() = runBlocking {
        repo.createTask(TaskDraft("系列",date=date,rule=RecurrenceRule(Frequency.DAILY)))
        val row=repo.observeDay(date).first().single()
        vm.openDetails(row); vm.requestDelete(row); vm.confirmDelete(); message("已删除任务")
        assertTrue(repo.observeDay(date).first().isEmpty())
        assertEquals(1,repo.observeDay(date.plusDays(1)).first().size)
    }
    @Test fun cancellingEditorDoesNotModifyDatabase() = runBlocking {
        val id=repo.createTask(TaskDraft("保留原值",date=date))
        val row=repo.observeDay(date).first().single()
        vm.openDetails(row); vm.requestEdit(row)
        withTimeout(10000) { vm.interaction.first { it.page==TaskPage.EDITOR && !it.busy } }
        vm.setTitle("丢弃的修改"); vm.requestBack(); vm.discardEditor()
        assertEquals("保留原值",db.taskDao().task(id)!!.title)
        assertEquals(0,db.taskDao().exceptionCount())
    }
}
