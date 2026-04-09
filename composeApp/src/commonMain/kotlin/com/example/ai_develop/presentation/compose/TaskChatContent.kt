package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LlmRequestSnapshot
import com.example.ai_develop.domain.SourceType
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskState
import com.example.ai_develop.presentation.DEFAULT_TASK_BUBBLE_PREVIEW_CHARS
import com.example.ai_develop.presentation.taskBubbleCollapsible
import com.example.ai_develop.presentation.taskBubbleDisplayText
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

                        if (task.state.taskState != TaskState.DONE) {
                            OutlinedButton(
                                onClick = { viewModel.cancelAutonomousTask(task.taskId) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Отменить задачу")
                            }
                        }
                    }
                }

                taskContext?.let { task ->
                    // Progress Bar with dots
                    TaskProgressBar(task)
                    Spacer(Modifier.height(8.dp))
                    val outcome = task.runtimeState.outcome
                    if (outcome != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                "Итог задачи: ${outcome.name}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (task.runtimeState.awaitingPlanConfirmation) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { viewModel.confirmPlan(task.taskId) }) {
                                Text("Подтвердить план")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
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
            val trySendTaskMessage = {
                if (isInputEnabled && input.isNotBlank()) {
                    viewModel.sendUserMessage(task.taskId, input)
                    input = ""
                }
            }

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
                        modifier = Modifier
                            .weight(1f)
                            .sendMessageOnEnter(input) { trySendTaskMessage() },
                        enabled = isInputEnabled,
                        placeholder = {
                            Text(
                                if (isPlanning) "Введите сообщение Архитектору..."
                                else "Ввод доступен только на этапе PLANNING"
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { trySendTaskMessage() })
                    )
                    FloatingActionButton(
                        onClick = { trySendTaskMessage() },
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
                        task = task,
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
private fun TaskMessageFullTextLogBlock(message: ChatMessage, task: TaskContext) {
    val stageColor = message.taskState?.let { getStageColor(it, task) } ?: MaterialTheme.colorScheme.secondaryContainer
    if (message.taskState != null && message.source != SourceType.USER) {
        Text(
            text = "ЭТАП: ${message.taskState.name}",
            style = MaterialTheme.typography.labelSmall,
            color = stageColor,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(4.dp))
    }
    Text(
        text = if (message.source == SourceType.USER) "Вы" else "Агент (${message.taskState ?: "System"})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Text(
            text = message.message,
            style = monoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}

@Composable
private fun TaskLlmLogPanel(message: ChatMessage, task: TaskContext, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val snap = message.llmRequestSnapshot
    val clipboard = LocalClipboardManager.current
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
            Text(
                if (snap != null) "Запрос к LLM и полный ответ в чате" else "Сообщение",
                style = MaterialTheme.typography.titleLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(buildFullTaskLlmLogCopyText(snap, message))) }) {
                    Text(if (snap != null) "Копировать всё" else "Копировать текст")
                }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Column(Modifier.fillMaxWidth()) {
                if (snap == null) {
                    Text(
                        "К этому сообщению нет сохранённого снимка запроса к LLM. Ниже — полный текст сообщения из чата.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TaskMessageFullTextLogBlock(message = message, task = task)
                } else {
                    TaskLlmSnapshotDetails(snap)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Полный текст ответа в чате",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    TaskMessageFullTextLogBlock(message = message, task = task)
                }
            }
        }
    }
}

private fun LlmRequestSnapshot.fullLogTextForCopy(): String = buildString {
    appendLine("Провайдер: $providerName")
    appendLine("Модель: $model")
    appendLine("Этап: $agentStage")
    appendLine("Параметры: temp=$temperature, maxTokens=$maxTokens, jsonMode=$isJsonMode")
    if (stopWord.isNotBlank()) appendLine("Стоп-слово: $stopWord")
    appendLine()
    appendLine("Системный промпт")
    appendLine(effectiveSystemPrompt)
    appendLine()
    appendLine("Сообщения в запросе")
    when (agentStage) {
        TaskState.EXECUTION.name ->
            appendLine("(оркестратор: структурированный план, индекс шага, текст CURRENT STEP — всё в одном [user] ниже)")
        TaskState.VERIFICATION.name ->
            appendLine("(оркестратор: структурированный план, шаг, при необходимости прошлый вердикт инспектора, последние ответы исполнителя, EXECUTION RESULT — в [user] ниже; чат планирования в запрос не входит)")
        else -> {}
    }
    appendLine()
    appendLine(inputMessagesText)
}

private fun buildFullTaskLlmLogCopyText(snap: LlmRequestSnapshot?, message: ChatMessage): String =
    if (snap != null) {
        buildString {
            append(snap.fullLogTextForCopy())
            appendLine()
            appendLine()
            appendLine("────────")
            appendLine("Полный текст ответа в чате")
            appendLine(message.message)
        }
    } else {
        message.message
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
    Text(
        snap.effectiveSystemPrompt,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Text("Сообщения в запросе", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    when (snap.agentStage) {
        TaskState.EXECUTION.name ->
            Text(
                "Исполнение: в одном [user] — «CURRENT STEP INDEX», «CURRENT STEP», при наличии «TASK WORKING MEMORY», затем при повторе шага «LAST EXECUTION RESULT» (что проверял инспектор) и «INSPECTOR FEEDBACK». Полный план JSON исполнителю не передаётся.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        TaskState.VERIFICATION.name ->
            Text(
                "Проверка: в [user] — план, текущий шаг, при необходимости прошлый вердикт инспектора, EXECUTION RESULT и критерии успеха. История чата планирования в запрос не передаётся.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        else -> {}
    }
    Spacer(Modifier.height(4.dp))
    Text(
        snap.inputMessagesText,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
    )
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
    val rs = task.runtimeState
    val planSize = when {
        task.plan.isNotEmpty() -> task.plan.size
        else -> rs.planResult?.steps?.size ?: 0
    }
    val planStepCurrent = if (planSize > 0) {
        minOf(rs.currentPlanStepIndex + 1, planSize)
    } else {
        0
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Итерация: ${rs.stepCount} / ${rs.maxSteps}",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (planSize > 0) {
                Text(
                    text = "Шаг плана: $planStepCurrent из $planSize",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
private fun TaskMessageBubbleFrame(
    message: ChatMessage,
    task: TaskContext,
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val full = message.message
    val collapsible = taskBubbleCollapsible(full, DEFAULT_TASK_BUBBLE_PREVIEW_CHARS)
    val displayText = taskBubbleDisplayText(full, expanded, DEFAULT_TASK_BUBBLE_PREVIEW_CHARS)

    val stageColor = message.taskState?.let { getStageColor(it, task) } ?: MaterialTheme.colorScheme.secondaryContainer
    val bgColor = if (message.source == SourceType.USER) MaterialTheme.colorScheme.primaryContainer else stageColor.copy(alpha = 0.1f)
    val hasLog = message.llmRequestSnapshot != null
    val gestureModifier = Modifier.pointerInput(message.id, collapsible) {
        detectTapGestures(
            onDoubleTap = {
                if (collapsible) expanded = !expanded
            },
            onTap = { onOpenLog() }
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = if (message.source != SourceType.USER) BorderStroke(1.dp, stageColor) else null,
        modifier = modifier
            .widthIn(max = 500.dp)
            .then(gestureModifier)
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
            Text(displayText)
            if (collapsible) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Свернуть" else "Показать полностью",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (expanded) "Свернуть" else "Показать полностью",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
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

@Composable
fun TaskMessageBubble(
    message: ChatMessage,
    task: TaskContext,
    onOpenLog: (ChatMessage) -> Unit
) {
    val alignment = if (message.source == SourceType.USER) Alignment.CenterEnd else Alignment.CenterStart
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        TaskMessageBubbleFrame(
            message = message,
            task = task,
            onOpenLog = { onOpenLog(message) }
        )
    }
}

fun getStageColor(state: TaskState, task: TaskContext): Color = when(state) {
    TaskState.PLANNING -> Color(task.architectColor)
    TaskState.EXECUTION -> Color(task.executorColor)
    TaskState.VERIFICATION -> Color(task.validatorColor)
    TaskState.DONE -> Color(0xFF607D8B)
}
