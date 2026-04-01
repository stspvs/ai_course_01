package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.*

@Composable
internal fun AgentsContent(
    state: LLMStateModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
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
    onUpdateProfile: (String, UserProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    val selectedAgentId = state.selectedAgentId

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(Color(0xFFF5F5F5))
                .padding(16.dp)
        ) {
            Text(
                "Ваши Агенты",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.agents, key = { it.id }) { agent ->
                    AgentItem(
                        agent = agent,
                        isSelected = selectedAgentId == agent.id,
                        onClick = { onSelectAgent(agent.id) },
                        onDelete = { onDeleteAgent(agent.id) },
                        onDuplicate = { onDuplicateAgent(agent.id) }
                    )
                }
            }

            Button(
                onClick = onCreateAgent,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новый агент")
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val selectedAgent = state.agents.find { it.id == selectedAgentId }
            if (selectedAgent != null) {
                AgentDetailSettings(
                    agent = selectedAgent,
                    onUpdate = { name, prompt, temp, provider, stop, tokens, strategy ->
                        onUpdateAgent(selectedAgent.id, name, prompt, temp, provider, stop, tokens, strategy)
                    },
                    onUpdateProfile = { profile -> onUpdateProfile(selectedAgent.id, profile) },
                    templates = templates
                )
            } else {
                EmptySettingsPlaceholder()
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
    onUpdateProfile: (String, UserProfile) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    val selectedAgentId = state.selectedAgentId
    val selectedAgent = state.agents.find { it.id == selectedAgentId }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedAgent?.name ?: "Выберите агента",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Настройка агента") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    state.agents.forEach { agent ->
                        DropdownMenuItem(
                            text = { Text(agent.name) },
                            onClick = {
                                onSelectAgent(agent.id)
                                expanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    if (agent.id == GENERAL_CHAT_ID) Icons.Default.Email else Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }

            IconButton(
                onClick = onCreateAgent,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF4A148C), contentColor = Color.White)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
            }
        }

        if (selectedAgent != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (selectedAgent.id != GENERAL_CHAT_ID) {
                    IconButton(onClick = { onDuplicateAgent(selectedAgent.id) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Копировать", tint = Color.Gray)
                    }
                    IconButton(onClick = { onDeleteAgent(selectedAgent.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color.Gray)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AgentDetailSettings(
                    agent = selectedAgent,
                    onUpdate = { name, prompt, temp, provider, stop, tokens, strategy ->
                        onUpdateAgent(selectedAgent.id, name, prompt, temp, provider, stop, tokens, strategy)
                    },
                    onUpdateProfile = { profile -> onUpdateProfile(selectedAgent.id, profile) },
                    templates = templates
                )
            }
        } else {
            EmptySettingsPlaceholder()
        }
    }
}

@Composable
private fun EmptySettingsPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color.Gray.copy(alpha = 0.3f)
        )
        Text(
            "Выберите агента для настройки",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun AgentItem(
    agent: Agent,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val isGeneral = agent.id == GENERAL_CHAT_ID
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFEDE7F6) else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF4A148C)) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isGeneral) Color(0xFF4A148C) else Color(0xFF9575CD)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isGeneral) Icons.Default.Email else Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when(agent.provider) {
                        is LLMProvider.DeepSeek -> "DeepSeek"
                        is LLMProvider.Yandex -> "YandexGPT"
                        is LLMProvider.OpenRouter -> "OpenRouter"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            if (!isGeneral) {
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.Refresh, contentDescription = "Duplicate", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun AgentDetailSettings(
    agent: Agent,
    onUpdate: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit,
    onUpdateProfile: (UserProfile) -> Unit,
    templates: List<AgentTemplate>
) {
    var selectedTabIndex by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Text(
            text = if (agent.id == GENERAL_CHAT_ID) "Общий чат: ${agent.name}" else "Агент: ${agent.name}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4A148C),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            contentColor = Color(0xFF4A148C),
            divider = {}
        ) {
            Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }) {
                Box(Modifier.padding(12.dp)) { Text("Основные", style = MaterialTheme.typography.bodyMedium) }
            }
            Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }) {
                Box(Modifier.padding(12.dp)) { Text("Память", style = MaterialTheme.typography.bodyMedium) }
            }
            Tab(selected = selectedTabIndex == 2, onClick = { selectedTabIndex = 2 }) {
                Box(Modifier.padding(12.dp)) { Text("Профиль", style = MaterialTheme.typography.bodyMedium) }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTabIndex) {
                0 -> MainSettingsTab(agent, templates, onUpdate)
                1 -> SummarySettingsTab(agent, onUpdate)
                2 -> UserProfileTab(agent, onUpdateProfile)
            }
        }
    }
}

