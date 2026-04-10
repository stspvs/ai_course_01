package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.TaskViewModel
import com.example.ai_develop.presentation.TaskEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
fun TaskManagementContent(viewModel: TaskViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    val selectedTaskId by viewModel.selectedTaskId.collectAsState()
    val agents by viewModel.agents.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: Task List
        Column(modifier = Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Задачи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.onEvent(TaskEvent.CreateTask("Новая задача")) }) {
                    Icon(Icons.Default.Add, contentDescription = "Создать задачу")
                }
            }
            LazyColumn {
                items(tasks, key = { it.taskId }) { task ->
                    val isSelected = task.taskId == selectedTaskId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onEvent(TaskEvent.SelectTask(task.taskId)) }
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(task.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.onEvent(TaskEvent.DeleteTask(task)) }) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // Right: Settings
        val selectedTask = tasks.find { it.taskId == selectedTaskId }
        if (selectedTask != null) {
            TaskSettings(
                task = selectedTask,
                agents = agents,
                onUpdate = { viewModel.onEvent(TaskEvent.UpdateTask(it)) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Выберите задачу")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSettings(
    task: TaskContext,
    agents: List<Agent>,
    onUpdate: (TaskContext) -> Unit
) {
    var title by remember(task.taskId) { mutableStateOf(task.title) }
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), 
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Общие настройки", style = MaterialTheme.typography.titleMedium)
        
        OutlinedTextField(
            value = title,
            onValueChange = { title = it; onUpdate(task.copy(title = it)) },
            label = { Text("Название задачи") },
            modifier = Modifier.fillMaxWidth()
        )

        TaskStateMachineLimitsCard(task, onUpdate)

        TaskInvariantsCard(task, onUpdate)

        HorizontalDivider()
        Text("Настройка ролей", style = MaterialTheme.typography.titleMedium)

        RoleSettingRow(
            label = "Архитектор-планировщик (PLANNING)",
            role = ArchitectRole(),
            task = task,
            agents = agents,
            selectedAgentId = task.architectAgentId,
            selectedColor = Color(task.architectColor),
            onAgentSelected = { onUpdate(task.copy(architectAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(architectColor = it.value.toLong())) }
        )
        
        RoleSettingRow(
            label = "Исполнитель (EXECUTION)",
            role = ExecutorRole(),
            task = task,
            agents = agents,
            selectedAgentId = task.executorAgentId,
            selectedColor = Color(task.executorColor),
            onAgentSelected = { onUpdate(task.copy(executorAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(executorColor = it.value.toLong())) }
        )
        
        RoleSettingRow(
            label = "Оценщик (VERIFICATION)",
            role = ValidatorRole(),
            task = task,
            agents = agents,
            selectedAgentId = task.validatorAgentId,
            selectedColor = Color(task.validatorColor),
            onAgentSelected = { onUpdate(task.copy(validatorAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(validatorColor = it.value.toLong())) }
        )
    }
}

private const val MAX_TASK_INVARIANTS = 20

/**
 * Локальный [TextFieldValue] удерживает selection/caret; при [value] как [String] из родителя
 * после первого ввода курсор часто сбрасывается в начало (рекомпозиция после [onUpdate]).
 */
@Composable
private fun InvariantConditionTextField(
    invariantId: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: @Composable () -> Unit
) {
    var fieldValue by remember(invariantId) { mutableStateOf(TextFieldValue(text)) }

    LaunchedEffect(text) {
        if (fieldValue.text != text) {
            fieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { newValue ->
            val clipped = newValue.text.take(MAX_TASK_INVARIANT_TEXT_LENGTH)
            fieldValue = if (clipped.length == newValue.text.length) {
                newValue.copy(text = clipped)
            } else {
                TextFieldValue(clipped, TextRange(clipped.length))
            }
            onTextChange(clipped)
        },
        modifier = modifier,
        minLines = 1,
        maxLines = 3,
        label = { Text("Условие") },
        supportingText = supportingText
    )
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun TaskInvariantsCard(
    task: TaskContext,
    onUpdate: (TaskContext) -> Unit
) {
    val rs = task.runtimeState
    val invariants = rs.invariants
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Инварианты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "До $MAX_TASK_INVARIANT_TEXT_LENGTH символов на условие. " +
                    "Позитивный — описанное должно выполняться; негативный — описанного не должно быть (запрет). " +
                    "Список добавляется отдельным блоком в системный промпт планировщика, исполнителя и инспектора. " +
                    "Инспектор дополнительно проверяет каждый инвариант отдельно; провал — в рекомендациях исполнителю.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            invariants.forEach { inv ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        InvariantConditionTextField(
                            invariantId = inv.id,
                            text = inv.text,
                            onTextChange = { clipped ->
                                onUpdate(
                                    task.copy(
                                        runtimeState = rs.copy(
                                            invariants = invariants.map {
                                                if (it.id == inv.id) it.copy(text = clipped) else it
                                            }
                                        )
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                            supportingText = {
                                Text("${inv.text.length}/$MAX_TASK_INVARIANT_TEXT_LENGTH")
                            }
                        )
                        IconButton(
                            onClick = {
                                onUpdate(
                                    task.copy(
                                        runtimeState = rs.copy(invariants = invariants.filter { it.id != inv.id })
                                    )
                                )
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить инвариант", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = inv.polarity == InvariantPolarity.POSITIVE,
                            onClick = {
                                if (inv.polarity != InvariantPolarity.POSITIVE) {
                                    onUpdate(
                                        task.copy(
                                            runtimeState = rs.copy(
                                                invariants = invariants.map {
                                                    if (it.id == inv.id) it.copy(polarity = InvariantPolarity.POSITIVE) else it
                                                }
                                            )
                                        )
                                    )
                                }
                            },
                            label = { Text("Позитивный") }
                        )
                        FilterChip(
                            selected = inv.polarity == InvariantPolarity.NEGATIVE,
                            onClick = {
                                if (inv.polarity != InvariantPolarity.NEGATIVE) {
                                    onUpdate(
                                        task.copy(
                                            runtimeState = rs.copy(
                                                invariants = invariants.map {
                                                    if (it.id == inv.id) it.copy(polarity = InvariantPolarity.NEGATIVE) else it
                                                }
                                            )
                                        )
                                    )
                                }
                            },
                            label = { Text("Негативный") }
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    if (invariants.size >= MAX_TASK_INVARIANTS) return@OutlinedButton
                    onUpdate(
                        task.copy(
                            runtimeState = rs.copy(
                                invariants = invariants + TaskInvariant(id = Uuid.random().toString(), text = "")
                            )
                        )
                    )
                },
                enabled = invariants.size < MAX_TASK_INVARIANTS
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Добавить инвариант")
            }
        }
    }
}

@Composable
private fun TaskStateMachineLimitsCard(
    task: TaskContext,
    onUpdate: (TaskContext) -> Unit
) {
    val rs = task.runtimeState
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Стейт-машина", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            RuntimeIntField(
                taskId = task.taskId,
                label = "Макс. шагов итераций (maxSteps)",
                value = rs.maxSteps,
                onCommit = { v -> onUpdate(task.copy(runtimeState = rs.copy(maxSteps = v))) }
            )
            Text(
                "Текущий счётчик итераций (stepCount): ${rs.stepCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "Лимиты вызовов LLM по этапам",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RuntimeIntField(
                taskId = task.taskId,
                label = "PLANNING (maxPlanningSteps)",
                value = rs.maxPlanningSteps,
                onCommit = { v -> onUpdate(task.copy(runtimeState = rs.copy(maxPlanningSteps = v))) }
            )
            RuntimeIntField(
                taskId = task.taskId,
                label = "EXECUTION (maxExecutionSteps)",
                value = rs.maxExecutionSteps,
                onCommit = { v -> onUpdate(task.copy(runtimeState = rs.copy(maxExecutionSteps = v))) }
            )
            RuntimeIntField(
                taskId = task.taskId,
                label = "VERIFICATION (maxVerificationSteps)",
                value = rs.maxVerificationSteps,
                onCommit = { v -> onUpdate(task.copy(runtimeState = rs.copy(maxVerificationSteps = v))) }
            )
        }
    }
}

@Composable
private fun RuntimeIntField(
    taskId: String,
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 1..10_000
) {
    var isFocused by remember { mutableStateOf(false) }
    var text by remember(taskId) { mutableStateOf(value.toString()) }
    LaunchedEffect(taskId) {
        text = value.toString()
    }
    LaunchedEffect(value) {
        if (!isFocused) text = value.toString()
    }
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(taskId) {
        onDispose { debounceJob?.cancel() }
    }

    fun commitIfParsedDiffers() {
        val v = text.toIntOrNull()?.coerceIn(range) ?: return
        if (v != value) onCommit(v)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() }.take(6)
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(450)
                commitIfParsedDiffers()
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) {
                    debounceJob?.cancel()
                    val v = text.toIntOrNull()?.coerceIn(range) ?: value
                    text = v.toString()
                    if (v != value) onCommit(v)
                }
            }
    )
}

