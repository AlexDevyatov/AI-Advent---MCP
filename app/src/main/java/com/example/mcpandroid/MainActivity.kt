package com.example.mcpandroid

import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.mcpandroid.chat.ChatScreen
import com.example.mcpandroid.data.local.RemindersDatabase
import com.example.mcpandroid.ui.theme.MCPAndroidTheme
import com.example.mcpandroid.worker.NotificationHelper
import com.example.mcpandroid.worker.ReminderScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        NotificationHelper.ensureChannel(this)
        enableEdgeToEdge()
        setContent {
            MCPAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatScreen()
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent) {
        val reminderId = intent.getIntExtra(NotificationHelper.EXTRA_REMINDER_ID, -1)
        val markCompleted = intent.getBooleanExtra(NotificationHelper.EXTRA_MARK_COMPLETED, false)
        if (reminderId >= 0 && markCompleted) {
            val db = RemindersDatabase.getDatabase(this)
            CoroutineScope(Dispatchers.IO).launch {
                db.reminderDao().markCompleted(reminderId)
                ReminderScheduler.cancel(this@MainActivity, reminderId)
            }
        }
    }
}