@Composable
fun MainSettingsTab(
    agent: Agent,
    templates: List<AgentTemplate>,
    onUpdate: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var prompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    var temp by remember(agent.id) { mutableDoubleStateOf(agent.temperature) }
    var stopWord by remember(agent.id) { mutableStateOf(agent.stopWord) }
    var maxTokens by remember(agent.id) { mutableIntStateOf(agent.maxTokens) }
    var provider by remember(agent.id) { mutableStateOf(agent.provider) }

    val isGeneral = agent.id == GENERAL_CHAT_ID

    LaunchedEffect(name, prompt, temp, provider, stopWord, maxTokens) {
        if (name != agent.name || prompt != agent.systemPrompt || temp != agent.temperature || 
            provider != agent.provider || stopWord != agent.stopWord || maxTokens != agent.maxTokens) {
            onUpdate(name, prompt, temp, provider, stopWord, maxTokens, agent.memoryStrategy)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isGeneral) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Применить шаблон:", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(templates) { template ->
                        SuggestionChip(
                            onClick = {
                                name = template.name
                                prompt = template.systemPrompt
                                temp = template.temperature
                                maxTokens = template.maxTokens
                            },
                            label = { Text(template.name, fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            enabled = !isGeneral
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Системный промпт (инструкции)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            shape = RoundedCornerShape(12.dp)
        )

        Text("Модель и параметры", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        LLMSelector(currentProvider = provider, onProviderChange = { provider = it })

        TemperatureSlider(temp = temp, provider = provider, onTempChange = { temp = it })

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = maxTokens.toString(),
                onValueChange = { it.toIntOrNull()?.let { v -> maxTokens = v } },
                label = { Text("Макс. токенов") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = stopWord,
                onValueChange = { stopWord = it },
                label = { Text("Стоп-слово") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummarySettingsTab(
    agent: Agent,
    onUpdate: (String, String, Double, LLMProvider, String, Int, ChatMemoryStrategy) -> Unit
) {
    var memoryStrategy by remember(agent.id) { mutableStateOf(agent.memoryStrategy) }
    var windowSize by remember(agent.id) { mutableIntStateOf(agent.memoryStrategy.windowSize) }

    LaunchedEffect(memoryStrategy) {
        if (memoryStrategy != agent.memoryStrategy) {
            onUpdate(agent.name, agent.systemPrompt, agent.temperature, agent.provider, agent.stopWord, agent.maxTokens, memoryStrategy)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
            label = { Text("Кол-во последних сообщений в контексте") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        AnimatedVisibility(
            visible = memoryStrategy is ChatMemoryStrategy.TaskOriented,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val strategy = memoryStrategy as? ChatMemoryStrategy.TaskOriented
            if (strategy != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider()
                    Text("Параметры Рабочей памяти", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = strategy.updateInterval.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { v -> 
                                memoryStrategy = strategy.copy(updateInterval = v)
                            }
                        },
                        label = { Text("Авто-обновление задачи каждые N сообщений") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = if (strategy.analysisWindowSize <= 0) "Все" else strategy.analysisWindowSize.toString(),
                        onValueChange = { 
                            val v = if (it.equals("все", ignoreCase = true)) 0 else it.toIntOrNull() ?: 0
                            memoryStrategy = strategy.copy(analysisWindowSize = v)
                        },
                        label = { Text("Окно анализа задачи (сообщений или 'Все')") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Text("Текущее состояние:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = strategy.currentTask ?: "",
                        onValueChange = { memoryStrategy = strategy.copy(currentTask = it) },
                        label = { Text("Текущая задача") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = strategy.progress ?: "",
                        onValueChange = { memoryStrategy = strategy.copy(progress = it) },
                        label = { Text("Текущий прогресс") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = memoryStrategy is ChatMemoryStrategy.StickyFacts,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val strategy = memoryStrategy as? ChatMemoryStrategy.StickyFacts
            if (strategy != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider()
                    Text("Параметры Sticky Facts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = strategy.updateInterval.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { v -> 
                                memoryStrategy = strategy.copy(updateInterval = v)
                            }
                        },
                        label = { Text("Обновлять факты каждые N сообщений") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Text("Извлеченные факты:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    // Для упрощения редактирования Map, можно сделать текстовое поле или список
                    // Пока добавим текстовое описание
                    Text("Факты обновляются автоматически LLM.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }

        AnimatedVisibility(
            visible = memoryStrategy is ChatMemoryStrategy.Summarization,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val strategy = memoryStrategy as? ChatMemoryStrategy.Summarization
            if (strategy != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider()

                    Text("Параметры суммаризации", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = strategy.summaryPrompt,
                        onValueChange = { memoryStrategy = strategy.copy(summaryPrompt = it) },
                        label = { Text("Инструкция для суммаризации") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Глубина суммаризации", style = MaterialTheme.typography.labelMedium)
                        
                        var depthExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = depthExpanded,
                            onExpandedChange = { depthExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = strategy.summaryDepth.description,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = depthExpanded) },
                                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = depthExpanded,
                                onDismissRequest = { depthExpanded = false }
                            ) {
                                SummaryDepth.entries.forEach { depth ->
                                    DropdownMenuItem(
                                        text = { Text(depth.description) },
                                        onClick = {
                                            memoryStrategy = strategy.copy(summaryDepth = depth)
                                            depthExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Text("Текущее саммари:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = strategy.summary ?: "",
                        onValueChange = { memoryStrategy = strategy.copy(summary = it) },
                        label = { Text("Контекст диалога") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UserProfileTab(
    agent: Agent,
    onUpdateProfile: (UserProfile) -> Unit
) {
    var profile by remember(agent.id) { mutableStateOf(agent.userProfile ?: UserProfile()) }

    LaunchedEffect(profile) {
        if (profile != agent.userProfile) {
            onUpdateProfile(profile)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Глобальные настройки памяти", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        
        Text("Модель для работы с памятью", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        
        LLMSelector(
            currentProvider = profile.memoryModelProvider ?: agent.provider,
            onProviderChange = { profile = profile.copy(memoryModelProvider = it) }
        )
        Text(
            "Эта модель будет использоваться для фонового извлечения фактов, суммаризации и анализа задач.",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )

        HorizontalDivider()

        Text("Ваше имя", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = profile.name,
            onValueChange = { profile = profile.copy(name = it) },
            label = { Text("Как агент должен к вам обращаться") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
    }
}
