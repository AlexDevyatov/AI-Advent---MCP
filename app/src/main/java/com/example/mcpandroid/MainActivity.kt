package com.example.mcpandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mcpandroid.mcp.ToolsViewModel
import com.example.mcpandroid.ui.ToolsScreen
import com.example.mcpandroid.ui.theme.MCPAndroidTheme

/**
 * Главный экран: при запуске подключается к MCP-серверу и отображает список tools.
 * На реальном устройстве укажите IP ПК в поле «URL сервера» и нажмите «Подключиться».
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ToolsViewModel(application) as T
        }
        setContent {
            MCPAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ToolsScreen(viewModel = viewModel(factory = viewModelFactory))
                }
            }
        }
    }
}
