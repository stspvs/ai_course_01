package com.example.ai_develop.presentation.compose

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
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int, Int, String, SummaryDepth, ChatMemoryStrategy) -> Unit,
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
                    onUpdate = { name, prompt, temp, provider, stop, tokens, keepLast, sPrompt, depth, strategy ->
                        onUpdateAgent(selectedAgent.id, name, prompt, temp, provider, stop, tokens, keepLast, sPrompt, depth, strategy)
                    },
                    templates = templates
                )
            } else {
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
        }
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
    onUpdate: (String, String, Double, LLMProvider, String, Int, Int, String, SummaryDepth, ChatMemoryStrategy) -> Unit,
    templates: List<AgentTemplate>
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Text(
            text = if (agent.id == GENERAL_CHAT_ID) "Общий чат: ${agent.name}" else "Агент: ${agent.name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4A148C),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = Color.Transparent,
            contentColor = Color(0xFF4A148C),
            divider = {}
        ) {
            Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }) {
                Box(Modifier.padding(12.dp)) { Text("Основные") }
            }
            Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }) {
                Box(Modifier.padding(12.dp)) { Text("Память и Контекст") }
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTabIndex == 0) {
                MainSettingsTab(agent, templates, onUpdate)
            } else {
                SummarySettingsTab(agent, onUpdate)
            }
        }
    }
}

@Composable
fun MainSettingsTab(
    agent: Agent,
    templates: List<AgentTemplate>,
    onUpdate: (String, String, Double, LLMProvider, String, Int, Int, String, SummaryDepth, ChatMemoryStrategy) -> Unit
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
            onUpdate(name, prompt, temp, provider, stopWord, maxTokens, agent.keepLastMessagesCount, agent.summaryPrompt, agent.summaryDepth, agent.memoryStrategy)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
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

@Composable
fun SummarySettingsTab(
    agent: Agent,
    onUpdate: (String, String, Double, LLMProvider, String, Int, Int, String, SummaryDepth, ChatMemoryStrategy) -> Unit
) {
    var keepLastMessagesCount by remember(agent.id) { mutableIntStateOf(agent.keepLastMessagesCount) }
    var summaryPrompt by remember(agent.id) { mutableStateOf(agent.summaryPrompt) }
    var summaryDepth by remember(agent.id) { mutableStateOf(agent.summaryDepth) }
    var memoryStrategy by remember(agent.id) { mutableStateOf(agent.memoryStrategy) }

    LaunchedEffect(keepLastMessagesCount, summaryPrompt, summaryDepth, memoryStrategy) {
        if (keepLastMessagesCount != agent.keepLastMessagesCount || 
            summaryPrompt != agent.summaryPrompt || 
            summaryDepth != agent.summaryDepth ||
            memoryStrategy != agent.memoryStrategy) {
            onUpdate(agent.name, agent.systemPrompt, agent.temperature, agent.provider, agent.stopWord, agent.maxTokens, keepLastMessagesCount, summaryPrompt, summaryDepth, memoryStrategy)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        MemoryStrategySelector(currentStrategy = memoryStrategy, onStrategyChange = { memoryStrategy = it })

        OutlinedTextField(
            value = keepLastMessagesCount.toString(),
            onValueChange = { it.toIntOrNull()?.let { v -> keepLastMessagesCount = v } },
            label = { Text("Кол-во последних сообщений в контексте") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        HorizontalDivider()

        Text("Параметры суммаризации", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = summaryPrompt,
            onValueChange = { summaryPrompt = it },
            label = { Text("Инструкция для суммаризации") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            shape = RoundedCornerShape(12.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Глубина суммаризации", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryDepth.entries.forEach { depth ->
                    FilterChip(
                        selected = summaryDepth == depth,
                        onClick = { summaryDepth = depth },
                        label = { Text(depth.description) }
                    )
                }
            }
        }
    }
}
