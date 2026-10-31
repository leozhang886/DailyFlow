package com.dailyflow.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Optional native host rendering evidence. This is NOT an emulator/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HostRenderingProbe {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DailyFlowApplication
    private val model get()=ViewModelProvider(compose.activity)[DailyViewModel::class.java]
    private fun shot(name:String){
        compose.waitForIdle()
        val windowClass=Class.forName("android.view.WindowManagerGlobal")
        val windows=windowClass.getDeclaredMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST") val views=windowClass.getDeclaredField("mViews").apply{isAccessible=true}.get(windows) as List<android.view.View>
        val view=views.last{it.isShown&&it.width>0&&it.height>0}
        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread{view.draw(android.graphics.Canvas(bitmap))}
        assertTrue(bitmap.width>200&&bitmap.height>200)
        val colors=mutableSetOf<Int>();for(y in 0 until bitmap.height step 8)for(x in 0 until bitmap.width step 8)colors.add(bitmap.getPixel(x,y))
        assertTrue("Must contain actual rendered content",colors.size>30)
        val dir=File("build/host-renders").apply{mkdirs()}
        File(dir,name+".png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun captureNativeHostPages(){
        compose.waitForIdle()
        val today=LocalDate.now();runBlocking{
            app.repository.create("DF-E 昨日回顾","记录今天以前的安排。",today.minusDays(1))
            app.repository.createTask(TaskDraft(title="DF-E 整理今天的学习笔记",note="回顾重点，整理下一次行动。",date=today,priority=Priority.HIGH)).also{
                app.repository.create("DF-E 散步与拉伸","",today,RecurrenceRule(Frequency.DAILY))
                app.repository.create("DF-E 阅读二十分钟","",today).let{done->app.repository.setCompleted(done,today,true)}
            }
        }
        compose.waitUntil(10000){model.state.value.tasks.size==3};shot("01-home-light-host")
        compose.onNodeWithText("DF-E 整理今天的学习笔记").performClick();shot("02-details-host")
        compose.onNodeWithTag("editTask").performClick();shot("03-editor-host")
        compose.onNodeWithTag("cancelEditor").performClick();compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("listDatePicker").performClick();shot("04-calendar-dialog-host");compose.onNodeWithText("取消").performClick()
        val planning=ViewModelProvider(compose.activity)[PlanningViewModel::class.java]
        compose.onNodeWithTag("openPlanning").performClick();compose.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();!planning.series.value.loading&&planning.series.value.items.isNotEmpty()};shot("05-repeat-manager-host")
        compose.onNodeWithText("编辑规则").performClick();shot("08-rule-editor-host");compose.onNodeWithTag("cancelRuleEdit").performClick();compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("openHistory").performClick();compose.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();!planning.history.value.loading&&planning.history.value.items.isNotEmpty()};shot("06-history-host");compose.onNodeWithText("返回").performClick()
        val planner=ViewModelProvider(compose.activity)[PlanningViewModel::class.java]
        compose.runOnUiThread{compose.activity.setContent{FlowTheme(true){DailyFlowApp(model,planner)}}};shot("07-home-dark-host")
    }
}
