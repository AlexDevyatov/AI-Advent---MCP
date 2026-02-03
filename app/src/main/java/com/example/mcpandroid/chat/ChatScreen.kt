package com.example.mcpandroid.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.mcpandroid.data.local.Reminder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат с агентом") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Напоминания",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                if (reminders.isNotEmpty()) {
                    item(key = "reminders_header") {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically(),
                            exit = fadeOut() + slideOutVertically()
                        ) {
                            Text(
                                "Напоминания",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(
                        items = reminders,
                        key = { it.id }
                    ) { reminder ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically(),
                            exit = fadeOut() + slideOutVertically()
                        ) {
                            ReminderListItem(reminder = reminder)
                        }
                    }
                    item(key = "chat_header") {
                        Text(
                            "Чат",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                itemsIndexed(messages, key = { index, msg -> "msg_${index}_${msg.role}" }) { _, msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut()
                    ) {
                        ChatBubble(msg = msg)
                    }
                }
                if (loading) {
                    item(key = "loading") {
                        Row(
                            Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                var input by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение…") },
                    enabled = !loading,
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
                IconButton(
                    onClick = {
                        viewModel.sendMessage(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !loading
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Отправить",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun ReminderListItem(reminder: Reminder) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = reminder.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            supportingContent = if (reminder.dueDatetime != null) {
                {
                    Text(
                        text = reminder.dueDatetime!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            } else null,
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
