package com.dailyflow.app.data

import android.content.Context
import com.dailyflow.app.reminders.*
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TaskEntity::class, RecurrenceRuleEntity::class, TaskDayStateEntity::class,
    TaskExceptionEntity::class, CategoryEntity::class, RecurrenceVersionEntity::class, ReminderRuleEntity::class, ReminderOverrideEntity::class, ReminderDeliveryEntity::class, ReminderAlarmEntity::class], version = 5, exportSchema = true)
@TypeConverters(RoomConverters::class)
abstract class DailyFlowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    companion object {
        fun open(context: Context, name: String = "dailyflow.db"): DailyFlowDatabase =
            Room.databaseBuilder(context.applicationContext, DailyFlowDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN scheduledMinute INTEGER")
        db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE task_exceptions ADD COLUMN scheduledMinuteOverride INTEGER")
        db.execSQL("ALTER TABLE task_exceptions ADD COLUMN timeOverridden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE task_exceptions ADD COLUMN priorityOverride INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN stateRevision INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3,4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS recurrence_versions (taskId TEXT NOT NULL, effectiveFrom INTEGER NOT NULL, frequency TEXT NOT NULL, interval INTEGER NOT NULL, weekdayMask INTEGER NOT NULL, untilDate INTEGER, PRIMARY KEY(taskId,effectiveFrom), FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_rules (taskId TEXT NOT NULL, enabled INTEGER NOT NULL, minute INTEGER NOT NULL, offsetDays INTEGER NOT NULL, PRIMARY KEY(taskId), FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_overrides (taskId TEXT NOT NULL, date INTEGER NOT NULL, enabled INTEGER NOT NULL, minute INTEGER NOT NULL, offsetDays INTEGER NOT NULL, PRIMARY KEY(taskId,date), FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_deliveries (taskId TEXT NOT NULL, date INTEGER NOT NULL, deliveredAt INTEGER NOT NULL, status TEXT NOT NULL, PRIMARY KEY(taskId,date))")
        db.execSQL("CREATE TABLE IF NOT EXISTS reminder_alarm (slot INTEGER NOT NULL, taskId TEXT NOT NULL, date INTEGER NOT NULL, dueAt INTEGER NOT NULL, token TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, PRIMARY KEY(slot))")
    }
}
