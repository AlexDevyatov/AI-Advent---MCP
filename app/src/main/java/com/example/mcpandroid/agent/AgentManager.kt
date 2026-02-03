package com.example.mcpandroid.agent

import android.content.Context
import android.util.Log
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
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
        // FIX: улучшен промпт для строгого JSON + явный пример и формат даты + относительное время
        const val SYSTEM_PROMPT = """Ты помощник по напоминаниям. Если пользователь просит действие — ответь ТОЛЬКО одной строкой JSON, без markdown и без текста до/после.
Формат строго: {"tool": "имя_инструмента", "args": {...}}

Доступные инструменты (имена строго так):
- get_current_time — получить текущее время в ISO 8601 (UTC). args: {}
  Вызывай его первым, если пользователь указал относительное время: «через N минут/часов», «завтра в 9», «через 2 часа» и т.д. После получения времени агент подставит его в запрос и попросит тебя вызвать add_reminder с абсолютной датой.
- list_upcoming_reminders — показать напоминания. args: {} или {"hours_ahead": 0}
- add_reminder — добавить напоминание. args: {"text": "текст напоминания", "due_datetime": "дата в ISO 8601"}
  Для абсолютных дат («5 февраля в 10:00», «завтра в 5 утра») вычисли дату и вызови add_reminder сразу. Для относительных («через 10 минут», «через 2 часа») вызови сначала get_current_time.
- mark_reminder_completed — отметить выполненным. args: {"reminder_id": число}
- clear_all_reminders — удалить все напоминания. args: {}

Важно: due_datetime только в формате ISO 8601. Час — от 0 до 23 (не «25 часов»). При неверном времени агент вернёт ошибку. ГГГГ-ММ-ДДТЧЧ:мм:сс или с таймзоной (например 2026-02-04T05:00:00 или 2026-02-03T14:45:00+00:00).

Примеры:
«напомни завтра в 5 утра» → {"tool": "add_reminder", "args": {"text": "Напоминание", "due_datetime": "2026-02-04T05:00:00"}}
«напомни позвонить маме завтра в 18:00» → {"tool": "add_reminder", "args": {"text": "Позвонить маме", "due_datetime": "2026-02-04T18:00:00"}}
«напомни через 15 минут выпить воды» → сначала {"tool": "get_current_time", "args": {}}
«Что у меня запланировано?» / «покажи напоминания» → {"tool": "list_upcoming_reminders", "args": {}}
«Готово с ID 3» / «выполнено 3» → {"tool": "mark_reminder_completed", "args": {"reminder_id": 3}}
«Какая сегодня дата?» / «который час?» → {"tool": "get_current_time", "args": {}}
«напомни в 25 часов» — неверно (час 0–23), ответь текстом с просьбой уточнить.
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

    // FIX: валидация «25 часов» — час 0–23, минута 0–59
    private fun isReasonableTime(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        return try {
            val normalized = value.trim().replace("Z", "+00:00")
            val dt = OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            dt.hour in 0..23 && dt.minute in 0..59 && dt.second in 0..59
        } catch (_: DateTimeParseException) {
            try {
                val dt = LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                dt.hour in 0..23 && dt.minute in 0..59 && dt.second in 0..59
            } catch (_: DateTimeParseException) {
                false
            }
        }
    }

    /** Ограничение: напоминание не более чем на 30 дней вперёд. */
    private fun isDueWithinMaxDays(value: String?, maxDays: Long = 30): Boolean {
        if (value.isNullOrBlank()) return true
        return try {
            val instant = parseDueToInstant(value.trim()) ?: return true
            val now = Instant.now()
            val limit = now.plus(maxDays, ChronoUnit.DAYS)
            !instant.isBefore(now) && !instant.isAfter(limit)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseDueToInstant(value: String): Instant? {
        return try {
            val normalized = value.replace("Z", "+00:00")
            OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atOffset(ZoneOffset.UTC).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Запрос про относительное время («через N минут/часов») — нужна двухэтапка get_current_time → add_reminder. */
    private fun looksLikeRelativeTime(userMessage: String): Boolean {
        val lower = userMessage.lowercase()
        return lower.contains("через") && (lower.contains("минут") || lower.contains("час") || lower.contains("секунд"))
    }

    /** Похоже на команду агенту (напомни, покажи, запланировано, готово, дата, время) — для fallback при не-JSON. */
    private fun looksLikeCommand(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("напомни") || lower.contains("покажи") || lower.contains("запланировано") ||
            lower.contains("что у меня") || lower.contains("готово") || lower.contains("дата") || lower.contains("какая сегодня") ||
            lower.contains("время") || lower.contains("список напоминаний")
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
            Log.d("Agent", "send: userMessage=${userMessage.take(100)}")
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userMessage))
            }
            val body = JSONObject().put("model", "deepseek-chat").put("messages", messages)
            val requestBody = body.toString().toRequestBody("application/json".toMediaType())
            val responseBody = try { deepSeekApi.chat(requestBody) } catch (e: Exception) {
                Log.d("Agent", "DeepSeek API error: ${e.message}")
                return@withContext Result.failure(e)
            }
            val response = try { JSONObject(responseBody.string()) } catch (e: Exception) {
                Log.d("Agent", "Parse response error: ${e.message}")
                return@withContext Result.failure(IllegalStateException("Invalid API response"))
            }
            val choices = response.optJSONArray("choices") ?: return@withContext Result.failure(IllegalStateException("No choices"))
            val first = choices.optJSONObject(0) ?: return@withContext Result.failure(IllegalStateException("No choice"))
            val message = first.optJSONObject("message") ?: return@withContext Result.failure(IllegalStateException("No message"))
            val rawText = message.optString("content", "").trim()
            if (rawText.isEmpty()) return@withContext Result.failure(IllegalStateException("No content"))
            // FIX: нормализация перед парсингом (убираем ```json)
            val trimmed = normalizeJsonFromLlm(rawText)
            if (trimmed.startsWith("{")) {
                val firstJson = try { JSONObject(trimmed) } catch (_: Exception) { null }
                val firstTool = firstJson?.optString("tool", "")?.takeIf { it.isNotEmpty() }
                // FIX: «Какая сегодня дата?» — только get_current_time; «через 5 минут» — двухэтапка
                if (firstTool == "get_current_time") {
                    Log.d("Agent", "MCP call: get_current_time")
                    val currentTime = callMcpToolsList("get_current_time", emptyList())?.trim()
                    if (currentTime.isNullOrBlank()) {
                        return@withContext Result.success("❌ Не удалось получить текущее время. Проверьте подключение к серверу.")
                    }
                    if (!looksLikeRelativeTime(userMessage)) {
                        return@withContext Result.success("Сейчас: $currentTime (UTC).")
                    }
                    val followUpUser = "Текущее время: $currentTime. $userMessage"
                    val followUpMessages = JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        put(JSONObject().put("role", "user").put("content", followUpUser))
                    }
                    val followUpBody = JSONObject().put("model", "deepseek-chat").put("messages", followUpMessages)
                    val followUpResp = try { deepSeekApi.chat(followUpBody.toString().toRequestBody("application/json".toMediaType())) } catch (e: Exception) {
                        Log.d("Agent", "Follow-up LLM error: ${e.message}")
                        return@withContext Result.success("❌ Не удалось получить время для напоминания. Проверьте сеть.")
                    }
                    val followUpJson = try { JSONObject(followUpResp.string()) } catch (_: Exception) { null } ?: return@withContext Result.success("Не удалось разобрать ответ для напоминания.")
                    val followUpChoices = followUpJson.optJSONArray("choices") ?: return@withContext Result.success("Не удалось получить ответ для напоминания.")
                    val followUpMessage = followUpChoices.optJSONObject(0)?.optJSONObject("message") ?: return@withContext Result.success("Не удалось получить ответ для напоминания.")
                    val followUpRaw = followUpMessage.optString("content", "").trim()
                    val followUpTrimmed = normalizeJsonFromLlm(followUpRaw)
                    if (followUpTrimmed.startsWith("{")) {
                        val toolResult = executeToolCall(followUpTrimmed)
                        if (toolResult != null) return@withContext Result.success(toolResult)
                    }
                    return@withContext Result.success("Не удалось создать напоминание по относительному времени. Попробуйте указать дату явно, например: «Напомни 5 февраля в 10:00».")
                }
                Log.d("Agent", "MCP call: $firstTool")
                val toolResult = executeToolCall(trimmed)
                if (toolResult != null) return@withContext Result.success(toolResult)
                // FIX: самовосстановление — битый JSON не показываем, просим уточнить
                return@withContext Result.success(
                    "Не удалось выполнить команду. Попробуйте, например: «Напомни 4 февраля 2026 в 10:00» или «Покажи напоминания»."
                )
            }
            // FIX: fallback — если LLM не вернул JSON, но запрос похож на команду — повторить с примером
            if (looksLikeCommand(userMessage)) {
                Log.d("Agent", "Fallback: retry with JSON example")
                val retryUser = "$userMessage\n\nОтветь только одной строкой JSON, например: {\"tool\": \"add_reminder\", \"args\": {\"text\": \"...\", \"due_datetime\": \"2026-02-04T18:00:00\"}} или {\"tool\": \"list_upcoming_reminders\", \"args\": {}}."
                val retryMessages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", retryUser))
                }
                val retryBody = JSONObject().put("model", "deepseek-chat").put("messages", retryMessages)
                val retryResp = try { deepSeekApi.chat(retryBody.toString().toRequestBody("application/json".toMediaType())) } catch (_: Exception) { null }
                if (retryResp != null) {
                    val retryJson = try { JSONObject(retryResp.string()) } catch (_: Exception) { null }
                    val retryTrimmed = retryJson?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")?.let { normalizeJsonFromLlm(it.trim()) } ?: ""
                    if (retryTrimmed.startsWith("{")) {
                        val retryTool = try { JSONObject(retryTrimmed).optString("tool", "") } catch (_: Exception) { "" }
                        Log.d("Agent", "Fallback MCP call: $retryTool")
                        val retryResult = executeToolCall(retryTrimmed)
                        if (retryResult != null) return@withContext Result.success(retryResult)
                    }
                }
            }
            // LLM иногда отвечает текстом вида "tool reminder list args {}" — вызываем список напоминаний
            if (trimmed.contains("reminder", ignoreCase = true) && trimmed.contains("list", ignoreCase = true)) {
                val out = callMcpToolsList("list_upcoming_reminders", listOf(0))
                if (out != null) return@withContext Result.success(out)
            }
            if (looksLikeCommand(trimmed)) {
                val out = callMcpToolsList("list_upcoming_reminders", listOf(0))
                if (out != null && (trimmed.contains("запланировано", ignoreCase = true) || trimmed.contains("что у меня", ignoreCase = true))) return@withContext Result.success(out)
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
        Log.d("Agent", "executeToolCall: tool=$tool")
        val args = json.optJSONObject("args") ?: JSONObject()
        when (tool) {
            "get_current_time" -> {
                val out = callMcpToolsList("get_current_time", emptyList())
                return out ?: "❌ Не удалось получить текущее время."
            }
            "add_reminder" -> {
                val text = args.optString("text", "").trim()
                val dueRaw = if (args.has("due_datetime") && !args.isNull("due_datetime")) args.optString("due_datetime") else null
                val due = dueRaw?.takeIf { it.isNotBlank() }
                // FIX: валидация due_datetime перед вызовом MCP — при неверном формате просим уточнить
                if (due != null && !isValidDueDatetime(due)) {
                    return "Укажите дату в формате ГГГГ-ММ-ДДЧЧ:мм:сс, например 2026-02-04T05:00:00."
                }
                // FIX: валидация «25 часов» — час 0–23
                if (due != null && !isReasonableTime(due)) {
                    return "❌ Неверное время: в сутках 24 часа (0–23). Укажите, например, «завтра в 18:00» или «через 5 минут»."
                }
                // Ограничение: не более 30 дней вперёд
                if (due != null && !isDueWithinMaxDays(due)) {
                    return "Дата напоминания не может быть более чем через 30 дней. Укажите более близкую дату."
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
        Log.d("Agent", "MCP call: $toolName args=$args")
        val argsJson = JSONObject().apply {
            when (toolName) {
                "get_current_time" -> { /* args не нужны */ }
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
