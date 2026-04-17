package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai_develop.data.McpToolBindingRecord
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.*
import kotlinx.coroutines.delay

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
internal fun AgentsContent(
    state: LLMStateModel,
    viewModel: LLMViewModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy, Boolean) -> Unit,
    onUpdateProfile: (String, UserProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isMobile = maxWidth < 600.dp
        
        if (isMobile) {
            MobileAgentsContent(
                state = state,
                viewModel = viewModel,
                templates = templates,
                onCreateAgent = onCreateAgent,
                onUpdateAgent = onUpdateAgent,
                onUpdateProfile = onUpdateProfile,
                onDeleteAgent = onDeleteAgent,
                onDuplicateAgent = onDuplicateAgent,
                onSelectAgent = onSelectAgent
            )
        } else {
            DesktopAgentsContent(
                state = state,
                viewModel = viewModel,
                templates = templates,
                onCreateAgent = onCreateAgent,
                onUpdateAgent = onUpdateAgent,
                onUpdateProfile = onUpdateProfile,
                onDeleteAgent = onDeleteAgent,
                onDuplicateAgent = onDuplicateAgent,
                onSelectAgent = onSelectAgent
            )
        }
    }
}

@Composable
private fun DesktopAgentsContent(
    state: LLMStateModel,
    viewModel: LLMViewModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy, Boolean) -> Unit,
    onUpdateProfile: (String, UserProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        AgentListSideBar(
            agents = state.agents,
            selectedAgentId = state.selectedAgentId,
            availableToolNames = state.availableToolNames,
            onCreateAgent = onCreateAgent,
            onSelectAgent = onSelectAgent,
            modifier = Modifier.width(300.dp).fillMaxHeight()
        )
        
        VerticalDivider()
        
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val selectedAgent = state.selectedAgent
            if (selectedAgent != null) {
                AgentDetails(
                    agent = selectedAgent,
                    viewModel = viewModel,
                    templates = templates,
                    canDelete = selectedAgent.id != GENERAL_CHAT_ID,
                    onUpdateAgent = { n, p, t, pr, s, m, k, r -> onUpdateAgent(selectedAgent.id, n, p, t, pr, s, m, k, r) },
                    onUpdateProfile = { onUpdateProfile(selectedAgent.id, it) },
                    onDeleteAgent = { onDeleteAgent(selectedAgent.id) },
                    onDuplicateAgent = { onDuplicateAgent(selectedAgent.id) }
                )
            } else {
                EmptyAgentState(onCreateAgent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAgentsContent(
    state: LLMStateModel,
    viewModel: LLMViewModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy, Boolean) -> Unit,
    onUpdateProfile: (String, UserProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    if (state.selectedAgentId == null) {
        AgentListSideBar(
            agents = state.agents,
            selectedAgentId = null,
            availableToolNames = state.availableToolNames,
            onCreateAgent = onCreateAgent,
            onSelectAgent = onSelectAgent,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        val selectedAgent = state.selectedAgent
        if (selectedAgent != null) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(selectedAgent.name) },
                    navigationIcon = {
                        IconButton(onClick = { onSelectAgent(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                )
                AgentDetails(
                    agent = selectedAgent,
                    viewModel = viewModel,
                    templates = templates,
                    canDelete = selectedAgent.id != GENERAL_CHAT_ID,
                    onUpdateAgent = { n, p, t, pr, s, m, k, r -> onUpdateAgent(selectedAgent.id, n, p, t, pr, s, m, k, r) },
                    onUpdateProfile = { onUpdateProfile(selectedAgent.id, it) },
                    onDeleteAgent = { onDeleteAgent(selectedAgent.id) },
                    onDuplicateAgent = { onDuplicateAgent(selectedAgent.id) }
                )
            }
        }
    }
}

@Composable
private fun AgentListSideBar(
    agents: List<Agent>,
    selectedAgentId: String?,
    availableToolNames: List<String>,
    onCreateAgent: () -> Unit,
    onSelectAgent: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Агенты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onCreateAgent) {
                Icon(Icons.Default.Add, contentDescription = "Создать агента")
            }
        }

        AvailableToolsOnAgentsPanel(availableToolNames)

        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(agents) { agent ->
                AgentItem(
                    agent = agent,
                    isSelected = agent.id == selectedAgentId,
                    onClick = { onSelectAgent(agent.id) }
                )
            }
        }
    }
}

