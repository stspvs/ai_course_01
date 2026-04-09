package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LlmRequestSnapshot
import com.example.ai_develop.domain.SourceType
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskState
import com.example.ai_develop.presentation.TaskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskChatContent(viewModel: TaskViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    val selectedTaskId by viewModel.selectedTaskId.collectAsState()
    val taskContext by viewModel.activeSagaContext.collectAsState()
    val messages by viewModel.taskMessages.collectAsState()
    val streamingDraft by viewModel.streamingDraft.collectAsState()
    val taskUiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<TaskState?>(null) }
    var logTargetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val logSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with Task Selection and Controls
        Surface(
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TaskSelector(
                        tasks = tasks,
                        selectedTaskId = selectedTaskId,
                        onSelect = { viewModel.selectTask(it) }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    taskContext?.let { task ->
                        if (!task.isReadyToRun) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Не назначены агенты",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Button(
                            onClick = { viewModel.togglePause(task.taskId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (task.isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(
                                if (task.isPaused) Icons.Default.PlayArrow else Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (task.isPaused) "Старт" else "Пауза")
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetTask(task.taskId) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Сброс")
                        }
                    }
                }

                taskContext?.let { task ->
                    // Progress Bar with dots
                    TaskProgressBar(task)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        taskContext?.let { task ->
            // Filters
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text("Все сообщения") }
                )
                TaskState.entries.forEach { state ->
                    FilterChip(
                        selected = filter == state,
                        onClick = { filter = state },
                        label = { Text(state.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = getStageColor(state, task).copy(alpha = 0.2f),
                            selectedLabelColor = getStageColor(state, task)
                        )
                    )
                }
            }

            // Chat
            val filteredMessages = if (filter == null) messages else messages.filter { it.taskState == filter }
            val showStreamingBubble = streamingDraft.isNotBlank() &&
                (filter == null || filter == task.state.taskState)
            val displayMessages = buildList {
                addAll(filteredMessages)
                if (showStreamingBubble) {
                    add(
                        ChatMessage(
                            id = "__streaming__${task.taskId}",
                            message = streamingDraft,
                            role = "assistant",
                            source = SourceType.AI,
                            taskId = task.taskId,
                            taskState = task.state.taskState
                        )
                    )
                }
            }
            val listState = rememberLazyListState()

            LaunchedEffect(
                displayMessages.size,
                filteredMessages.lastOrNull()?.message?.length,
                streamingDraft.length,
                showStreamingBubble,
                taskUiState.isSending
            ) {
                if (displayMessages.isNotEmpty()) {
                    listState.scrollToItem(displayMessages.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayMessages, key = { it.id }) { msg ->
                    TaskMessageBubble(
                        message = msg,
                        task = task,
                        onOpenLog = { logTargetMessage = it }
                    )
                }
            }

            // Input
            val isPlanning = task.state.taskState == TaskState.PLANNING
            val isInputEnabled = isPlanning

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        enabled = isInputEnabled,
                        placeholder = {
                            Text(
                                if (isPlanning) "Введите сообщение Архитектору..."
                                else "Ввод доступен только на этапе PLANNING"
                            )
                        },
                        shape = RoundedCornerShape(12.dp)
                    )
                    FloatingActionButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                viewModel.sendUserMessage(task.taskId, input)
                                input = ""
                            }
                        },
                        containerColor = if (isInputEnabled && input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }

            if (logTargetMessage != null) {
                ModalBottomSheet(
                    onDismissRequest = { logTargetMessage = null },
                    sheetState = logSheetState
                ) {
                    TaskLlmLogPanel(
                        message = logTargetMessage!!,
                        onClose = { logTargetMessage = null }
                    )
                }
            }
        } ?: run {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выберите задачу для отображения чата", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun TaskLlmLogPanel(message: ChatMessage, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val snap = message.llmRequestSnapshot
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(scroll)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Запрос к LLM", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        Spacer(Modifier.height(8.dp))
        if (snap == null) {
            Text(
                "К этому сообщению нет сохранённых логов запроса к LLM.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            TaskLlmSnapshotDetails(snap)
        }
    }
}

