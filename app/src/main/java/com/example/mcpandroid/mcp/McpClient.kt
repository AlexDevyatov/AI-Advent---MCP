package com.example.mcpandroid.mcp

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Минимальный MCP-клиент для Android.
 * Реализует Streamable HTTP transport: POST JSON-RPC к MCP endpoint.
 * Жизненный цикл MCP: initialize → notifications/initialized → tools/list.
 */
class McpClient(
    private val baseUrl: String,
    private val timeoutSeconds: Long = 15,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val toolListAdapter = moshi.adapter(ToolsListResponse::class.java)

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2025-11-25"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Подключение к MCP: initialize → initialized → list tools.
     * Блокирующий вызов — вызывать с Dispatchers.IO.
     * Возвращает список инструментов или ошибку.
     */
    fun fetchTools(): Result<List<McpTool>> = runCatching {
        val url = baseUrl.trimEnd('/').let { if (it.endsWith("/mcp")) it else "$it/mcp" }
        try {
            fetchToolsInternal(url)
        } catch (e: ConnectException) {
            throw McpException(
                "Не удалось подключиться к $baseUrl. Убедитесь, что MCP-сервер запущен (python3 mcp_server/server.py). " +
                    "Эмулятор: 10.0.2.2. Реальное устройство: укажите IP ПК в McpConfig.",
                e
            )
        } catch (e: SocketTimeoutException) {
            throw McpException("Таймаут подключения к $baseUrl. Проверьте сеть и firewall.", e)
        } catch (e: IOException) {
            throw McpException("Сеть: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun fetchToolsInternal(url: String): List<McpTool> {
        val headers = mapOf(
            "Accept" to "application/json, text/event-stream",
            "Content-Type" to "application/json; charset=utf-8",
            "MCP-Protocol-Version" to MCP_PROTOCOL_VERSION,
        )

        // 1. Initialize — обязательный первый шаг по спецификации MCP
        val initBody = buildJsonRpcRequest(id = 1, method = "initialize", params = mapOf(
            "protocolVersion" to MCP_PROTOCOL_VERSION,
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf(
                "name" to "MCPAndroid",
                "version" to "1.0.0",
            ),
        ))
        val initResponse = post(url, headers, initJson(initBody))
        if (!initResponse.isSuccessful) {
            throw McpException("Initialize failed: ${initResponse.code} ${initResponse.message}")
        }

        // 2. Notifications/initialized — уведомление о готовности клиента
        val notifiedBody = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        post(url, headers, notifiedBody)

        // 3. tools/list — запрос списка инструментов
        val listBody = buildJsonRpcRequest(id = 2, method = "tools/list", params = emptyMap())
        val listResponse = post(url, headers, initJson(listBody))
        if (!listResponse.isSuccessful) {
            throw McpException("tools/list failed: ${listResponse.code}")
        }

        val body = listResponse.body?.string() ?: throw McpException("Empty response")
        val parsed = toolListAdapter.fromJson(body)
            ?: throw McpException("Parse error: $body")
        return parsed.result.tools
    }

    private fun post(url: String, headers: Map<String, String>, body: String): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute()
    }

    private fun buildJsonRpcRequest(id: Int, method: String, params: Map<String, Any>): String {
        val paramsJson = if (params.isEmpty()) "{}" else JSONObject(params).toString()
        return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$paramsJson}"""
    }

    private fun initJson(request: String): String = request
}

/** Ответ на tools/list: result.tools */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class ToolsListResponse(
    val result: ToolsListResult,
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class ToolsListResult(
    val tools: List<McpTool>,
)

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)
