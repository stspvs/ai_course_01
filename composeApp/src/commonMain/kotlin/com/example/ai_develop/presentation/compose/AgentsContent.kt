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
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.*
import kotlinx.coroutines.delay

@Suppress("UnusedBoxWithConstraintsScope")
@Composable
internal fun AgentsContent(
    state: LLMStateModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
    onUpdateProfile: (String, AgentProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isMobile = maxWidth < 600.dp
        
        if (isMobile) {
            MobileAgentsContent(
                state = state,
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
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
    onUpdateProfile: (String, AgentProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        AgentListSideBar(
            agents = state.agents,
            selectedAgentId = state.selectedAgentId,
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
                    templates = templates,
                    onUpdateAgent = { n, p, t, pr, s, m, k -> onUpdateAgent(selectedAgent.id, n, p, t, pr, s, m, k) },
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
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
    onUpdateProfile: (String, AgentProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    if (state.selectedAgentId == null) {
        AgentListSideBar(
            agents = state.agents,
            selectedAgentId = null,
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
                    templates = templates,
                    onUpdateAgent = { n, p, t, pr, s, m, k -> onUpdateAgent(selectedAgent.id, n, p, t, pr, s, m, k) },
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
    templates: List<AgentTemplate>,
    onUpdateAgent: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
    onUpdateProfile: (AgentProfile) -> Unit,
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
                Text("Память & Личность", modifier = Modifier.padding(12.dp))
            }
        }
        
        Box(modifier = Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> AgentSettingsTab(
                    agent = agent,
                    templates = templates,
                    onUpdateAgent = onUpdateAgent,
                    onDeleteAgent = onDeleteAgent,
                    onDuplicateAgent = onDuplicateAgent
                )
                1 -> AgentMemoryTab(
                    agent = agent,
                    onUpdateProfile = onUpdateProfile
                )
            }
        }
    }
}

@Composable
private fun AgentSettingsTab(
    agent: Agent,
    templates: List<AgentTemplate>,
    onUpdateAgent: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
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

    LaunchedEffect(name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy) {
        if (name != agent.name ||
            systemPrompt != agent.systemPrompt ||
            temperature != agent.temperature ||
            provider != agent.provider ||
            stopWord != agent.stopWord ||
            maxTokens != agent.maxTokens ||
            memoryStrategy != agent.memoryStrategy
        ) {
            delay(500)
            onUpdateAgent(name, systemPrompt, temperature, provider, stopWord, maxTokens, memoryStrategy)
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

            IconButton(
                onClick = onDeleteAgent,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить")
            }
        }
    }
}

@Composable
private fun AgentMemoryTab(
    agent: Agent,
    onUpdateProfile: (AgentProfile) -> Unit
) {
    var profile by remember(agent.id) { mutableStateOf(agent.agentProfile ?: AgentProfile()) }

    LaunchedEffect(profile) {
        if (profile != agent.agentProfile) {
            delay(500)
            onUpdateProfile(profile)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Профиль агента", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(
            value = profile.name,
            onValueChange = { profile = profile.copy(name = it) },
            label = { Text("Доп. имя / Роль") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        OutlinedTextField(
            value = profile.about,
            onValueChange = { profile = profile.copy(about = it) },
            label = { Text("Описание личности") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
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
