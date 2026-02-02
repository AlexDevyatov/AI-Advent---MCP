package com.example.mcpandroid.mcp

/**
 * Конфигурация MCP-клиента.
 * Для эмулятора Android: 10.0.2.2 — это хост-машина.
 * Для реального устройства: укажите IP компьютера с запущенным MCP-сервером в той же сети.
 */
object McpConfig {
    /** Базовый URL MCP-сервера. Endpoint будет .../mcp */
    const val defaultBaseUrl: String = "http://10.0.2.2:8765"
}
