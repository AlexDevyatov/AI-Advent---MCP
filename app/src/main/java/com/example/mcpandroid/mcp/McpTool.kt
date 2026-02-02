package com.example.mcpandroid.mcp

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Модель инструмента MCP (tool).
 * Соответствует спецификации MCP: name, title, description, inputSchema.
 */
@JsonClass(generateAdapter = true)
data class McpTool(
    val name: String,
    val title: String? = null,
    val description: String,
    @Json(name = "inputSchema") val inputSchema: Map<String, Any>? = null,
)
