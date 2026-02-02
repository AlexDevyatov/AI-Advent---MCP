package com.example.mcpandroid.mcp

import org.json.JSONObject

/**
 * Простой агент День 12: получает запрос пользователя, выбирает MCP-инструмент и вызывает его.
 * Пайплайн: Agent → MCP (tools/call) → Tool → Result.
 *
 * Логика выбора инструмента (без LLM, демо):
 * — если в запросе есть "задач" / "открытых" / "tasks" → вызываем get_open_tasks;
 * — иначе возвращаем подсказку.
 *
 * Вызов через MCP: агент вызывает McpClient.callTool(name, arguments), сервер выполняет
 * инструмент и возвращает JSON (например {"open_tasks": 5}), агент форматирует ответ для пользователя.
 */
class McpAgent(private val mcpClient: McpClient) {

    fun processPrompt(prompt: String): Result<String> = runCatching {
        val normalized = prompt.trim().lowercase()
        when {
            // Выбор инструмента по ключевым словам (демо-агент без LLM)
            normalized.contains("задач") || normalized.contains("открытых") ||
            normalized.contains("tasks") || normalized.contains("open") -> callGetOpenTasks()
            else -> "Задайте вопрос про открытые задачи, например: «Сколько сейчас открытых задач?»"
        }
    }

    /**
     * Вызов инструмента get_open_tasks через MCP.
     * Сервер возвращает JSON: {"open_tasks": N, "tasks": [{"id", "title", "status"}, ...]}.
     * Форматируем: количество + нумерованный список задач.
     */
    private fun callGetOpenTasks(): String {
        val result = mcpClient.callTool("get_open_tasks", emptyMap())
            .getOrElse { e -> return "Ошибка: ${e.message}" }
        if (result.isError) return "Инструмент вернул ошибку: ${result.text}"
        val text = result.text
        return try {
            val json = JSONObject(text)
            val count = json.optInt("open_tasks", -1)
            val tasksArray = json.optJSONArray("tasks")
            val sb = StringBuilder()
            sb.append("Открытых задач: $count")
            if (tasksArray != null && tasksArray.length() > 0) {
                sb.append("\n\n")
                for (i in 0 until tasksArray.length()) {
                    val task = tasksArray.getJSONObject(i)
                    val title = task.optString("title", "?")
                    sb.append("${i + 1}. $title\n")
                }
            }
            sb.toString()
        } catch (_: Exception) {
            "Ответ сервера: $text"
        }
    }
}
