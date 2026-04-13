package com.example.ai_develop.presentation.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.example.ai_develop.data.McpDeploymentKind
import com.example.ai_develop.data.McpWireKind
import com.example.ai_develop.data.McpServerLinkStatus
import com.example.ai_develop.data.McpServerRecord
import com.example.ai_develop.data.displayHint
import com.example.ai_develop.data.displayTitle
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.presentation.GraylogSettingsViewModel
import com.example.ai_develop.presentation.McpServersViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun McpServersContent(
    viewModel: McpServersViewModel = koinViewModel(),
) {
    val graylogVm: GraylogSettingsViewModel = koinViewModel()
    val ui by viewModel.uiState.collectAsState()
    var showAddServer by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerRecord?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        GraylogSettingsSection(graylogVm)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
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
                        onRunBoot = { viewModel.runServerStartCommand(server.id) },
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
                McpServerPanelStatusBlock(server = server, toolsInDbCount = ui.bindings.size)
                Text(
                    "Список обновляется кнопкой «Обновить» на карточке. Новые инструменты после синхронизации включаются по умолчанию; при необходимости отключите переключателем.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    OutlinedTextField(
                        value = ui.testInput,
                        onValueChange = viewModel::setTestInput,
                        label = { Text("Текст для теста вызова (один строковый аргумент по схеме)") },
                        modifier = Modifier.weight(1f),
                    )
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
            title = "Добавить MCP-сервер",
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
}

