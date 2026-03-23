package com.example.ai_develop.presentation.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.ChatMessage
import com.example.ai_develop.presentation.LLMStateModel
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.SourceType

@Composable
internal fun ChatScreen(viewModel: LLMViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var chatInput by rememberSaveable { mutableStateOf("") }

    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        bottomBar = {
            if (!isKeyboardVisible) {
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
        },
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .windowInsetsPadding(WindowInsets.ime)
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
        state.temperature <= 0.3 -> "максимально точные, детерминированные ответы\n→ идеально для: задач, кода, логики"
        state.temperature <= 0.8 -> "(самый популярный диапазон)\n→ баланс точности и вариативности\n→ обычный чат"
        state.temperature <= 1.5 -> "больше креатива\n→ возможны ошибки"
        else -> "хаотичные, иногда странные ответы\n→ используется редко (тесты, генерация идей)"
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

        // Provider and Model Selection
        LLMSelector(
            currentProvider = state.selectedProvider,
            onProviderChange = onUpdateProvider
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.maxTokens.toString(),
                onValueChange = {
                    it.toIntOrNull()?.let { tokens -> onUpdateMaxTokens(tokens) }
                },
                label = { Text("Макс. длина", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )

            OutlinedTextField(
                value = state.stopWord,
                onValueChange = onUpdateStopWord,
                label = { Text("Стоп-слово", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )
        }

        // Temperature Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Температура: ${String.format("%.1f", state.temperature)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = state.temperature.toFloat(),
                onValueChange = { onUpdateTemperature(it.toDouble()) },
                valueRange = 0f..2f,
                steps = 19, // Чтобы был шаг 0.1
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = temperatureDescription,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = Color.DarkGray,
                lineHeight = 16.sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.isJsonMode,
                onCheckedChange = onUpdateJsonMode
            )
            Text(
                text = "JSON Mode (требуется инструкция в промпте)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Text(
            text = "Системный промпт",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = state.systemPrompt,
            onValueChange = onUpdatePrompt,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            placeholder = { Text("Например: Ты — профессиональный переводчик...") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
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
        Text(
            "Выбор LLM",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Provider Dropdown
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
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
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

        // Model Dropdown
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
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
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

    LaunchedEffect(state.messages.size, state.isLoading) {
        if (state.messages.isNotEmpty() || state.isLoading) {
            val lastIndex = if (state.isLoading) state.messages.size else state.messages.size - 1
            if (lastIndex >= 0) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

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
            items(
                items = state.messages,
                key = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(600),
                        placementSpec = tween(600),
                        fadeOutSpec = tween(600)
                    )
                )
            }

            item(key = "loading_indicator") {
                AnimatedVisibility(
                    visible = state.isLoading,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(300)) + shrinkVertically(tween(300))
                ) {
                    val loadingText = when (state.selectedProvider) {
                        is LLMProvider.DeepSeek -> "DeepSeek думает..."
                        is LLMProvider.Yandex -> "Yandex GPT думает..."
                        is LLMProvider.OpenRouter -> "OpenRouter думает..."
                    }
                    MessageBubble(
                        message = ChatMessage(message = loadingText, source = SourceType.ASSISTANT),
                        backgroundColor = Color(0xFFFFF59D)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Control Panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // Streaming toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.isStreamingEnabled) "Поток: ВКЛ" else "Поток: ВЫКЛ",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )
                IconButton(
                    onClick = { onToggleStreaming(!state.isStreamingEnabled) },
                    modifier = Modifier.height(32.dp).width(32.dp)
                ) {
                    Text(text = if (state.isStreamingEnabled) "🌊" else "📄", fontSize = 16.sp)
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // History toggle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.sendFullHistory) "История: ВСЯ" else "История: ПОСЛ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )
                IconButton(
                    onClick = { onToggleHistory(!state.sendFullHistory) },
                    modifier = Modifier.height(32.dp).width(32.dp)
                ) {
                    Text(text = if (state.sendFullHistory) "📚" else "🎯", fontSize = 16.sp)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val inputBgColor = Color(0xFFF1F3F4)
            val buttonBgColor = Color(0xFFDADCE0)

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = inputBgColor,
                    unfocusedContainerColor = inputBgColor,
                    focusedBorderColor = Color.DarkGray,
                    unfocusedBorderColor = Color.Gray,
                ),
                trailingIcon = {
                    IconButton(onClick = onClearChat) {
                        Text("➕", fontSize = 20.sp)
                    }
                }
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSendMessage(input)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonBgColor,
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("🚀 Ок", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null
) {
    val isUser = message.source == SourceType.USER
    val bubbleColor = backgroundColor ?: if (isUser) Color.White else Color(0xFFF5F5DC)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            tonalElevation = 2.dp,
            shadowElevation = 1.dp
        ) {
            SelectionContainer {
                Text(
                    text = message.message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.Black,
                    style = if (backgroundColor != null) {
                        MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    val mockState = LLMStateModel(
        messages = listOf(
            ChatMessage(message = "Привет!", source = SourceType.USER),
            ChatMessage(message = "Я DeepSeek.", source = SourceType.ASSISTANT)
        )
    )
    ChatContent(
        state = mockState,
        input = "",
        onInputChange = {},
        onSendMessage = {},
        onClearChat = {},
        onToggleStreaming = {},
        onToggleHistory = {})
}
