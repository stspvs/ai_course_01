package com.example.ai_develop.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
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

    val providers = listOf("DeepSeek", "OpenRouter", "Ollama")
    val deepSeekModels = listOf("deepseek-chat", "deepseek-coder")
    val openRouterModels = listOf("google/gemini-2.0-flash-001", "anthropic/claude-3.5-sonnet", "openai/gpt-4o", "deepseek/deepseek-r1:free")
    val yandexModels = listOf("yandexgpt-5.1/latest", "yandexgpt-lite/latest", "yandexgpt/latest")
    val ollamaModels = OllamaUiModelNames

    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
            OutlinedTextField(
                value = when (currentProvider) {
                    is LLMProvider.Yandex -> "Yandex"
                    is LLMProvider.DeepSeek -> "DeepSeek"
                    is LLMProvider.OpenRouter -> "OpenRouter"
                    is LLMProvider.Ollama -> "Ollama"
                },
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
                        val firstModel = when (p) {
                            "DeepSeek" -> deepSeekModels.first()
                            "OpenRouter" -> openRouterModels.first()
                            "Ollama" -> ollamaModels.first()
                            else -> yandexModels.first()
                        }
                        onProviderChange(
                            when (p) {
                                "DeepSeek" -> LLMProvider.DeepSeek(firstModel)
                                "OpenRouter" -> LLMProvider.OpenRouter(firstModel)
                                "Ollama" -> LLMProvider.Ollama(firstModel)
                                else -> LLMProvider.Yandex(firstModel)
                            }
                        )
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
                val models = when (currentProvider) {
                    is LLMProvider.DeepSeek -> deepSeekModels
                    is LLMProvider.OpenRouter -> openRouterModels
                    is LLMProvider.Yandex -> yandexModels
                    is LLMProvider.Ollama -> ollamaModels
                }
                models.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        onProviderChange(
                            when (currentProvider) {
                                is LLMProvider.DeepSeek -> LLMProvider.DeepSeek(m)
                                is LLMProvider.OpenRouter -> LLMProvider.OpenRouter(m)
                                is LLMProvider.Yandex -> LLMProvider.Yandex(m)
                                is LLMProvider.Ollama -> LLMProvider.Ollama(m)
                            }
                        )
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
    val wideTempRange = provider is LLMProvider.DeepSeek || provider is LLMProvider.Ollama
    val maxTemp = if (wideTempRange) 2.0 else 1.0
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
            steps = if (wideTempRange) 19 else 9,
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
internal fun RagChunkMaterialDialog(
    chunkId: String,
    attribution: RagAttribution,
    onDismiss: () -> Unit,
) {
    val src = attribution.sources.find { it.chunkId == chunkId }
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(src?.documentTitle ?: "Фрагмент") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (src == null) {
                    Text(
                        text = "Фрагмент с chunk_id не найден в атрибуции запроса.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = buildString {
                            append(src.sourceFileName)
                            append(" · chunk_index=${src.chunkIndex}")
                            append(" · chunk_id=${src.chunkId}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                    SelectionContainer {
                        Text(
                            text = src.chunkText.ifBlank { "(пустой текст)" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun CollapsibleRagMetaSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$title ($count)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun RagStructuredAssistantContent(
    payload: RagStructuredChatPayload,
    onChunkClick: (String) -> Unit,
    onOpenRagPipeline: (() -> Unit)?,
) {
    var sourcesExpanded by remember { mutableStateOf(false) }
    var quotesExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Text(
                text = payload.answer,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        payload.validationNote?.let { note ->
            Text(
                text = "— $note —",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontStyle = FontStyle.Italic,
            )
        }
        CollapsibleRagMetaSection(
            title = "Источники",
            count = payload.sources.size,
            expanded = sourcesExpanded,
            onToggle = { sourcesExpanded = !sourcesExpanded },
        ) {
            if (payload.sources.isEmpty()) {
                Text(
                    text = "Фрагменты из базы не привязаны к ответу (список пуст). Контекст мог не подмешиваться или модель не вернула ссылки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                payload.sources.forEachIndexed { i, s ->
                    Text(
                        text = "${i + 1}. ${s.source} · chunk ${s.chunkIndex} (${s.chunkId})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onChunkClick(s.chunkId) },
                    )
                }
            }
        }
        if (payload.quotes.isNotEmpty()) {
            CollapsibleRagMetaSection(
                title = "Цитаты",
                count = payload.quotes.size,
                expanded = quotesExpanded,
                onToggle = { quotesExpanded = !quotesExpanded },
            ) {
                payload.quotes.forEachIndexed { i, q ->
                    Text(
                        text = "${i + 1}. [${q.chunkId}] «${q.text}»",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clickable { onChunkClick(q.chunkId) }
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
        onOpenRagPipeline?.let { open ->
            TextButton(onClick = open) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Пайплайн поиска RAG")
            }
        }
    }
}

/**
 * Если в снимке есть [LlmRequestSnapshot.ragStructuredContent], но поля JSON пустые,
 * [RagStructuredAssistantContent] нечего показать — нужен fallback на [ChatMessage.message].
 */
internal fun RagStructuredChatPayload?.shouldRenderStructuredBubble(): Boolean {
    val p = this ?: return false
    return !p.answer.isBlank() ||
        p.sources.isNotEmpty() ||
        p.quotes.isNotEmpty() ||
        !p.validationNote.isNullOrBlank()
}

@Composable
internal fun MessageBubble(
    message: ChatMessage,
    onRagChunkClick: ((String) -> Unit)? = null,
    onOpenRagPipelineDialog: (() -> Unit)? = null,
) {
    if (message.isSystemNotification) {
        SystemNotificationBubble(message.message)
        return
    }

    val isUser = message.source == SourceType.USER
    val snap = message.llmRequestSnapshot
    val structured = snap?.ragStructuredContent
    val ragAttr = snap?.ragAttribution

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
                val segments = remember(message.message) { parseMessageBodySegments(message.message) }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        structured != null &&
                            structured.shouldRenderStructuredBubble() &&
                            ragAttr != null &&
                            onRagChunkClick != null &&
                            !isUser -> {
                            RagStructuredAssistantContent(
                                payload = structured,
                                onChunkClick = onRagChunkClick,
                                onOpenRagPipeline = onOpenRagPipelineDialog,
                            )
                        }
                        else -> {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when {
                                        segments.isEmpty() && message.message.isNotBlank() -> {
                                            Text(
                                                text = message.message,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        segments.isEmpty() && !isUser && message.message.isBlank() -> {
                                            Text(
                                                text = "(пустое сообщение)",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        else -> {
                                            segments.forEach { seg ->
                                                when (seg) {
                                                    is MessageBodySegment.Text -> Text(
                                                        text = seg.content,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                    is MessageBodySegment.Image -> ChatImageFromUrl(
                                                        url = seg.url,
                                                        modifier = Modifier.padding(vertical = 2.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
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
