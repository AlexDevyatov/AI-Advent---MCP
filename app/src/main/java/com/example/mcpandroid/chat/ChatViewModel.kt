package com.example.mcpandroid.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mcpandroid.agent.AgentManager
import com.example.mcpandroid.data.ReminderRepository
import com.example.mcpandroid.data.local.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(val role: String, val text: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentManager: AgentManager,
    private val reminderRepository: ReminderRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val reminders: StateFlow<List<Reminder>> = reminderRepository
        .upcomingReminders(168)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendMessage(text: String) {
        if (text.isBlank() || _loading.value) return
        val userMsg = ChatMessage("user", text)
        _messages.value = _messages.value + userMsg
        _loading.value = true
        viewModelScope.launch {
            try {
                agentManager.send(text)
                    .onSuccess { reply ->
                        _messages.value = _messages.value + ChatMessage("assistant", reply)
                    }
                    .onFailure {
                        _messages.value = _messages.value + ChatMessage("assistant", "Ошибка: ${it.message}")
                    }
            } finally {
                _loading.value = false
            }
        }
    }

    fun markReminderCompletedFromNotification(reminderId: Int) {
        viewModelScope.launch {
            agentManager.syncRemindersFromMcp()
            // MCP mark_reminder_completed + Room already updated by MainActivity intent handling
        }
    }
}
