package com.dailyflow.app.widget

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.color.ColorProvider
import com.dailyflow.app.DailyFlowApplication
import com.dailyflow.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.LocalDate

private val ink = ColorProvider(day = Color(0xFF172033), night = Color(0xFFF1F5F9))
private val muted = ColorProvider(day = Color(0xFF475569), night = Color(0xFFCBD5E1))
private val surface = ColorProvider(day = Color(0xFFF5F7FF), night = Color(0xFF182131))
private val accent = ColorProvider(day = Color(0xFF2454C5), night = Color(0xFFAAC7FF))

class DailyTaskWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error) {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(240.dp, 280.dp), DpSize(320.dp, 380.dp)))
    override val stateDefinition = null
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as DailyFlowApplication).repository
        val dates = flow {
            while (true) {
                emit(LocalDate.now())
                delay((nextWidgetMidnight() - System.currentTimeMillis()).coerceAtLeast(1000))
            }
        }.distinctUntilChanged()
        val snapshots = dates.flatMapLatest { day ->
            repository.observeDay(day).map { WidgetSnapshot(day, it) }
                .catch { emit(WidgetSnapshot(day, error = true)) }
        }
        provideContent {
            val state by snapshots.collectAsState(WidgetSnapshot(LocalDate.now(), loading = true))
            DailyWidgetContent(state)
        }
    }
}

@Composable
internal fun DailyWidgetContent(state: WidgetSnapshot) {
    val context = LocalContext.current
    val layout = widgetLayoutPolicy(LocalSize.current.width.value,LocalSize.current.height.value,context.resources.configuration.fontScale)
    val limit = layout.visibleRows
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().background(surface).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(WidgetNavigation.homeIntent(context)))) {
                if(layout.showBrand)Text("DailyFlow", style = TextStyle(color = accent, fontSize = 12.sp))
                Text(state.date.toString(), maxLines = 2, style = TextStyle(color = ink, fontSize = layout.dateFontSize.sp, fontWeight = FontWeight.Bold))
            }
            Box(GlanceModifier.size(48.dp).clickable(actionRunCallback<RefreshWidgetAction>()), contentAlignment = Alignment.Center) {
                Image(provider=ImageProvider(R.drawable.ic_widget_refresh),contentDescription="刷新今日任务",modifier=GlanceModifier.size(24.dp),colorFilter=ColorFilter.tint(accent))
            }
        }
        Text(when { state.loading -> "加载中…"; state.error -> "计数暂不可用"; else -> if(!layout.showBrand)"完成 "+state.completedCount+" / "+state.tasks.size else "已完成 "+state.completedCount+" / "+state.tasks.size }, style = TextStyle(color = muted, fontSize = 13.sp))
        Spacer(GlanceModifier.height(4.dp))
        when {
            state.loading -> Text("正在加载今日任务…", style = TextStyle(color = muted))
            state.error -> Text("暂时无法读取任务，请点刷新重试", style = TextStyle(color = ink))
            state.tasks.isEmpty() -> Text("今天暂无任务，点此打开 App 添加", modifier = GlanceModifier.fillMaxWidth().padding(vertical = 20.dp)
                .clickable(actionStartActivity(WidgetNavigation.homeIntent(context))), style = TextStyle(color = muted))
            else -> state.tasks.take(limit).forEach { task ->
                Row(GlanceModifier.fillMaxWidth().height(layout.rowHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(48.dp).clickable(actionRunCallback<CompleteWidgetAction>(WidgetActions.parameters(task)))
                        .semantics { contentDescription = (if (task.completed) "撤销完成：" else "完成：")+task.title },
                        contentAlignment = Alignment.Center) {
                        Image(provider=ImageProvider(if(task.completed)R.drawable.ic_widget_done else R.drawable.ic_widget_empty),contentDescription=null,modifier=GlanceModifier.size(26.dp),colorFilter=ColorFilter.tint(accent))
                    }
                    Text((if (task.completed) "已完成 · " else "")+task.title, maxLines = 2,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight().clickable(actionStartActivity(WidgetNavigation.detailIntent(context, task.taskId, task.date))).padding(vertical = 5.dp),
                        style = TextStyle(color = if (task.completed) muted else ink, fontSize = 14.sp))
                }
            }
        }
        if (state.tasks.size > limit) Box(GlanceModifier.fillMaxWidth().height(layout.footerHeight.dp).clickable(actionStartActivity(WidgetNavigation.homeIntent(context))),contentAlignment=Alignment.CenterStart) {
            Text(if(context.resources.configuration.fontScale>=2.5f)"打开全部" else if(!layout.showBrand)"打开全部 · "+state.tasks.size+" 项" else "还有 "+(state.tasks.size-limit)+" 项 · 打开全部", style=TextStyle(color=accent,fontSize=12.sp),maxLines=2)
        }
    }
}

class DailyTaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DailyTaskWidget()
    override fun onUpdate(context: Context, manager: android.appwidget.AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids); WidgetUpdates.schedule(context)
    }
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetUpdates.schedule(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetUpdates.cancel(context) }
}
