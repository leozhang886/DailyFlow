package com.dailyflow.app

import android.os.Bundle
import android.content.Intent
import com.dailyflow.app.widget.WidgetNavigation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dailyflow.app.ui.FlowTheme
import androidx.lifecycle.ViewModelProvider
import com.dailyflow.app.ui.DailyFlowApp
import com.dailyflow.app.ui.DailyViewModel
import com.dailyflow.app.ui.PlanningViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: DailyViewModel
    private lateinit var planner: PlanningViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as DailyFlowApplication).repository
        model = ViewModelProvider(this, DailyViewModel.Factory(repository))[DailyViewModel::class.java]
        planner=ViewModelProvider(this,PlanningViewModel.Factory(repository))[PlanningViewModel::class.java]
        if (savedInstanceState == null) handleWidgetIntent(intent)
        setContent { FlowTheme { DailyFlowApp(model,planner) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); handleWidgetIntent(intent)
    }
    private fun handleWidgetIntent(intent: Intent) {
        if(intent.data?.scheme=="dailyflow" && !planner.canNavigateAway())return
        WidgetNavigation.destination(intent)?.let { (id, date) -> model.openWidgetTask(id, date) }
        if (intent.dataString == "dailyflow://widget/today") model.openWidgetHome()
    }
}
