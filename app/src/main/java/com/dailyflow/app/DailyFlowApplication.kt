package com.dailyflow.app

import android.app.Application
import com.dailyflow.app.data.DailyFlowDatabase
import com.dailyflow.app.data.RoomTaskRepository
import com.dailyflow.app.data.UpdatingTaskRepository
import com.dailyflow.app.widget.WidgetUpdates
import com.dailyflow.app.domain.TaskRepository

class DailyFlowApplication : Application() {
    val database: DailyFlowDatabase by lazy { DailyFlowDatabase.open(this) }
    val repository: TaskRepository by lazy { UpdatingTaskRepository(RoomTaskRepository(database)) { WidgetUpdates.request(this) } }
}
