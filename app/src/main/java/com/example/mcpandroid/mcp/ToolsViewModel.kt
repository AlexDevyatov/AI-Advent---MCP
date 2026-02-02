package com.example.mcpandroid.mcp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние экрана со списком MCP-инструментов.
 */
sealed class ToolsUiState {
    data object Loading : ToolsUiState()
    data class Success(val tools: List<McpTool>) : ToolsUiState()
    data class Error(val message: String) : ToolsUiState()
}

/**
 * ViewModel: подключается к MCP и запрашивает tools/list.
 * URL сервера хранится в SharedPreferences — на реальном устройстве укажите IP ПК (например http://192.168.1.100:8765).
 */
class ToolsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = McpServerPrefs(application)

    /** Текущий URL сервера (из настроек). */
    private val _serverUrl = MutableStateFlow(prefs.getServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    /** Текст в поле ввода URL (для редактирования перед подключением). */
    private val _editUrl = MutableStateFlow(prefs.getServerUrl())
    val editUrl: StateFlow<String> = _editUrl.asStateFlow()

    private val _uiState = MutableStateFlow<ToolsUiState>(ToolsUiState.Loading)
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        loadTools()
    }

    fun setEditUrl(url: String) {
        _editUrl.value = url
    }

    /** Сохранить введённый URL и подключиться к MCP. */
    fun connect() {
        val url = _editUrl.value.trim()
        if (url.isBlank()) return
        prefs.setServerUrl(url)
        _serverUrl.value = url
        _uiState.value = ToolsUiState.Loading
        loadTools()
    }

    fun retry() {
        _uiState.value = ToolsUiState.Loading
        loadTools()
    }

    private fun loadTools() {
        viewModelScope.launch {
            val url = _serverUrl.value
            val result = withContext(Dispatchers.IO) {
                McpClient(url).fetchTools()
            }
            _uiState.value = result.fold(
                onSuccess = { ToolsUiState.Success(it) },
                onFailure = { ToolsUiState.Error(it.message ?: "Unknown error") },
            )
        }
    }
}
