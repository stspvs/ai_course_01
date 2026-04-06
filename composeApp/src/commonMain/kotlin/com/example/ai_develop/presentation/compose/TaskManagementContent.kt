package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.Agent
import com.example.ai_develop.domain.TaskContext
import com.example.ai_develop.presentation.TaskViewModel

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
                IconButton(onClick = { viewModel.createTask("Новая задача") }) {
                    Icon(Icons.Default.Add, contentDescription = "Создать задачу")
                }
            }
            LazyColumn {
                items(tasks) { task ->
                    val isSelected = task.taskId == selectedTaskId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectTask(task.taskId) }
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(task.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.deleteTask(task) }) {
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
                onUpdate = { viewModel.updateTask(it) }
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

        HorizontalDivider()
        Text("Настройка ролей", style = MaterialTheme.typography.titleMedium)

        RoleSettingRow(
            label = "Архитектор-планировщик (PLANNING)",
            agents = agents,
            selectedAgentId = task.architectAgentId,
            selectedColor = Color(task.architectColor),
            onAgentSelected = { onUpdate(task.copy(architectAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(architectColor = it.value.toLong())) }
        )
        
        RoleSettingRow(
            label = "Исполнитель (EXECUTION)",
            agents = agents,
            selectedAgentId = task.executorAgentId,
            selectedColor = Color(task.executorColor),
            onAgentSelected = { onUpdate(task.copy(executorAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(executorColor = it.value.toLong())) }
        )
        
        RoleSettingRow(
            label = "Оценщик (VALIDATION)",
            agents = agents,
            selectedAgentId = task.validatorAgentId,
            selectedColor = Color(task.validatorColor),
            onAgentSelected = { onUpdate(task.copy(validatorAgentId = it)) },
            onColorSelected = { onUpdate(task.copy(validatorColor = it.value.toLong())) }
        )
    }
}

@Composable
fun RoleSettingRow(
    label: String,
    agents: List<Agent>,
    selectedAgentId: String?,
    selectedColor: Color,
    onAgentSelected: (String) -> Unit,
    onColorSelected: (Color) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                AgentDropdownSelector("", agents, selectedAgentId, onAgentSelected)
            }
            ColorPickerSimple(selectedColor, onColorSelected)
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
