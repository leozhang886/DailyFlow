package com.dailyflow.app.widget

import android.content.res.Configuration
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.*
import androidx.test.core.app.ApplicationProvider
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.domain.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalGlanceRemoteViewsApi::class,ExperimentalCoroutinesApi::class)
class WidgetPresentationTest{
    private val app get()=ApplicationProvider.getApplicationContext<DailyFlowApplication>()
    @Before fun main(){Dispatchers.setMain(UnconfinedTestDispatcher())}
    @After fun reset(){Dispatchers.resetMain()}
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
    private suspend fun render(width:Int,height:Int,scale:Float):ViewGroup{
        val cfg=Configuration(app.resources.configuration).apply{fontScale=scale}
        @Suppress("DEPRECATION") val metrics=DisplayMetrics().apply{setTo(app.resources.displayMetrics);scaledDensity=density*scale}
        @Suppress("DEPRECATION") app.resources.updateConfiguration(cfg,metrics)
        val tasks=(1..7).map{TaskOccurrence("w-"+it,LocalDate.of(2026,10,28),"DF-E-长任务标题-"+it,"",it==1,false,false)}
        val result=GlanceRemoteViews().compose(app,DpSize(width.dp,height.dp)){DailyWidgetContent(WidgetSnapshot(LocalDate.of(2026,10,28),tasks))}
        val view=result.remoteViews.apply(app,null) as ViewGroup
        val w=(width*metrics.density).toInt();val h=(height*metrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
        return view
    }
    @Test fun compactAndLargeNormalSizesStillShowThreeAndFiveRows()=runTest{
        assertEquals(3,widgetLayoutPolicy(240f,280f,1f).visibleRows);assertEquals(5,widgetLayoutPolicy(320f,380f,1f).visibleRows)
    }
    @Test fun largeFontsReduceRowsRatherThanShrinkingTouchTargets(){
        val normal=widgetLayoutPolicy(240f,280f,1f);val large=widgetLayoutPolicy(240f,280f,2f)
        assertTrue(large.visibleRows<normal.visibleRows);assertTrue(large.rowHeight>=48f);assertFalse(large.showBrand)
    }
    @Test fun extremeFontSizeFallsBackToProgressAndOpenAll(){assertEquals(0,widgetLayoutPolicy(240f,280f,3f).visibleRows)}
    @Test fun largeFontRemoteViewsKeepTaskTitlesInsideBounds()=runTest{
        val root=render(240,280,2f);val titles=descendants(root).filterIsInstance<TextView>().filter{it.text.contains("DF-E-")}
        assertTrue(titles.size<3);assertTrue(titles.isNotEmpty())
        for(t in titles){val rect=Rect(0,0,t.width,t.height);root.offsetDescendantRectToMyCoords(t,rect);assertTrue("title outside widget",rect.bottom<=root.height);assertTrue("horizontal overflow",rect.right<=root.width)}
        assertTrue(descendants(root).filterIsInstance<TextView>().any{it.text.toString().startsWith("打开全部")})
    }
    @Test fun extremeFontRemoteViewsKeepProgressWithoutClaimingEmpty()=runTest{
        val texts=descendants(render(240,280,3f)).filterIsInstance<TextView>().map{it.text.toString()}
        assertFalse(texts.any{it.contains("DF-E-")});assertTrue(texts.contains("完成 1 / 7"));assertTrue(texts.contains("打开全部"));assertFalse(texts.any{it.contains("暂无任务")})
    }
    @Test fun refreshButtonKeepsFixedIconAndAccessibleTargetAtLargeFont()=runTest{
        val views=descendants(render(240,280,3f));val icon=views.filterIsInstance<android.widget.ImageView>().single{it.contentDescription=="刷新今日任务"}
        assertTrue(icon.width>0&&icon.height>0);assertEquals(icon.width,icon.height)
        var target:View=icon;while(!target.isClickable&&target.parent is View)target=target.parent as View
        assertTrue(target.isClickable);assertTrue(target.width>=48&&target.height>=48)
    }
    @Test fun normalRenderStillCountsAllTasksWhenTruncated()=runTest{
        val texts=descendants(render(240,280,1f)).filterIsInstance<TextView>().map{it.text.toString()}
        assertEquals(3,texts.count{it.contains("DF-E-")});assertTrue(texts.contains("已完成 1 / 7"));assertTrue(texts.contains("还有 4 项 · 打开全部"))
    }
}
