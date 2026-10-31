package com.dailyflow.app

import android.app.Application
import com.dailyflow.app.reminders.*
import com.dailyflow.app.data.DailyFlowDatabase
import com.dailyflow.app.data.RoomTaskRepository
import com.dailyflow.app.data.UpdatingTaskRepository
import com.dailyflow.app.widget.WidgetUpdates
import com.dailyflow.app.domain.TaskRepository

class DailyFlowApplication : Application() {
    val database: DailyFlowDatabase by lazy { DailyFlowDatabase.open(this) }
    val reminders: ReminderRuntime by lazy { ReminderRuntime(this, database) }
    val repository: TaskRepository by lazy { UpdatingTaskRepository(ReminderAwareRepository(RoomTaskRepository(database), reminders.engine)) { WidgetUpdates.request(this) } }
}
