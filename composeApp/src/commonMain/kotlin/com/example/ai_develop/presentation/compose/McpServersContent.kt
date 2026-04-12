package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_develop.data.McpDiscoveredTool
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.presentation.McpServersViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun McpServersContent(
    viewModel: McpServersViewModel = koinViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()
    var showAddServer by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerRecord?>(null) }
    var addBindingForServer by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("MCP / API серверы", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { showAddServer = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Сервер")
            }
        }

        ui.message?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.servers.forEach { server ->
                key(server.id) {
                    ServerCard(
                        server = server,
                        selected = ui.selectedServerId == server.id,
                        onSelect = { viewModel.selectServer(server.id) },
                        onEdit = { editingServer = server },
                        onDelete = { viewModel.deleteServer(server.id) },
                        onSync = { viewModel.syncToolsFromServer(server.id) },
                    )
                }
            }
        }

        ui.selectedServerId?.let { sid ->
            val server = ui.servers.find { it.id == sid }
            if (server != null) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Инструменты сервера «${server.displayName}»", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Статус синхронизации: " + if (server.lastSyncError != null) {
                        "ошибка — ${server.lastSyncError}"
                    } else {
                        "ок (${ui.discoveredTools.size} в кэше)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    OutlinedTextField(
                        value = ui.testInput,
                        onValueChange = viewModel::setTestInput,
                        label = { Text("Текст для теста вызова") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { addBindingForServer = sid }) {
                        Text("Добавить привязку")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.bindings.forEach { binding ->
                        key(binding.id) {
                            BindingRow(
                                binding = binding,
                                onToggle = { en ->
                                    viewModel.saveBinding(binding.copy(enabled = en))
                                },
                                onDelete = { viewModel.deleteBinding(binding.id, binding.serverId) },
                                onTest = { viewModel.testBinding(binding) },
                            )
                        }
                    }
                }
                ui.testResult?.let { tr ->
                    Spacer(Modifier.height(8.dp))
                    Text("Результат теста:", style = MaterialTheme.typography.labelMedium)
                    Text(tr, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showAddServer) {
        ServerEditDialog(
            title = "Новый MCP сервер",
            initial = null,
            onDismiss = { showAddServer = false },
            onSave = { rec ->
                viewModel.saveServer(rec)
                showAddServer = false
            },
        )
    }

    editingServer?.let { s ->
        ServerEditDialog(
            title = "Редактировать сервер",
            initial = s,
            onDismiss = { editingServer = null },
            onSave = { rec ->
                viewModel.saveServer(rec)
                editingServer = null
            },
        )
    }

    addBindingForServer?.let { serverId ->
        val server = ui.servers.find { it.id == serverId }
        if (server != null) {
            AddBindingDialog(
                serverId = serverId,
                discovered = ui.discoveredTools,
                onDismiss = { addBindingForServer = null },
                onSave = { binding ->
                    viewModel.saveBinding(binding)
                    addBindingForServer = null
                },
            )
        }
    }
}

@Composable
private fun ServerCard(
    server: McpServerRecord,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFE8EAF6) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(server.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(server.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (server.enabled) "Включён" else "Выключен",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row {
                    IconButton(onClick = onSync) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить список tools")
                    }
                    TextButton(onClick = onEdit) { Text("Изм.") }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
            }
        }
    }
}

@Composable
private fun BindingRow(
    binding: McpToolBindingRecord,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(binding.agentToolName, style = MaterialTheme.typography.titleSmall)
                Text("MCP: ${binding.mcpToolName}", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = binding.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onTest) { Text("Тест") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить привязку")
            }
        }
    }
}

@Composable
private fun ServerEditDialog(
    title: String,
    initial: McpServerRecord?,
    onDismiss: () -> Unit,
    onSave: (McpServerRecord) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.displayName ?: "") }
    var url by remember(initial?.id) { mutableStateOf(initial?.baseUrl ?: "http://127.0.0.1:8765/mcp") }
    var headers by remember(initial?.id) { mutableStateOf(initial?.headersJson ?: "{}") }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Base URL (…/mcp)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(headers, { headers = it }, label = { Text("Headers JSON") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включён")
                    Spacer(Modifier.width(8.dp))
                    Switch(enabled, { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = initial?.id ?: McpServersViewModel.newId()
                    onSave(
                        McpServerRecord(
                            id = id,
                            displayName = name.ifBlank { "MCP" },
                            baseUrl = url.trim(),
                            enabled = enabled,
                            headersJson = headers.ifBlank { "{}" },
                            lastSyncToolsJson = initial?.lastSyncToolsJson ?: "",
                            lastSyncError = initial?.lastSyncError,
                            lastSyncAt = initial?.lastSyncAt ?: 0L,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun AddBindingDialog(
    serverId: String,
    discovered: List<McpDiscoveredTool>,
    onDismiss: () -> Unit,
    onSave: (McpToolBindingRecord) -> Unit,
) {
    var mcpName by remember { mutableStateOf(discovered.firstOrNull()?.name ?: "") }
    var agentName by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var argKey by remember { mutableStateOf("query") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая привязка инструмента") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hint = discovered.joinToString { it.name }.takeIf { it.isNotEmpty() }
                OutlinedTextField(
                    mcpName,
                    { mcpName = it },
                    label = { Text("Имя MCP tool") },
                    supportingText = {
                        hint?.let { Text("С сервера: $it") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(agentName, { agentName = it }, label = { Text("Имя для агента (уникально)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(desc, { desc = it }, label = { Text("Описание (опц.)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(argKey, { argKey = it }, label = { Text("Ключ аргумента (например query)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        McpToolBindingRecord(
                            id = McpServersViewModel.newId(),
                            serverId = serverId,
                            mcpToolName = mcpName.trim(),
                            agentToolName = agentName.trim().ifBlank { mcpName.trim() },
                            descriptionOverride = desc,
                            inputArgumentKey = argKey.ifBlank { "query" },
                            enabled = true,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