@Composable
private fun TaskLlmSnapshotDetails(snap: LlmRequestSnapshot) {
    Text("Провайдер: ${snap.providerName}", style = MaterialTheme.typography.titleSmall)
    Text("Модель: ${snap.model}", style = MaterialTheme.typography.bodyMedium)
    Text("Этап: ${snap.agentStage}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Параметры: temp=${snap.temperature}, maxTokens=${snap.maxTokens}, jsonMode=${snap.isJsonMode}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (snap.stopWord.isNotBlank()) {
        Text("Стоп-слово: ${snap.stopWord}", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    Text("Системный промпт", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(snap.effectiveSystemPrompt, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    Text("Сообщения в запросе", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(snap.inputMessagesText, style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSelector(tasks: List<TaskContext>, selectedTaskId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTask = tasks.find { it.taskId == selectedTaskId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedTask?.title ?: "Выберите задачу",
            onValueChange = {},
            readOnly = true,
            label = { Text("Текущая задача") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.width(350.dp).menuAnchor(MenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            tasks.forEach { task ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(task.title, fontWeight = FontWeight.Bold)
                            Text("Этап: ${task.state.taskState}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    onClick = {
                        onSelect(task.taskId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun TaskProgressBar(task: TaskContext) {
    val states = TaskState.entries
    val currentState = task.state.taskState
    val currentIndex = states.indexOf(currentState)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            states.forEachIndexed { index, state ->
                val isCompleted = index < currentIndex || (currentState == TaskState.DONE && index <= currentIndex)
                val isCurrent = index == currentIndex && currentState != TaskState.DONE
                val color = if (isCompleted || isCurrent) getStageColor(state, task) else MaterialTheme.colorScheme.outlineVariant

                // Dot
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) color else Color.Transparent)
                        .border(2.dp, color, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompleted) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isCurrent) Color.White else color
                        )
                    } else {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) Color.White else color,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Connecting Line
                if (index < states.size - 1) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .padding(horizontal = 4.dp)
                            .background(
                                if (index < currentIndex) getStageColor(state, task)
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            states.forEach { state ->
                Text(
                    text = state.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state == currentState) getStageColor(state, task) else Color.Gray,
                    fontWeight = if (state == currentState) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(72.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun TaskMessageBubble(
    message: ChatMessage,
    task: TaskContext,
    onOpenLog: (ChatMessage) -> Unit
) {
    val alignment = if (message.source == SourceType.USER) Alignment.CenterEnd else Alignment.CenterStart
    val stageColor = message.taskState?.let { getStageColor(it, task) } ?: MaterialTheme.colorScheme.secondaryContainer
    val bgColor = if (message.source == SourceType.USER) MaterialTheme.colorScheme.primaryContainer else stageColor.copy(alpha = 0.1f)
    val hasLog = message.llmRequestSnapshot != null

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            border = if (message.source != SourceType.USER) BorderStroke(1.dp, stageColor) else null,
            modifier = Modifier
                .widthIn(max = 500.dp)
                .clickable { onOpenLog(message) }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.taskState != null && message.source != SourceType.USER) {
                    Text(
                        text = "ЭТАП: ${message.taskState.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = stageColor,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(message.message)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasLog) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Spacer(Modifier.size(14.dp))
                    }
                    Text(
                        text = if (message.source == SourceType.USER) "Вы" else "Агент (${message.taskState ?: "System"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

fun getStageColor(state: TaskState, task: TaskContext): Color = when(state) {
    TaskState.PLANNING -> Color(task.architectColor)
    TaskState.EXECUTION -> Color(task.executorColor)
    TaskState.VALIDATION -> Color(task.validatorColor)
    TaskState.DONE -> Color(0xFF607D8B)
}
