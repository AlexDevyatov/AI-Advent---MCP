package com.example.mcpandroid.data

import com.example.mcpandroid.data.local.Reminder
import com.example.mcpandroid.data.local.ReminderDao
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao
) {
    fun allReminders(): Flow<List<Reminder>> = dao.allReminders()

    fun upcomingReminders(hoursAhead: Int): Flow<List<Reminder>> {
        val now = Instant.now().atOffset(ZoneOffset.UTC)
        val from = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val to = now.plusHours(hoursAhead.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return dao.upcoming(from, to)
    }

    suspend fun replaceAll(reminders: List<Reminder>) {
        dao.deleteAll()
        if (reminders.isNotEmpty()) dao.insertAll(reminders)
    }

    suspend fun markCompleted(id: Int) {
        dao.markCompleted(id)
    }
}