@Composable
private fun GraylogSettingsSection(viewModel: GraylogSettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Локальный Graylog", style = MaterialTheme.typography.headlineSmall)
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "URL веб-интерфейса и при необходимости команда запуска стека (Docker). На Windows команда выполняется через cmd /c.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.webUrl,
                    onValueChange = viewModel::setWebUrl,
                    label = { Text("URL веб-интерфейса Graylog") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://127.0.0.1:9000") },
                )
                OutlinedTextField(
                    value = state.startCommand,
                    onValueChange = viewModel::setStartCommand,
                    label = { Text("Команда запуска (опционально)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("docker compose -f C:\\graylog\\docker-compose.yml up -d") },
                    minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.save() }) { Text("Сохранить") }
                    OutlinedButton(onClick = { viewModel.openWebUi() }) { Text("Открыть в браузере") }
                    OutlinedButton(onClick = { viewModel.startGraylog() }) { Text("Запустить команду") }
                }
                state.message?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun McpServerLinkStatusRow(
    status: McpServerLinkStatus,
    lastError: String?,
) {
    val color = when (status) {
        McpServerLinkStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        McpServerLinkStatus.NOT_RUNNING -> MaterialTheme.colorScheme.tertiary
        McpServerLinkStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerLinkStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Column {
        Text(
            status.displayTitle(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
        if (!lastError.isNullOrBlank() && status != McpServerLinkStatus.CONNECTED) {
            Text(
                lastError.take(140) + if (lastError.length > 140) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun McpServerPanelStatusBlock(server: McpServerRecord, toolsInDbCount: Int) {
    val color = when (server.linkStatus) {
        McpServerLinkStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        McpServerLinkStatus.NOT_RUNNING -> MaterialTheme.colorScheme.tertiary
        McpServerLinkStatus.ERROR -> MaterialTheme.colorScheme.error
        McpServerLinkStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    Text(
        server.linkStatus.displayTitle(),
        style = MaterialTheme.typography.titleSmall,
        color = color,
    )
    Text(
        server.linkStatus.displayHint(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Инструментов в БД: $toolsInDbCount",
        style = MaterialTheme.typography.bodySmall,
    )
    val err = server.lastSyncError
    if (!err.isNullOrBlank() && server.linkStatus != McpServerLinkStatus.CONNECTED) {
        Text(
            err,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
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
    onRunBoot: () -> Unit,
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
                        when (server.wireKind) {
                            McpWireKind.STREAMABLE_HTTP -> "Транспорт: Streamable HTTP"
                            McpWireKind.STDIO -> "Транспорт: STDIO (процесс)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    McpServerLinkStatusRow(
                        status = server.linkStatus,
                        lastError = server.lastSyncError,
                    )
                    Text(
                        when (server.deploymentKind) {
                            McpDeploymentKind.LOCAL -> "Локальный"
                            McpDeploymentKind.REMOTE -> "Удалённый"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (server.enabled) "Включён" else "Выключен",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (server.startCommand.isNotBlank()) {
                        Text(
                            "Boot: ${server.startCommand.take(80)}${if (server.startCommand.length > 80) "…" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (server.startCommand.isNotBlank()) {
                        OutlinedButton(onClick = onRunBoot) {
                            Text(
                                if (server.wireKind == McpWireKind.STDIO) "Сброс stdio"
                                else "Запуск",
                            )
                        }
                    }
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
    onTest: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(binding.mcpToolName, style = MaterialTheme.typography.titleSmall)
                    if (binding.description.isNotBlank()) {
                        Text(binding.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(checked = binding.enabled, onCheckedChange = onToggle)
                TextButton(onClick = onTest) { Text("Тест") }
            }
            val schemaPreview = when {
                binding.inputSchemaJson.isBlank() || binding.inputSchemaJson == "{}" -> null
                binding.inputSchemaJson.length > 600 -> binding.inputSchemaJson.take(600) + "…"
                else -> binding.inputSchemaJson
            }
            schemaPreview?.let { s ->
                Spacer(Modifier.height(6.dp))
                Text("Параметры:", style = MaterialTheme.typography.labelSmall)
                Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val isCreate = initial == null
    var name by remember(initial?.id) { mutableStateOf(initial?.displayName ?: "") }
    var wireKind by remember(initial?.id) {
        mutableStateOf(initial?.wireKind ?: McpWireKind.STREAMABLE_HTTP)
    }
    var url by remember(initial?.id) {
        mutableStateOf(
            when {
                initial == null -> "http://127.0.0.1:8765/mcp"
                initial.wireKind == McpWireKind.STDIO -> initial.baseUrl.ifBlank { "stdio://local" }
                else -> initial.baseUrl
            },
        )
    }
    var headers by remember(initial?.id) { mutableStateOf(initial?.headersJson ?: "{}") }
    var enabled by remember(initial?.id) { mutableStateOf(initial?.enabled ?: true) }
    var deploymentKind by remember(initial?.id) {
        mutableStateOf(initial?.deploymentKind ?: McpDeploymentKind.REMOTE)
    }
    var startCommand by remember(initial?.id) { mutableStateOf(initial?.startCommand ?: "") }

    val deploymentIndex = when (deploymentKind) {
        McpDeploymentKind.LOCAL -> 0
        McpDeploymentKind.REMOTE -> 1
    }
    val urlSupportingText = when (deploymentKind) {
        McpDeploymentKind.LOCAL ->
            "Endpoint Streamable HTTP на этой машине или в LAN, обычно …/mcp"
        McpDeploymentKind.REMOTE ->
            "HTTPS- или HTTP-адрес опубликованного MCP за прокси / в облаке"
    }
    val headersSupportingText = when (deploymentKind) {
        McpDeploymentKind.LOCAL ->
            "Чаще не нужен. Для локального сервера с авторизацией — JSON заголовков"
        McpDeploymentKind.REMOTE ->
            "Опционально: Bearer, API-key и др. в формате JSON-объекта"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isCreate) {
                    Text(
                        "Выберите транспорт: Streamable HTTP (сервер уже слушает URL) или STDIO (приложение запускает процесс и общается по stdin/stdout). Список tools подтягивается кнопкой «Обновить» на карточке.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Отображаемое имя", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название в списке") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Например: GitHub MCP") },
                )

                HorizontalDivider()

                Text("Транспорт MCP", style = MaterialTheme.typography.titleSmall)
                Text(
                    "HTTP — к уже запущенному endpoint. STDIO — клиент запускает процесс (например через gradlew.bat) и ведёт MCP по stdin/stdout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = wireKind == McpWireKind.STREAMABLE_HTTP,
                        onClick = { wireKind = McpWireKind.STREAMABLE_HTTP },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Streamable HTTP") }
                    SegmentedButton(
                        selected = wireKind == McpWireKind.STDIO,
                        onClick = { wireKind = McpWireKind.STDIO },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("STDIO") }
                }

                if (wireKind == McpWireKind.STREAMABLE_HTTP) {
                    Text("Тип сервера", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Метка и подсказки для URL; для удалённого чаще нужны заголовки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = deploymentIndex == 0,
                            onClick = { deploymentKind = McpDeploymentKind.LOCAL },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Локальный") }
                        SegmentedButton(
                            selected = deploymentIndex == 1,
                            onClick = { deploymentKind = McpDeploymentKind.REMOTE },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Удалённый") }
                    }
                    Text(
                        when (deploymentKind) {
                            McpDeploymentKind.LOCAL ->
                                "Процесс или контейнер на вашей стороне (127.0.0.1 или хост в сети)."
                            McpDeploymentKind.REMOTE ->
                                "Сервис в интернете или в корпоративной сети."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()

                    Text("Команда запуска (boot)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Опционально: отдельный процесс до обращения к URL (Docker и т.). На Windows — cmd /c.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = startCommand,
                        onValueChange = { startCommand = it },
                        label = { Text("Команда запуска процесса") },
                        supportingText = {
                            Text(
                                "Docker/npx/.exe или Gradle для фонового сервиса — см. docs/windows-launch-string-kmp.md",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("""cd /d "C:\path\to\day_1" && gradlew.bat :composeApp:run""") },
                    )

                    HorizontalDivider()

                    Text("Подключение", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Base URL endpoint") },
                        supportingText = { Text(urlSupportingText) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = {
                            Text(
                                when (deploymentKind) {
                                    McpDeploymentKind.LOCAL -> "http://127.0.0.1:8765/mcp"
                                    McpDeploymentKind.REMOTE -> "https://api.example.com/mcp"
                                },
                            )
                        },
                    )

                    Text("Заголовки HTTP", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text("Headers (JSON)") },
                        supportingText = { Text(headersSupportingText) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = { Text("""{"Authorization": "Bearer …"}""") },
                    )
                } else {
                    HorizontalDivider()
                    Text("Команда процесса (stdio)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Обязательно. Строка для cmd /c (Windows): cd в корень с gradlew.bat, затем отдельная задача Gradle, которая поднимает только MCP по stdin/stdout (StdioServerTransport). Не используйте :composeApp:run — это откроет второе окно этого приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = startCommand,
                        onValueChange = { startCommand = it },
                        label = { Text("Команда запуска MCP (stdio)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text("""cd /d "C:\path\to\project" && gradlew.bat :mcpStdio:run""") },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Сервер активен", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Выключенный сервер не участвует в вызовах инструментов.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = initial?.id ?: McpServersViewModel.newId()
                    val outBase = when (wireKind) {
                        McpWireKind.STDIO -> "stdio://local"
                        McpWireKind.STREAMABLE_HTTP -> url.trim().ifBlank { "http://127.0.0.1:8765/mcp" }
                    }
                    val outHeaders = when (wireKind) {
                        McpWireKind.STDIO -> "{}"
                        McpWireKind.STREAMABLE_HTTP -> headers.ifBlank { "{}" }
                    }
                    onSave(
                        McpServerRecord(
                            id = id,
                            displayName = name.ifBlank { "MCP" },
                            baseUrl = outBase,
                            enabled = enabled,
                            headersJson = outHeaders,
                            lastSyncToolsJson = initial?.lastSyncToolsJson ?: "",
                            lastSyncError = initial?.lastSyncError,
                            lastSyncAt = initial?.lastSyncAt ?: 0L,
                            deploymentKind = deploymentKind,
                            startCommand = startCommand.trim(),
                            linkStatus = initial?.linkStatus ?: McpServerLinkStatus.UNKNOWN,
                            wireKind = wireKind,
                        ),
                    )
                },
            ) { Text(if (isCreate) "Добавить" else "Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
