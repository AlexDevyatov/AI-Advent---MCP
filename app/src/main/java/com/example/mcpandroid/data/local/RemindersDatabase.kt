package com.example.mcpandroid.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Reminder::class], version = 1, exportSchema = false)
abstract class RemindersDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: RemindersDatabase? = null
        fun getDatabase(context: Context): RemindersDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, RemindersDatabase::class.java, "reminders.db")
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
