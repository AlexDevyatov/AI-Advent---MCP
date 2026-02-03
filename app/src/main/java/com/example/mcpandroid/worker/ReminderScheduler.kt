package com.example.mcpandroid.worker

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.mcpandroid.data.local.Reminder
import java.time.Instant
import java.util.concurrent.TimeUnit

object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val TAG_ALL_REMINDERS = "reminder"

    fun schedule(context: Context, reminder: Reminder) {
        val due = reminder.dueDatetime ?: run {
            Log.d(TAG, "schedule: reminder id=${reminder.id} has no due_datetime, skip")
            return
        }
        if (reminder.completed) {
            Log.d(TAG, "schedule: reminder id=${reminder.id} completed, skip")
            return
        }
        val normalized = due.replace(" ", "T").let { s ->
            if (s.contains("Z") || s.contains("+") || (s.length > 11 && s.indexOf("-", 11) >= 0)) s else s + "Z"
        }
        val instant = try {
            Instant.parse(normalized)
        } catch (e: Exception) {
            Log.w(TAG, "schedule: parse failed for due=$due normalized=$normalized", e)
            return
        }
        val delayMs = instant.toEpochMilli() - System.currentTimeMillis()
        if (delayMs <= 0) {
            Log.d(TAG, "schedule: reminder id=${reminder.id} due=$due is in the past (delayMs=$delayMs), skip")
            return
        }
        Log.d(TAG, "schedule: reminder id=${reminder.id} text=${reminder.text.take(30)} due=$due delayMs=$delayMs")
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
