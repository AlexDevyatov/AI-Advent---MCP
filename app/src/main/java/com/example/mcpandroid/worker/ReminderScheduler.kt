package com.example.mcpandroid.worker

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.mcpandroid.data.local.Reminder
import java.time.Instant
import java.util.concurrent.TimeUnit

object ReminderScheduler {

    private const val TAG_ALL_REMINDERS = "reminder"

    fun schedule(context: Context, reminder: Reminder) {
        val due = reminder.dueDatetime ?: return
        if (reminder.completed) return
        val normalized = due.replace(" ", "T").let { s ->
            if (s.contains("Z") || s.contains("+") || (s.length > 11 && s.indexOf("-", 11) >= 0)) s else s + "Z"
        }
        val instant = try {
            Instant.parse(normalized)
        } catch (_: Exception) {
            return
        }
        val delayMs = instant.toEpochMilli() - System.currentTimeMillis()
        if (delayMs <= 0) return
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                ReminderWorker.KEY_REMINDER_ID to reminder.id,
                ReminderWorker.KEY_TEXT to reminder.text
            ))
            .addTag(TAG_ALL_REMINDERS)
            .addTag("reminder_${reminder.id}")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun cancel(context: Context, reminderId: Int) {
        WorkManager.getInstance(context).cancelAllWorkByTag("reminder_$reminderId")
    }

    /** Отменить все запланированные напоминания (для очистки списка). */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_ALL_REMINDERS)
    }
}
