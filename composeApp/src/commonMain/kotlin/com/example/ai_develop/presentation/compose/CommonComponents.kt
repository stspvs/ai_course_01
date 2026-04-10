package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.*
import kotlin.math.round

/**
 * Перехватывает Enter до обработки [androidx.compose.foundation.text.BasicTextField], иначе
 * в многострочном поле вставляется перевод строки и отправка не срабатывает.
 * Shift+Enter оставляем для новой строки.
 */
internal fun Modifier.sendMessageOnEnter(
    input: String,
    onSend: () -> Unit,
): Modifier = onPreviewKeyEvent { keyEvent ->
    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (keyEvent.key != Key.Enter && keyEvent.key != Key.NumPadEnter) return@onPreviewKeyEvent false
    if (keyEvent.isShiftPressed) return@onPreviewKeyEvent false
    if (input.isNotBlank()) {
        onSend()
    }
    true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LLMSelector(
    currentProvider: LLMProvider,
    onProviderChange: (LLMProvider) -> Unit
) {
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val providers = listOf("Yandex", "DeepSeek", "OpenRouter")
    val deepSeekModels = listOf("deepseek-chat", "deepseek-coder")
    val openRouterModels = listOf("google/gemini-2.0-flash-001", "anthropic/claude-3.5-sonnet", "openai/gpt-4o", "deepseek/deepseek-r1:free")
    val yandexModels = listOf("yandexgpt-5.1/latest", "yandexgpt-lite/latest", "yandexgpt/latest")

    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
            OutlinedTextField(
                value = when(currentProvider){ is LLMProvider.Yandex -> "Yandex"; is LLMProvider.DeepSeek -> "DeepSeek"; is LLMProvider.OpenRouter -> "OpenRouter" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                providers.forEach { p ->
                    DropdownMenuItem(text = { Text(p) }, onClick = {
                        val firstModel = when(p){ "DeepSeek" -> deepSeekModels.first(); "OpenRouter" -> openRouterModels.first(); else -> yandexModels.first() }
                        onProviderChange(when(p){ "DeepSeek" -> LLMProvider.DeepSeek(firstModel); "OpenRouter" -> LLMProvider.OpenRouter(firstModel); else -> LLMProvider.Yandex(firstModel) })
                        providerExpanded = false
                    })
                }
            }
        }

        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
            OutlinedTextField(
                value = currentProvider.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Модель") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                val models = when(currentProvider){ is LLMProvider.DeepSeek -> deepSeekModels; is LLMProvider.OpenRouter -> openRouterModels; is LLMProvider.Yandex -> yandexModels }
                models.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        onProviderChange(when(currentProvider){ is LLMProvider.DeepSeek -> LLMProvider.DeepSeek(m); is LLMProvider.OpenRouter -> LLMProvider.OpenRouter(m); is LLMProvider.Yandex -> LLMProvider.Yandex(m) })
                        modelExpanded = false
                    })
                }
            }
        }
    }
}

@Composable
internal fun TemperatureSlider(
    temp: Double,
    provider: LLMProvider,
    onTempChange: (Double) -> Unit
) {
    val isDeepSeek = provider is LLMProvider.DeepSeek
    val maxTemp = if (isDeepSeek) 2.0 else 1.0
    val coercedTemp = temp.coerceIn(0.0, maxTemp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayTemp = (round(coercedTemp * 10) / 10).toString()
            Text("Температура: $displayTemp", style = MaterialTheme.typography.labelMedium)
            val description = when {
                coercedTemp < 0.3 * maxTemp -> "Точные ответы"
                coercedTemp < 0.7 * maxTemp -> "Сбалансированно"
                else -> "Креативность"
            }
            Text(description, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Slider(
            value = coercedTemp.toFloat(),
            onValueChange = { onTempChange(it.toDouble()) },
            valueRange = 0f..maxTemp.toFloat(),
            steps = if (isDeepSeek) 19 else 9,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4A148C),
                activeTrackColor = Color(0xFF4A148C)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryStrategySelector(
    currentStrategy: ChatMemoryStrategy,
    windowSize: Int,
    onStrategyChange: (ChatMemoryStrategy) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    val strategies = listOf(
        "Sliding Window" to "Хранит последние N сообщений",
        "Summarization" to "Сжимает старые сообщения в краткую суть",
        "Sticky Facts" to "Извлекает и хранит ключевые факты",
        "Branching" to "Позволяет создавать ветки диалога",
        "Task Oriented" to "Хранит текущую цель и прогресс по ней"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Стратегия памяти", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            val currentName = when(currentStrategy) {
                is ChatMemoryStrategy.SlidingWindow -> "Sliding Window"
                is ChatMemoryStrategy.Summarization -> "Summarization"
                is ChatMemoryStrategy.StickyFacts -> "Sticky Facts"
                is ChatMemoryStrategy.Branching -> "Branching"
                is ChatMemoryStrategy.TaskOriented -> "Task Oriented"
            }
            
            OutlinedTextField(
                value = currentName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                strategies.forEach { (name, desc) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text(desc, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        },
                        onClick = {
                            onStrategyChange(when(name) {
                                "Sliding Window" -> ChatMemoryStrategy.SlidingWindow(windowSize)
                                "Summarization" -> ChatMemoryStrategy.Summarization(windowSize)
                                "Sticky Facts" -> ChatMemoryStrategy.StickyFacts(windowSize)
                                "Branching" -> ChatMemoryStrategy.Branching(windowSize)
                                "Task Oriented" -> ChatMemoryStrategy.TaskOriented(windowSize)
                                else -> currentStrategy
                            })
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage) {
    if (message.isSystemNotification) {
        SystemNotificationBubble(message.message)
        return
    }

    val isUser = message.source == SourceType.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) Color(0xFF1976D2) else Color.White,
            contentColor = if (isUser) Color.White else Color.Black,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 0.dp,
                bottomEnd = if (isUser) 0.dp else 16.dp
            ),
            tonalElevation = 1.dp,
            shadowElevation = 1.dp
        ) {
            Column {
                SelectionContainer {
                    Text(
                        text = message.message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (message.message.isNotBlank()) {
                    Text(
                        text = "${message.estimatedTokenCount()} токенов",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp),
                        color = if (isUser) Color.White.copy(alpha = 0.7f) else Color.Gray
                    )
                }
            }
        }
        Text(
            text = if (isUser) "Вы" else "Агент",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SystemNotificationBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFFF3E5F5),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF4A148C)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4A148C),
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