@Composable
fun RoleSettingRow(
    label: String,
    role: TaskRole,
    task: TaskContext,
    agents: List<Agent>,
    selectedAgentId: String?,
    selectedColor: Color,
    onAgentSelected: (String) -> Unit,
    onColorSelected: (Color) -> Unit
) {
    val selectedAgent = agents.find { it.id == selectedAgentId }
    var showDetails by remember(selectedAgentId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                AgentDropdownSelector("", agents, selectedAgentId, onAgentSelected)
            }
            ColorPickerSimple(selectedColor, onColorSelected)
            
            if (selectedAgent != null) {
                IconButton(onClick = { showDetails = !showDetails }) {
                    Icon(
                        if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Подробности"
                    )
                }
            }
        }

        if (showDetails && selectedAgent != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Системный промпт агента:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(selectedAgent.systemPrompt, style = MaterialTheme.typography.bodySmall)
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text("Инструкция роли (${role.taskState}):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(role.getSystemInstruction(task), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}

@Composable
fun ColorPickerSimple(selectedColor: Color, onColorSelected: (Color) -> Unit) {
    val colors = listOf(
        Color(0xFF2196F3), // Blue
        Color(0xFF4CAF50), // Green
        Color(0xFF9C27B0), // Purple
        Color(0xFFF44336), // Red
        Color(0xFFFF9800), // Orange
        Color(0xFF795548), // Brown
        Color(0xFF607D8B)  // Grey
    )
    
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(selectedColor)
                .clickable { expanded = true }
                .background(Color.Black.copy(alpha = 0.1f)) // border effect
        )
        
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { 
                                onColorSelected(color)
                                expanded = false 
                            }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDropdownSelector(label: String, agents: List<Agent>, selectedId: String?, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedAgent = agents.find { it.id == selectedId }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedAgent?.name ?: "Не выбран",
            onValueChange = {},
            readOnly = true,
            label = if (label.isNotEmpty()) { { Text(label) } } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            agents.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.name) },
                    onClick = {
                        onSelected(agent.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
