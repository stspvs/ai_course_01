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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ai_develop.domain.*

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
                if (message.tokenCount > 0) {
                    Text(
                        text = "${message.tokenCount} токенов",
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
