package com.example.ai_develop.presentation.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.ChatMessage
import com.example.ai_develop.presentation.LLMStateModel
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.SourceType
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun App(viewModel: LLMViewModel) {
    MaterialTheme {
        ChatScreen(viewModel)
    }
}

@Composable
internal fun ChatScreen(viewModel: LLMViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var chatInput by rememberSaveable { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            Surface(
                color = Color(0xFFF3E5F5),
                border = BorderStroke(1.dp, Color(0xFF4A148C)),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Text("💬") },
                        label = { Text("Чат") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Text("⚙️") },
                        label = { Text("Настройки") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> ChatContent(
                    state = state,
                    input = chatInput,
                    onInputChange = { chatInput = it },
                    onSendMessage = {
                        viewModel.sendMessage(it)
                        chatInput = ""
                    },
                    onClearChat = { viewModel.clearChat() },
                    onToggleStreaming = { viewModel.updateStreamingEnabled(it) },
                    onToggleHistory = { viewModel.updateSendFullHistory(it) }
                )

                1 -> SettingsContent(
                    state = state,
                    onUpdatePrompt = { viewModel.updateSystemPrompt(it) },
                    onUpdateMaxTokens = { viewModel.updateMaxTokens(it) },
                    onUpdateTemperature = { viewModel.updateTemperature(it) },
                    onUpdateStopWord = { viewModel.updateStopWord(it) },
                    onUpdateJsonMode = { viewModel.updateJsonMode(it) },
                    onUpdateProvider = { viewModel.updateProvider(it) }
                )
            }
        }
    }
}

@Composable
internal fun SettingsContent(
    state: LLMStateModel,
    onUpdatePrompt: (String) -> Unit,
    onUpdateMaxTokens: (Int) -> Unit,
    onUpdateTemperature: (Double) -> Unit,
    onUpdateStopWord: (String) -> Unit,
    onUpdateJsonMode: (Boolean) -> Unit,
    onUpdateProvider: (LLMProvider) -> Unit
) {
    val temperatureDescription = when {
        state.temperature <= 0.3 -> "максимально точные ответы"
        state.temperature <= 0.8 -> "баланс точности и вариативности"
        else -> "креативные ответы"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE3F2FD))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LLMSelector(
            currentProvider = state.selectedProvider,
            onProviderChange = onUpdateProvider
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.maxTokens.toString(),
                onValueChange = { it.toIntOrNull()?.let { tokens -> onUpdateMaxTokens(tokens) } },
                label = { Text("Макс. длина") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = state.stopWord,
                onValueChange = onUpdateStopWord,
                label = { Text("Стоп-слово") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "Температура: ${state.temperature}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Slider(
                value = state.temperature.toFloat(),
                onValueChange = { onUpdateTemperature(it.toDouble()) },
                valueRange = 0f..2f,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = temperatureDescription,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = state.isJsonMode, onCheckedChange = onUpdateJsonMode)
            Text(text = "JSON Mode", modifier = Modifier.padding(start = 4.dp))
        }

        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onUpdatePrompt,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            label = { Text("Системный промпт") },
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LLMSelector(
    currentProvider: LLMProvider,
    onProviderChange: (LLMProvider) -> Unit
) {
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val providers = listOf("Yandex", "DeepSeek", "OpenRouter")
    val deepSeekModels = listOf("deepseek-chat", "deepseek-coder")
    val openRouterModels = listOf(
        "google/gemini-2.0-flash-001",
        "anthropic/claude-3.5-sonnet",
        "openai/gpt-4o",
        "deepseek/deepseek-r1:free",
        "meta-llama/llama-3.3-70b-instruct"
    )
    val yandexModels = listOf(
        "yandexgpt-5.1/latest",
        "yandexgpt-lite/latest",
        "yandexgpt/latest",
        "qwen3-0.6b/latest",
        "qwq-32b/latest",
        "qwen3-235b-a22b-fp8/latest",
        "gemma-3-27b-it/latest"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it }
        ) {
            val providerName = when(currentProvider) {
                is LLMProvider.DeepSeek -> "DeepSeek"
                is LLMProvider.Yandex -> "Yandex"
                is LLMProvider.OpenRouter -> "OpenRouter"
            }
            OutlinedTextField(
                value = providerName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false }
            ) {
                providers.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider) },
                        onClick = {
                            when (provider) {
                                "DeepSeek" -> onProviderChange(LLMProvider.DeepSeek(deepSeekModels.first()))
                                "OpenRouter" -> onProviderChange(LLMProvider.OpenRouter(openRouterModels.first()))
                                else -> onProviderChange(LLMProvider.Yandex(yandexModels.first()))
                            }
                            providerExpanded = false
                        }
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = modelExpanded,
            onExpandedChange = { modelExpanded = it }
        ) {
            OutlinedTextField(
                value = currentProvider.model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Модель") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = modelExpanded,
                onDismissRequest = { modelExpanded = false }
            ) {
                val models = when(currentProvider) {
                    is LLMProvider.DeepSeek -> deepSeekModels
                    is LLMProvider.OpenRouter -> openRouterModels
                    is LLMProvider.Yandex -> yandexModels
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            when (currentProvider) {
                                is LLMProvider.DeepSeek -> onProviderChange(LLMProvider.DeepSeek(model))
                                is LLMProvider.OpenRouter -> onProviderChange(LLMProvider.OpenRouter(model))
                                is LLMProvider.Yandex -> onProviderChange(LLMProvider.Yandex(model))
                            }
                            modelExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatContent(
    state: LLMStateModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleStreaming: (Boolean) -> Unit,
    onToggleHistory: (Boolean) -> Unit
) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE3F2FD))
            .padding(12.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.messages) { message ->
                MessageBubble(message = message)
            }

            if (state.isLoading) {
                item {
                    Text("Думает...", modifier = Modifier.padding(8.dp), fontStyle = FontStyle.Italic)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { onToggleStreaming(!state.isStreamingEnabled) }) {
                Text(if (state.isStreamingEnabled) "🌊" else "📄")
            }
            IconButton(onClick = { onToggleHistory(!state.sendFullHistory) }) {
                Text(if (state.sendFullHistory) "📚" else "🎯")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    IconButton(onClick = onClearChat) { Text("➕") }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { if (input.isNotBlank()) onSendMessage(input) },
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("🚀")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.source == SourceType.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = if (isUser) Color.White else Color(0xFFF5F5DC),
            shape = RoundedCornerShape(12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.message,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
