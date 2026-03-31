package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import com.example.ai_develop.domain.ChatBranch
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
    var showFacts by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }

    val isBranchingMode = activeAgent?.memoryStrategy is ChatMemoryStrategy.Branching

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
            onMenuToggle = { menuExpanded = it },
            onShowFacts = { showFacts = !showFacts },
            hasFacts = (activeAgent?.memoryStrategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts?.isNotEmpty() == true,
            onShowBranches = { showBranches = !showBranches },
            isBranchingMode = isBranchingMode,
            hasBranches = activeAgent?.branches?.isNotEmpty() == true
        )

        AnimatedVisibility(
            visible = showFacts && !isBranchingMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val facts = (activeAgent?.memoryStrategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts ?: emptyMap()
            FactsPanel(facts = facts)
        }

        AnimatedVisibility(
            visible = showBranches && isBranchingMode,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            BranchesPanel(
                branches = activeAgent?.branches ?: emptyList(),
                currentBranchId = activeAgent?.currentBranchId,
                onSwitchBranch = onSwitchBranch,
                onCreateBranch = { branchName ->
                    // Создаем ветку от последнего сообщения, если оно есть
                    val lastMsgId = activeAgent?.messages?.lastOrNull()?.id
                    if (lastMsgId != null) {
                        onCreateBranch(lastMsgId, branchName)
                    } else {
                        // Если сообщений нет, ветка по сути от начала (но UI обычно не дает создать без сообщений)
                    }
                }
            )
        }

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
            onInputChange = onInputChange,
            onSendMessage = onSendMessage,
            onClearChat = onClearChat
        )
    }
}

@Composable
private fun FactsPanel(facts: Map<String, String>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xFFFFF9C4),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFF57F17), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sticky Facts (Извлечено из контекста):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color(0xFFF57F17))
            }
            Spacer(Modifier.height(8.dp))
            if (facts.isEmpty()) {
                Text("Факты еще не извлечены. Пообщайтесь с агентом!", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                facts.forEach { (key, value) ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("• ", fontWeight = FontWeight.Bold)
                        Text(key, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text(": ", style = MaterialTheme.typography.bodySmall)
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchesPanel(
    branches: List<ChatBranch>,
    currentBranchId: String?,
    onSwitchBranch: (String?) -> Unit,
    onCreateBranch: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = Color(0xFFE8F5E9),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                val currentBranchName = branches.find { it.id == currentBranchId }?.name ?: "Основная ветка"
                Text(
                    text = "Текущая ветка: $currentBranchName",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Branch", tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentBranchId == null || currentBranchId == "main_branch",
                    onClick = { onSwitchBranch(null) },
                    label = { Text("Основная") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White
                    )
                )
                
                branches.filter { it.id != "main_branch" }.forEach { branch ->
                    FilterChip(
                        selected = branch.id == currentBranchId,
                        onClick = { onSwitchBranch(branch.id) },
                        label = { Text(branch.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2E7D32),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Создать новую ветку") },
            text = {
                OutlinedTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = { Text("Название ветки") },
                    placeholder = { Text("Введите название...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newBranchName.isNotBlank()) {
                        onCreateBranch(newBranchName)
                        newBranchName = ""
                        showCreateDialog = false
                    }
                }) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Отмена") }
            }
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
    onMenuToggle: (Boolean) -> Unit,
    onShowFacts: () -> Unit,
    hasFacts: Boolean,
    onShowBranches: () -> Unit,
    isBranchingMode: Boolean,
    hasBranches: Boolean
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
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onMenuToggle(true) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isAgentSelected) Color(0xFF673AB7) else Color(0xFF1976D2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isAgentSelected) Icons.Default.Person else Icons.Default.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeAgentName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                        }
                        Text(
                            text = if (isLoading) "Печатает..." else "В сети",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoading) Color(0xFF673AB7) else Color.Gray
                        )
                    }
                }
                
                if (isAgentSelected) {
                    if (isBranchingMode) {
                        IconButton(onClick = onShowBranches) {
                            BadgedBox(
                                badge = { 
                                    if (hasBranches) {
                                        Badge(containerColor = Color(0xFF2E7D32)) {
                                            Text("!", color = Color.White)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Branches", tint = if (hasBranches) Color(0xFF2E7D32) else Color.Gray)
                            }
                        }
                    } else {
                        IconButton(onClick = onShowFacts) {
                            BadgedBox(
                                badge = { 
                                    if (hasFacts) {
                                        Badge(containerColor = Color(0xFFF57F17)) {
                                            Text("!", color = Color.White)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Info, contentDescription = "Facts", tint = if (hasFacts) Color(0xFFF57F17) else Color.Gray)
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
                    Text(
                        text = "$tokensUsed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4A148C)
                    )
                    Text(
                        text = "токенов",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
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
                agents.filter { it.id != "general_chat_id" }.forEach { agent ->
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
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        color = Color.White
    ) {
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
                    IconButton(onClick = onClearChat) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Clear Chat",
                            tint = Color.Gray
                        )
                    }
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
