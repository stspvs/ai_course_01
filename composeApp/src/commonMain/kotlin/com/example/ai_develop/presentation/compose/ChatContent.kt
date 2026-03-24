package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    onSelectAgent: (String?) -> Unit
) {
    val activeAgent = state.agents.find { it.id == state.selectedAgentId }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFE3F2FD))
    ) {
        // Top Bar with Agent Selector
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

        // Messages List
        val messages = state.currentMessages
        val listState = rememberLazyListState()
        
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message = message)
            }
        }

        // Bottom Input Area
        ChatInputArea(
            input = input,
            isStreamingEnabled = state.isStreamingEnabled,
            sendFullHistory = state.sendFullHistory,
            onInputChange = onInputChange,
            onSendMessage = onSendMessage,
            onClearChat = onClearChat,
            onToggleStreaming = onToggleStreaming,
            onToggleHistory = onToggleHistory
        )
    }
}

@Composable
private fun ChatTopBar(
    activeAgentName: String,
    isAgentSelected: Boolean,
    isLoading: Boolean,
    tokensUsed: Int,
    agents: List<com.example.ai_develop.presentation.Agent>,
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
                if (isAgentSelected) {
                    IconButton(onClick = { onSelectAgent(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color.Gray)
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
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onToggleHistory: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = Color.White
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                FilterChip(
                    selected = isStreamingEnabled,
                    onClick = { onToggleStreaming(!isStreamingEnabled) },
                    label = { Text("Стриминг", fontSize = 10.sp) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = sendFullHistory,
                    onClick = { onToggleHistory(!sendFullHistory) },
                    label = { Text("История", fontSize = 10.sp) }
                )
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
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        IconButton(onClick = onClearChat) { Icon(Icons.Default.Refresh, contentDescription = "Clear") }
                    }
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
