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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.*

@Composable
internal fun AgentsContent(
    state: LLMStateModel,
    templates: List<AgentTemplate>,
    onCreateAgent: () -> Unit,
    onUpdateAgent: (String, String, String, Double, LLMProvider, String, Int) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onSelectAgent: (String?) -> Unit
) {
    val selectedAgent = state.agents.find { it.id == state.selectedAgentId }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // ЛЕВАЯ ПАНЕЛЬ: Список агентов
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(8.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Агенты",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onCreateAgent) {
                    Icon(Icons.Default.Add, contentDescription = "Создать", tint = Color(0xFF4A148C))
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.agents) { agent ->
                    AgentListTile(
                        name = agent.name,
                        isSelected = state.selectedAgentId == agent.id,
                        isGeneral = agent.id == GENERAL_CHAT_ID,
                        onClick = { onSelectAgent(agent.id) }
                    )
                }
            }

            if (state.selectedAgentId != null && state.selectedAgentId != GENERAL_CHAT_ID) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = { state.selectedAgentId.let { onDuplicateAgent(it) } },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Копия", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { state.selectedAgentId.let { onDeleteAgent(it) } },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red.copy(alpha = 0.7f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Удалить", fontSize = 12.sp)
                    }
                }
            }
        }

        // ПРАВАЯ ПАНЕЛЬ: Настройки
        Box(
            modifier = Modifier
                .weight(2.5f)
                .fillMaxHeight()
                .padding(8.dp)
        ) {
            if (selectedAgent != null) {
                AgentDetailSettings(
                    agent = selectedAgent,
                    onUpdate = { n, p, t, pr, s, m -> onUpdateAgent(selectedAgent.id, n, p, t, pr, s, m) },
                    templates = templates
                )
            } else {
                EmptyAgentPlaceholder()
            }
        }
    }
}

@Composable
private fun EmptyAgentPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(16.dp)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Face,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.LightGray
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Выберите агента из списка слева\nили создайте нового",
            textAlign = TextAlign.Center,
            color = Color.Gray,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun AgentListTile(name: String, isSelected: Boolean, isGeneral: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = if (isSelected) Color(0xFFF3E5F5) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGeneral) Icons.Default.Star else Icons.Default.Person,
                contentDescription = null,
                tint = if (isSelected) Color(0xFF4A148C) else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color(0xFF4A148C) else Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AgentDetailSettings(
    agent: Agent,
    onUpdate: (String, String, Double, LLMProvider, String, Int) -> Unit,
    templates: List<AgentTemplate>
) {
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var prompt by remember(agent.id) { mutableStateOf(agent.systemPrompt) }
    var temp by remember(agent.id) { mutableDoubleStateOf(agent.temperature) }
    var stopWord by remember(agent.id) { mutableStateOf(agent.stopWord) }
    var maxTokens by remember(agent.id) { mutableIntStateOf(agent.maxTokens) }
    var provider by remember(agent.id) { mutableStateOf(agent.provider) }

    val isGeneral = agent.id == GENERAL_CHAT_ID

    LaunchedEffect(name, prompt, temp, provider, stopWord, maxTokens) {
        onUpdate(name, prompt, temp, provider, stopWord, maxTokens)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = if (isGeneral) "Настройки общего чата" else "Настройки агента",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4A148C)
        )

        // Шаблоны для быстрого заполнения (скрываем для общего чата, если хотим "дефолтные настройки")
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
            enabled = !isGeneral // Запрещаем менять имя общего чата
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

        TemperatureSlider(temp = temp, onTempChange = { temp = it })

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
        
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Изменения применяются мгновенно",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun TemperatureSlider(temp: Double, onTempChange: (Double) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val tempFormatted = ((temp * 10).toInt() / 10.0).toString()
            Text("Температура: $tempFormatted", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = when {
                    temp <= 0.3 -> "Точный"
                    temp <= 0.8 -> "Баланс"
                    else -> "Креативный"
                },
                color = Color(0xFF4A148C),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = temp.toFloat(),
            onValueChange = { onTempChange(it.toDouble()) },
            valueRange = 0f..2f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4A148C),
                activeTrackColor = Color(0xFF4A148C)
            )
        )
    }
}
