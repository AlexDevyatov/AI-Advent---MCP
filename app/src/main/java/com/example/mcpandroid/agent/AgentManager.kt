package com.example.mcpandroid.agent

import android.content.Context
import com.example.mcpandroid.data.local.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.mcpandroid.data.local.ReminderDao
import com.example.mcpandroid.data.remote.DeepSeekApi
import com.example.mcpandroid.data.remote.McpApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.mcpandroid.worker.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentManager @Inject constructor(
    private val deepSeekApi: DeepSeekApi,
    private val mcpApi: McpApi,
    private val reminderDao: ReminderDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        // FIX: улучшен промпт для строгого JSON + явный пример и формат даты
        const val SYSTEM_PROMPT = """Ты помощник по напоминаниям. Если пользователь просит действие — ответь ТОЛЬКО одной строкой JSON, без markdown и без текста до/после.
Формат строго: {"tool": "имя_инструмента", "args": {...}}

Инструменты (имена строго так):
- list_upcoming_reminders — показать напоминания. args: {} или {"hours_ahead": 0}
- add_reminder — добавить напоминание. args: {"text": "текст напоминания", "due_datetime": "дата в ISO 8601"}
- mark_reminder_completed — отметить выполненным. args: {"reminder_id": число}
- clear_all_reminders — удалить все напоминания (очистить список). args: {}

Важно: due_datetime только в формате ISO 8601: ГГГГ-ММ-ДДТЧЧ:мм:сс (например 2026-02-04T05:00:00). Для "завтра в 5 утра" вычисли дату и время и подставь в этот формат.

Пример вызова для "напомни завтра в 5 утра": {"tool": "add_reminder", "args": {"text": "Напоминание", "due_datetime": "2026-02-04T05:00:00"}}
Ещё пример: {"tool": "add_reminder", "args": {"text": "Позвонить", "due_datetime": "2026-02-04T10:00:00"}}

Если действие не требуется — ответь обычным текстом (без JSON)."""
    }

    // FIX: валидация due_datetime через ISO (локальное время или с Z)
    private fun isValidDueDatetime(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return try {
            val normalized = value.trim().replace("Z", "+00:00")
            java.time.OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            true
        } catch (_: DateTimeParseException) {
            try {
                java.time.LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                true
            } catch (_: DateTimeParseException) {
                false
            }
        }
    }

    // FIX: нормализация ответа LLM — убрать markdown-обёртку ```json ... ```
    private fun normalizeJsonFromLlm(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }

    suspend fun send(userMessage: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userMessage))
            }
            val body = JSONObject().put("model", "deepseek-chat").put("messages", messages)
            val requestBody = body.toString().toRequestBody("application/json".toMediaType())
            val responseBody = deepSeekApi.chat(requestBody)
            val response = JSONObject(responseBody.string())
            val choices = response.optJSONArray("choices") ?: return@withContext Result.failure(IllegalStateException("No choices"))
            val first = choices.optJSONObject(0) ?: return@withContext Result.failure(IllegalStateException("No choice"))
            val message = first.optJSONObject("message") ?: return@withContext Result.failure(IllegalStateException("No message"))
            val rawText = message.optString("content", "").trim()
            if (rawText.isEmpty()) return@withContext Result.failure(IllegalStateException("No content"))
            // FIX: нормализация перед парсингом (убираем ```json)
            val trimmed = normalizeJsonFromLlm(rawText)
            if (trimmed.startsWith("{")) {
                val toolResult = executeToolCall(trimmed)
                if (toolResult != null) return@withContext Result.success(toolResult)
                // FIX: самовосстановление — битый JSON не показываем, просим уточнить
                return@withContext Result.success(
                    "Не удалось выполнить команду. Попробуйте, например: «Напомни 4 февраля 2026 в 10:00» или «Покажи напоминания»."
                )
            }
            // LLM иногда отвечает текстом вида "tool reminder list args {}" — вызываем список напоминаний
            if (trimmed.contains("reminder", ignoreCase = true) && trimmed.contains("list", ignoreCase = true)) {
                val out = callMcpToolsList("list_upcoming_reminders", listOf(0))
                if (out != null) return@withContext Result.success(out)
            }
            Result.success(trimmed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeToolCall(jsonStr: String): String? {
        // FIX: безопасный парсинг — try/catch, fallback null
        val json = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val tool = json.optString("tool").takeIf { it.isNotEmpty() } ?: return null
        val args = json.optJSONObject("args") ?: JSONObject()
        when (tool) {
            "add_reminder" -> {
                val text = args.optString("text", "").trim()
                val dueRaw = if (args.has("due_datetime") && !args.isNull("due_datetime")) args.optString("due_datetime") else null
                val due = dueRaw?.takeIf { it.isNotBlank() }
                // FIX: валидация due_datetime перед вызовом MCP — при неверном формате просим уточнить
                if (due != null && !isValidDueDatetime(due)) {
                    return "Укажите дату в формате ГГГГ-ММ-ДДЧЧ:мм:сс, например 2026-02-04T05:00:00."
                }
                val out = callMcpToolsList("add_reminder", listOf(text, due))
                if (out != null) syncRemindersFromMcp()
                return out ?: "Напоминание не удалось добавить. Проверьте подключение к серверу."
            }
            "list_upcoming_reminders" -> {
                val hours = args.optInt("hours_ahead", 0).coerceIn(0, 8760)
                val out = callMcpToolsList("list_upcoming_reminders", listOf(hours))
                return out ?: "{\"reminders\":[]}"
            }
            "mark_reminder_completed" -> {
                val id = args.optInt("reminder_id", -1)
                if (id >= 0) {
                    val out = callMcpToolsList("mark_reminder_completed", listOf(id))
                    if (out != null) {
                        reminderDao.markCompleted(id)
                        ReminderScheduler.cancel(context, id)
                    }
                    return out ?: "Готово."
                }
                return "Укажите номер напоминания (reminder_id)."
            }
            "clear_all_reminders" -> {
                val out = callMcpToolsList("clear_all_reminders", emptyList())
                if (out != null) {
                    reminderDao.deleteAll()
                    ReminderScheduler.cancelAll(context)
                }
                return out ?: "Очистка не выполнена. Проверьте подключение к серверу."
            }
        }
        return null
    }

    // FIX: обёрнут в try/catch, обрабатываем JSON-RPC error и HTTP/таймаут
    private suspend fun callMcpToolsList(toolName: String, args: List<Any?>): String? {
        val argsJson = JSONObject().apply {
            when (toolName) {
                "add_reminder" -> {
                    (args.getOrNull(0) as? String)?.let { put("text", it) }
                    (args.getOrNull(1) as? String)?.let { put("due_datetime", it) }
                }
                "list_upcoming_reminders" -> put("hours_ahead", args.getOrNull(0) as? Int ?: 0)
                "mark_reminder_completed" -> (args.getOrNull(0) as? Int)?.let { put("reminder_id", it) }
                "clear_all_reminders" -> { /* args не нужны */ }
                else -> {}
            }
        }
        val id = "req-${System.currentTimeMillis()}"
        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", argsJson)
            })
        }
        return try {
            val body = request.toString().toRequestBody("application/json".toMediaType())
            val responseBody = mcpApi.mcp(body)
            val response = JSONObject(responseBody.string())
            // FIX: проверка JSON-RPC error (таймаут, 500, неверный ответ MCP)
            val err = response.optJSONObject("error")
            if (err != null) {
                val msg = err.optString("message", "Ошибка MCP")
                return "❌ $msg"
            }
            val result = response.optJSONObject("result") ?: return "❌ Нет ответа от сервера напоминаний."
            val content = result.optJSONArray("content") ?: return "❌ Пустой ответ."
            val first = content.optJSONObject(0) ?: return "❌ Пустой ответ."
            val t = first.optString("text").trim()
            if (t.isEmpty()) return "❌ Пустой ответ."
            // Если MCP вернул JSON с полем error — показываем пользователю (префикс ❌ уже может быть в тексте)
            if (t.startsWith("{")) {
                try {
                    val parsed = JSONObject(t)
                    val errMsg = parsed.optString("error", "").trim()
                    if (errMsg.isNotEmpty()) return if (errMsg.startsWith("❌")) errMsg else "❌ $errMsg"
                } catch (_: Exception) { /* не JSON или без error — возвращаем как есть */ }
            }
            t
        } catch (e: Exception) {
            val msg = e.message ?: "Ошибка сети"
            "❌ Сервер напоминаний недоступен: $msg"
        }
    }

    // FIX: try/catch — не падать при таймауте/500/битом ответе
    suspend fun syncRemindersFromMcp() {
        try {
            val id = "req-${System.currentTimeMillis()}"
            val request = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "list_upcoming_reminders")
                    put("arguments", JSONObject().put("hours_ahead", 0))
                })
            }
            val body = request.toString().toRequestBody("application/json".toMediaType())
            val responseBody = mcpApi.mcp(body)
            val response = JSONObject(responseBody.string())
            if (response.has("error")) return
            val result = response.optJSONObject("result") ?: return
            val content = result.optJSONArray("content") ?: return
            val first = content.optJSONObject(0) ?: return
            val text = first.optString("text").takeIf { it.isNotEmpty() } ?: return
            val json = try { JSONObject(text) } catch (_: Exception) { return }
            val arr = json.optJSONArray("reminders") ?: return
            val list = mutableListOf<Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Reminder(
                    id = o.getInt("id"),
                    text = o.getString("text"),
                    dueDatetime = if (o.has("due_datetime") && !o.isNull("due_datetime")) o.getString("due_datetime") else null,
                    completed = o.optBoolean("completed", false)
                ))
            }
            reminderDao.deleteAll()
            if (list.isNotEmpty()) reminderDao.insertAll(list)
            list.filter { !it.completed && it.dueDatetime != null }.forEach { ReminderScheduler.schedule(context, it) }
        } catch (_: Exception) {
            // Тихо игнорируем — синхронизация повторится при следующем действии
        }
    }
}
