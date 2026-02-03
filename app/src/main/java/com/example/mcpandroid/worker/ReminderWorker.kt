package com.example.mcpandroid.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.mcpandroid.data.local.ReminderDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: ReminderDao
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_REMINDER_ID, -1)
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        if (id < 0) return Result.failure()
        NotificationHelper.showReminderNotification(applicationContext, id, text)
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_TEXT = "text"
    }
}
