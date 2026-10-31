package com.dailyflow.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.*
import com.dailyflow.app.domain.*
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w320dp-h640dp")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveUiTest{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as DailyFlowApplication
    private val model get()=ViewModelProvider(compose.activity)[DailyViewModel::class.java]
    private val planner get()=ViewModelProvider(compose.activity)[PlanningViewModel::class.java]
    private var scheme:ColorScheme?=null
    private fun compact(dark:Boolean,scale:Float=2f){compose.runOnUiThread{
        compose.activity.setContent{val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,scale)){
                FlowTheme(dark){val colors=MaterialTheme.colorScheme;SideEffect{scheme=colors};Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopStart){Box(Modifier.requiredSize(320.dp,480.dp)){DailyFlowApp(model,planner)}}}
            }
        }
    }}
    private fun within(tag:String){val b=compose.onNodeWithTag(tag).getBoundsInRoot();assertTrue("left",b.left.value>=-1);assertTrue("right",b.right.value<=321);assertTrue("bottom",b.bottom.value<=481);assertTrue("height",b.bottom.value-b.top.value>=47)}
    @Test fun largeTypeTaskFormKeepsSaveAndCancelInReducedViewport(){
        compact(true);compose.runOnUiThread{model.newTask()};compose.onNodeWithTag("taskTitle").performTextInput("小屏大字标题")
        compose.onNodeWithTag("saveTask").assertIsDisplayed();compose.onNodeWithTag("cancelEditor").assertIsDisplayed();within("saveTask");within("cancelEditor")
        assertEquals(Color(0xFF0E1522),scheme!!.background)
    }
    @Test fun largeTypeRuleValidationShowsRelevantInputAndAction(){
        val summary=runBlocking{app.repository.create("大字规则","",LocalDate.now(),RecurrenceRule(Frequency.DAILY));app.repository.observeSeries().first().single()}
        compact(false);compose.runOnUiThread{planner.openRule(summary)}
        compose.onNodeWithTag("ruleInterval").performScrollTo().performTextReplacement("0");compose.onNodeWithTag("previewRule").performClick();compose.waitUntil(10000){planner.state.value.error!=null}
        compose.onNodeWithTag("ruleInterval").assertIsFocused().assertIsDisplayed();compose.onNodeWithTag("previewRule").assertIsDisplayed();within("previewRule")
    }
    @Test fun lightAndDarkSemanticColorsHaveReadableContrast(){
        fun contrast(a:Color,b:Color):Float{val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)}
        for(dark in listOf(false,true)){compact(dark,1f);compose.waitForIdle();val s=scheme!!
            assertTrue(contrast(s.onSurface,s.surface)>=4.5f);assertTrue(contrast(s.onSurfaceVariant,s.background)>=4.5f);assertTrue(contrast(s.onPrimary,s.primary)>=4.5f)
        }
    }
    @Test fun compactCalendarKeepsConfirmAndCancelAvailable(){compact(true);compose.onNodeWithTag("listDatePicker").performClick();compose.onNodeWithText("确定日期").assertIsDisplayed();compose.onNodeWithText("取消").assertIsDisplayed();compose.onNodeWithText("取消").performClick()}
    @Test fun calendarBoundsProtectRuleDates(){val date=LocalDate.of(2026,10,28);assertFalse(selectableCalendarDate(date.minusDays(1),date,null));assertTrue(selectableCalendarDate(date,date,null));assertFalse(selectableCalendarDate(date.plusDays(1),null,date));assertTrue(selectableCalendarDate(date,null,date))}
}
