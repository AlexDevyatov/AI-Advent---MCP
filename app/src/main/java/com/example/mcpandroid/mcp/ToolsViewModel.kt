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
 * Состояние экрана со списком MCP-инструментов и ответами агента.
 */
sealed class ToolsUiState {
    /** Список tools не загружался — показывать по нажатию «Подключиться». */
    data object Idle : ToolsUiState()
    data object Loading : ToolsUiState()
    data class Success(val tools: List<McpTool>) : ToolsUiState()
    data class Error(val message: String) : ToolsUiState()
}

/** Результат запроса агенту (День 12). */
data class AgentResult(
    val query: String,
    val response: String,
)

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

    private val _uiState = MutableStateFlow<ToolsUiState>(ToolsUiState.Idle)
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    /** Показывать ли список инструментов (включается после «Подключиться», скрывается по кнопке). */
    private val _toolsListVisible = MutableStateFlow(false)
    val toolsListVisible: StateFlow<Boolean> = _toolsListVisible.asStateFlow()

    /** Текст запроса агенту (поле ввода). */
    private val _agentQuery = MutableStateFlow("")
    val agentQuery: StateFlow<String> = _agentQuery.asStateFlow()

    /** Последний результат агента: запрос + ответ (День 12). */
    private val _lastAgentResult = MutableStateFlow<AgentResult?>(null)
    val lastAgentResult: StateFlow<AgentResult?> = _lastAgentResult.asStateFlow()

    /** Идёт ли выполнение запроса агенту. */
    private val _agentLoading = MutableStateFlow(false)
    val agentLoading: StateFlow<Boolean> = _agentLoading.asStateFlow()

    /** Текст новой задачи (поле «Добавить задачу»). */
    private val _newTaskTitle = MutableStateFlow("")
    val newTaskTitle: StateFlow<String> = _newTaskTitle.asStateFlow()

    /** Сообщение после добавления задачи (успех/ошибка). */
    private val _addTaskMessage = MutableStateFlow<String?>(null)
    val addTaskMessage: StateFlow<String?> = _addTaskMessage.asStateFlow()

    /** Идёт ли добавление задачи. */
    private val _addTaskLoading = MutableStateFlow(false)
    val addTaskLoading: StateFlow<Boolean> = _addTaskLoading.asStateFlow()

    init {
        // Список tools не грузим при старте — только по нажатию «Подключиться»
    }

    fun setAgentQuery(query: String) {
        _agentQuery.value = query
    }

    /** Отправить запрос агенту: агент выберет инструмент и вызовет его через MCP. */
    fun askAgent() {
        val query = _agentQuery.value.trim()
        if (query.isBlank()) return
        val url = _serverUrl.value
        viewModelScope.launch {
            _agentLoading.value = true
            val result = withContext(Dispatchers.IO) {
                val client = McpClient(url)
                val agent = McpAgent(client)
                agent.processPrompt(query)
            }
            _agentLoading.value = false
            _lastAgentResult.value = AgentResult(
                query = query,
                response = result.getOrElse { "Ошибка: ${it.message}" },
            )
        }
    }

    fun setEditUrl(url: String) {
        _editUrl.value = url
    }

    /** Сохранить URL и загрузить список инструментов; после успеха показываем список. */
    fun connect() {
        val url = _editUrl.value.trim()
        if (url.isBlank()) return
        prefs.setServerUrl(url)
        _serverUrl.value = url
        _uiState.value = ToolsUiState.Loading
        loadTools(showListOnSuccess = true)
    }

    /** Скрыть список инструментов (по кнопке «Скрыть инструменты»). */
    fun hideToolsList() {
        _toolsListVisible.value = false
    }

    fun retry() {
        _uiState.value = ToolsUiState.Loading
        loadTools(showListOnSuccess = true)
    }

    private fun loadTools(showListOnSuccess: Boolean = false) {
        viewModelScope.launch {
            val url = _serverUrl.value
            val result = withContext(Dispatchers.IO) {
                McpClient(url).fetchTools()
            }
            _uiState.value = result.fold(
                onSuccess = { ToolsUiState.Success(it) },
                onFailure = { ToolsUiState.Error(it.message ?: "Unknown error") },
            )
            if (showListOnSuccess && result.isSuccess) {
                _toolsListVisible.value = true
            }
        }
    }

    fun setNewTaskTitle(title: String) {
        _newTaskTitle.value = title
        _addTaskMessage.value = null
    }

    /** Добавить задачу через MCP (add_task). */
    fun addTask() {
        val title = _newTaskTitle.value.trim()
        if (title.isBlank()) return
        val url = _serverUrl.value
        viewModelScope.launch {
            _addTaskLoading.value = true
            _addTaskMessage.value = null
            val result = withContext(Dispatchers.IO) {
                McpClient(url).callTool("add_task", mapOf("title" to title))
            }
            _addTaskLoading.value = false
            _addTaskMessage.value = result.fold(
                onSuccess = { "Задача добавлена" },
                onFailure = { "Ошибка: ${it.message}" },
            )
            if (result.isSuccess) _newTaskTitle.value = ""
        }
    }

    fun clearAddTaskMessage() {
        _addTaskMessage.value = null
    }
}
