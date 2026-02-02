package com.example.mcpandroid.mcp

import android.content.Context
import android.content.SharedPreferences

/**
 * Хранение URL MCP-сервера для реального устройства.
 * Эмулятор: http://10.0.2.2:8765. Реальное устройство: http://&lt;IP ПК&gt;:8765.
 */
class McpServerPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, McpConfig.defaultBaseUrl) ?: McpConfig.defaultBaseUrl

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "mcp_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }
}
