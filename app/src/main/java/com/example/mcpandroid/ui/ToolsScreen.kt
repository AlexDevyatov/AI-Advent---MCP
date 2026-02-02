package com.example.mcpandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.mcpandroid.mcp.AgentResult
import com.example.mcpandroid.mcp.McpTool
import com.example.mcpandroid.mcp.ToolsUiState
import com.example.mcpandroid.mcp.ToolsViewModel

/**
 * Экран: список MCP-инструментов + блок «Спросить агента» (День 12).
 * Агент получает запрос, выбирает инструмент (get_open_tasks) и вызывает его через MCP.
 */
@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val editUrl by viewModel.editUrl.collectAsState()
    val toolsListVisible by viewModel.toolsListVisible.collectAsState()
    val agentQuery by viewModel.agentQuery.collectAsState()
    val lastAgentResult by viewModel.lastAgentResult.collectAsState()
    val agentLoading by viewModel.agentLoading.collectAsState()
    val newTaskTitle by viewModel.newTaskTitle.collectAsState()
    val addTaskMessage by viewModel.addTaskMessage.collectAsState()
    val addTaskLoading by viewModel.addTaskLoading.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        ServerUrlSection(
            url = editUrl,
            onUrlChange = viewModel::setEditUrl,
            onConnect = viewModel::connect,
            onHideTools = viewModel::hideToolsList,
            toolsListVisible = toolsListVisible,
        )
        AddTaskSection(
            title = newTaskTitle,
            onTitleChange = viewModel::setNewTaskTitle,
            onAdd = viewModel::addTask,
            message = addTaskMessage,
            loading = addTaskLoading,
            onDismissMessage = viewModel::clearAddTaskMessage,
        )
        AgentSection(
            query = agentQuery,
            onQueryChange = viewModel::setAgentQuery,
            onAsk = viewModel::askAgent,
            lastResult = lastAgentResult,
            loading = agentLoading,
        )
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (val state = uiState) {
                is ToolsUiState.Idle -> IdleContent()
                is ToolsUiState.Loading -> LoadingContent()
                is ToolsUiState.Success -> if (toolsListVisible) {
                    ToolsList(tools = state.tools, onHide = viewModel::hideToolsList)
                } else {
                    IdleContent()
                }
                is ToolsUiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Нажмите «Подключиться», чтобы загрузить список инструментов",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AddTaskSection(
    title: String,
    onTitleChange: (String) -> Unit,
    onAdd: () -> Unit,
    message: String?,
    loading: Boolean,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Добавить задачу",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Название задачи") },
                enabled = !loading,
            )
            Button(onClick = onAdd, enabled = !loading && title.isNotBlank()) {
                Text(if (loading) "…" else "Добавить")
            }
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AgentSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onAsk: () -> Unit,
    lastResult: AgentResult?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Спросить агента (День 12)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Сколько сейчас открытых задач?") },
            enabled = !loading,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onAsk, enabled = !loading) {
                Text(if (loading) "…" else "Спросить")
            }
        }
        lastResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Ваш запрос: ${result.query}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Ответ агента: ${result.response}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerUrlSection(
    url: String,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onHideTools: () -> Unit,
    toolsListVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "URL MCP-сервера",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("http://10.0.2.2:8765") },
            supportingText = {
                Text("Эмулятор: 10.0.2.2:8765. Реальное устройство: IP ПК, напр. 192.168.1.100:8765")
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (toolsListVisible) {
                Button(onClick = onHideTools) {
                    Text("Скрыть инструменты")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Подключиться")
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Подключение к MCP…",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ToolsList(
    tools: List<McpTool>,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MCP Tools",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = onHide) {
                Text("Скрыть")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                items(tools, key = { it.name }) { tool ->
                    ToolCard(tool = tool)
                }
            },
        )
    }
}

@Composable
private fun ToolCard(
    tool: McpTool,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = tool.title ?: tool.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Ошибка",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Повторить")
        }
    }
}
