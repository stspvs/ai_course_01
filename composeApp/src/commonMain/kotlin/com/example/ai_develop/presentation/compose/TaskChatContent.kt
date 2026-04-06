package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.SourceType
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.domain.TaskState
import com.example.ai_develop.presentation.TaskViewModel

@Composable
fun TaskChatContent(viewModel: TaskViewModel) {
    val selectedTaskId by viewModel.selectedTaskId.collectAsState()
    val taskContext by viewModel.activeSagaContext.collectAsState()
    val messages by viewModel.taskMessages.collectAsState()
    var input by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<TaskState?>(null) }

    if (selectedTaskId == null || taskContext == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Выберите задачу в управлении")
        }
        return
    }

    val task = taskContext!!

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Этап: ${task.state.taskState}", color = getStageColor(task.state.taskState, task))
            }
            
            if (!task.isReadyToRun) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Не назначены агенты", modifier = Modifier.padding(4.dp))
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

        // Filters
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val filteredMessages = if (filter == null) messages else messages.filter { it.taskState == filter }
            items(filteredMessages) { msg ->
                TaskMessageBubble(msg, task)
            }
        }

        // Input
        // Разблокируем ввод на этапе PLANNING всегда, чтобы можно было дать инструкции до старта
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
    }
}

@Composable
fun TaskMessageBubble(message: ChatMessage, task: TaskContext) {
    val alignment = if (message.source == SourceType.USER) Alignment.CenterEnd else Alignment.CenterStart
    val stageColor = message.taskState?.let { getStageColor(it, task) } ?: MaterialTheme.colorScheme.secondaryContainer
    val bgColor = if (message.source == SourceType.USER) MaterialTheme.colorScheme.primaryContainer else stageColor.copy(alpha = 0.1f)
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bgColor,
            border = if (message.source != SourceType.USER) BorderStroke(1.dp, stageColor) else null,
            modifier = Modifier.widthIn(max = 500.dp)
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
                Text(
                    text = if (message.source == SourceType.USER) "Вы" else "Агент (${message.taskState ?: "System"})",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
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
