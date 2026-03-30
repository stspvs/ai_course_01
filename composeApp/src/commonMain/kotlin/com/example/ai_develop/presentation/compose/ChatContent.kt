package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.ChatMemoryStrategy
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.presentation.LLMStateModel

@Composable
internal fun ChatContent(
    state: LLMStateModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onToggleHistory: (Boolean) -> Unit,
    onSelectAgent: (String?) -> Unit,
    onUpdateStrategy: (ChatMemoryStrategy) -> Unit,
    onCreateBranch: (String, String) -> Unit,
    onSwitchBranch: (String?) -> Unit
) {
    val activeAgent = state.agents.find { it.id == state.selectedAgentId }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFE3F2FD))
    ) {
        ChatTopBar(
            activeAgentName = activeAgent?.name ?: "Общий чат",
            isAgentSelected = activeAgent != null,
            isLoading = state.isLoading,
            tokensUsed = state.currentTokensUsed,
            agents = state.agents,
            onSelectAgent = onSelectAgent,
            menuExpanded = menuExpanded,
            onMenuToggle = { menuExpanded = it }
        )

        val messages = state.currentMessages
        val listState = rememberLazyListState()
        
        LaunchedEffect(messages.size, messages.lastOrNull()?.message?.length) {
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageItem(
                    message = message,
                    onCreateBranch = { branchName -> onCreateBranch(message.id, branchName) }
                )
            }
        }

        ChatInputArea(
            input = input,
            isStreamingEnabled = state.isStreamingEnabled,
            sendFullHistory = state.sendFullHistory,
            currentStrategy = activeAgent?.memoryStrategy ?: ChatMemoryStrategy.SlidingWindow(10),
            onInputChange = onInputChange,
            onSendMessage = onSendMessage,
            onClearChat = onClearChat,
            onToggleStreaming = onToggleStreaming,
            onToggleHistory = onToggleHistory,
            onUpdateStrategy = onUpdateStrategy
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    message: ChatMessage,
    onCreateBranch: (String) -> Unit
) {
    var showBranchDialog by remember { mutableStateOf(false) }
    var branchName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier.combinedClickable(
            onLongClick = { showBranchDialog = true },
            onClick = {}
        )
    ) {
        MessageBubble(message = message)
    }

    if (showBranchDialog) {
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("Создать новую ветку?") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Название ветки") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCreateBranch(branchName.ifBlank { "Ветка от ${message.id.take(4)}" })
                    showBranchDialog = false
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showBranchDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    activeAgentName: String,
    isAgentSelected: Boolean,
    isLoading: Boolean,
    tokensUsed: Int,
    agents: List<Agent>,
    onSelectAgent: (String?) -> Unit,
    menuExpanded: Boolean,
    onMenuToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 4.dp
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onMenuToggle(true) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isAgentSelected) Color(0xFF673AB7) else Color(0xFF1976D2)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isAgentSelected) Icons.Default.Person else Icons.Default.Star,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = activeAgentName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isLoading) "Печатает..." else "В сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoading) Color(0xFF673AB7) else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• $tokensUsed токенов",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4A148C)
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { onMenuToggle(false) },
                modifier = Modifier.width(200.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Общий чат") },
                    onClick = {
                        onSelectAgent(null)
                        onMenuToggle(false)
                    },
                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF1976D2)) }
                )
                agents.forEach { agent ->
                    DropdownMenuItem(
                        text = { Text(agent.name) },
                        onClick = {
                            onSelectAgent(agent.id)
                            onMenuToggle(false)
                        },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF673AB7)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputArea(
    input: String,
    isStreamingEnabled: Boolean,
    sendFullHistory: Boolean,
    currentStrategy: ChatMemoryStrategy,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onToggleHistory: (Boolean) -> Unit,
    onUpdateStrategy: (ChatMemoryStrategy) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = Color.White
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = currentStrategy is ChatMemoryStrategy.SlidingWindow,
                    onClick = { onUpdateStrategy(ChatMemoryStrategy.SlidingWindow(10)) },
                    label = { Text("Sliding Window", fontSize = 10.sp) }
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = currentStrategy is ChatMemoryStrategy.StickyFacts,
                    onClick = { onUpdateStrategy(ChatMemoryStrategy.StickyFacts(10)) },
                    label = { Text("Sticky Facts", fontSize = 10.sp) }
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClearChat) { Icon(Icons.Default.Refresh, contentDescription = "Clear") }
            }
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение...") },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = { if (input.isNotBlank()) onSendMessage(input) },
                    containerColor = Color(0xFF1976D2),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