@Composable
private fun AvailableToolsOnAgentsPanel(toolNames: List<String>) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Доступные инструменты",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Список для выбранного агента (назначение — вкладка «MCP» в карточке агента).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
        if (toolNames.isEmpty()) {
            Text(
                "Нет инструментов (ни встроенных, ни MCP).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        } else {
            Text(
                toolNames.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AgentItem(
    agent: Agent,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(bgColor)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    agent.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                agent.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                agent.provider.name,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun AgentDetails(
    agent: Agent,
    viewModel: LLMViewModel,
    templates: List<AgentTemplate>,
    canDelete: Boolean,
    onUpdateAgent: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy, Boolean) -> Unit,
    onUpdateProfile: (UserProfile) -> Unit,
    onDeleteAgent: () -> Unit,
    onDuplicateAgent: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("Настройки", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("Память & Персонализация", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("MCP", modifier = Modifier.padding(12.dp))
            }
        }
        
        Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> AgentSettingsTab(
                    agent = agent,
                    templates = templates,
                    canDelete = canDelete,
                    onUpdateAgent = onUpdateAgent,
                    onDeleteAgent = onDeleteAgent,
                    onDuplicateAgent = onDuplicateAgent
                )
                1 -> UserMemoryTab(
                    agent = agent,
                    onUpdateProfile = onUpdateProfile
                )
                2 -> AgentMcpAssignmentTab(agent = agent, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AgentMcpAssignmentTab(
    agent: Agent,
    viewModel: LLMViewModel,
) {
    var rows by remember { mutableStateOf<List<Pair<String, McpToolBindingRecord>>>(emptyList()) }
    var selected by remember(agent.id) { mutableStateOf(agent.mcpAllowedBindingIds.toSet()) }

    LaunchedEffect(agent.mcpAllowedBindingIds) {
        selected = agent.mcpAllowedBindingIds.toSet()
    }

    LaunchedEffect(agent.id) {
        rows = viewModel.loadMcpAssignmentCatalog()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Инструменты MCP", style = MaterialTheme.typography.titleSmall)
        Text(
            "Отметьте привязки из каталога (глобально они настраиваются на вкладке «MCP»). Для агента без отметок MCP не используется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val all = rows.map { it.second.id }.toSet()
                selected = all
                viewModel.setMcpAllowedBindingIds(agent.id, all.toList())
            }) { Text("Все") }
            TextButton(onClick = {
                selected = emptySet()
                viewModel.setMcpAllowedBindingIds(agent.id, emptyList())
            }) { Text("Снять все") }
        }
        if (rows.isEmpty()) {
            Text(
                "Нет доступных привязок: включите сервер на вкладке «MCP» и нажмите «Обновить список tools».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            var lastServer = ""
            rows.forEach { (serverLabel, binding) ->
                if (serverLabel != lastServer) {
                    lastServer = serverLabel
                    Text(
                        serverLabel,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = binding.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + binding.id else selected - binding.id
                            viewModel.setMcpAllowedBindingIds(agent.id, selected.toList())
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(binding.mcpToolName, style = MaterialTheme.typography.bodyMedium)
                        if (binding.description.isNotBlank()) {
                            Text(
                                binding.description.take(280),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSettingsTab(
    agent: Agent,
    templates: List<AgentTemplate>,
    canDelete: Boolean,
    onUpdateAgent: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy, Boolean) -> Unit,
    onDeleteAgent: () -> Unit,
    onDuplicateAgent: () -> Unit
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var systemPrompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    var temperature by remember(agent.id) { mutableStateOf(agent.temperature) }
    var provider by remember(agent.id) { mutableStateOf(agent.provider) }
    var stopWord by remember(agent.id) { mutableStateOf(agent.stopWord) }
    var maxTokens by remember(agent.id) { mutableStateOf(agent.maxTokens) }
    var memoryStrategy by remember(agent.id) { mutableStateOf(agent.memoryStrategy) }
    var windowSize by remember(agent.id) { mutableStateOf(agent.memoryStrategy.windowSize) }
    var ragEnabled by remember(agent.id) { mutableStateOf(agent.ragEnabled) }

    LaunchedEffect(agent.ragEnabled) {
        ragEnabled = agent.ragEnabled
    }

    LaunchedEffect(name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy, ragEnabled) {
        if (name != agent.name ||
            systemPrompt != agent.systemPrompt ||
            temperature != agent.temperature ||
            provider != agent.provider ||
            stopWord != agent.stopWord ||
            maxTokens != agent.maxTokens ||
            memoryStrategy != agent.memoryStrategy ||
            ragEnabled != agent.ragEnabled
        ) {
            delay(500)
            onUpdateAgent(name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy, ragEnabled)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (templates.isNotEmpty()) {
            Text("Использовать шаблон:", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { template ->
                    SuggestionChip(
                        onClick = {
                            name = template.name
                            systemPrompt = template.systemPrompt
                            temperature = template.temperature
                            maxTokens = template.maxTokens
                        },
                        label = { Text(template.name) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя агента") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("Системный промпт") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
            shape = RoundedCornerShape(12.dp)
        )

        TemperatureSlider(
            temp = temperature,
            provider = provider,
            onTempChange = { temperature = it }
        )

        OutlinedTextField(
            value = maxTokens.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> maxTokens = v } },
            label = { Text("Max Tokens") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        LLMSelector(
            currentProvider = provider,
            onProviderChange = { provider = it }
        )

        OutlinedTextField(
            value = stopWord,
            onValueChange = { stopWord = it },
            label = { Text("Stop Word (опционально)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("RAG (контекст из базы)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Подмешивать релевантные фрагменты из сохранённых документов в запрос к модели",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = ragEnabled,
                onCheckedChange = { ragEnabled = it }
            )
        }
        
        MemoryStrategySelector(
            currentStrategy = memoryStrategy, 
            windowSize = windowSize,
            onStrategyChange = { memoryStrategy = it }
        )

        OutlinedTextField(
            value = windowSize.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> 
                windowSize = v 
                memoryStrategy = when(val s = memoryStrategy) {
                    is ChatMemoryStrategy.SlidingWindow -> s.copy(windowSize = v)
                    is ChatMemoryStrategy.StickyFacts -> s.copy(windowSize = v)
                    is ChatMemoryStrategy.Branching -> s.copy(windowSize = v)
                    is ChatMemoryStrategy.Summarization -> s.copy(windowSize = v)
                    is ChatMemoryStrategy.TaskOriented -> s.copy(windowSize = v)
                }
            } },
            label = { Text("Размер окна контекста (сообщений)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = onDuplicateAgent,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Копия")
            }

            if (canDelete) {
                IconButton(
                    onClick = onDeleteAgent,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить")
                }
            }
        }
    }
}

@Composable
private fun UserMemoryTab(
    agent: Agent,
    onUpdateProfile: (UserProfile) -> Unit
) {
    var profile by remember(agent.id) { mutableStateOf(agent.userProfile ?: UserProfile()) }

    LaunchedEffect(profile) {
        if (profile != agent.userProfile) {
            delay(500)
            onUpdateProfile(profile)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Профиль пользователя", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = profile.preferences,
            onValueChange = { profile = profile.copy(preferences = it) },
            label = { Text("Предпочтения (стиль, формат и пр.)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("Например: отвечай кратко, используй Markdown, стиль официально-деловой") },
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = profile.constraints,
            onValueChange = { profile = profile.copy(constraints = it) },
            label = { Text("Ограничения (что не использовать)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("Например: не используй эмодзи, не упоминай конкурентов") },
            shape = RoundedCornerShape(12.dp)
        )

        Text("Модель для обслуживания памяти", style = MaterialTheme.typography.labelMedium)
        LLMSelector(
            currentProvider = profile.memoryModelProvider ?: agent.provider,
            onProviderChange = { profile = profile.copy(memoryModelProvider = it) }
        )
    }
}

@Composable
private fun EmptyAgentState(onCreateAgent: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            Text("Выберите агента или создайте нового", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onCreateAgent, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Создать агента")
            }
        }
    }
}
