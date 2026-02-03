package com.example.mcpandroid.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mcpandroid.MainActivity
import com.example.mcpandroid.R

object NotificationHelper {
    const val CHANNEL_ID = "reminders"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_MARK_COMPLETED = "mark_completed"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showReminderNotification(context: Context, reminderId: Int, text: String) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_MARK_COMPLETED, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            reminderId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(reminderId, notification)
    }
}
