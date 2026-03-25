package com.example.ai_develop.presentation.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var chatInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider()
                NavigationBar(
                    containerColor = Color.White,
                    modifier = Modifier.height(64.dp)
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                        label = { Text("Чат") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Настройки") }
                    )
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

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AI Помощник",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.isLoading) "Печатает..." else "В сети",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isLoading) Color(0xFF1976D2) else Color.Gray
                    )
                }
                
                Row {
                    IconButton(onClick = onClearChat) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Chat", tint = Color.Gray)
                    }
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.Gray)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.isStreamingEnabled) "Выключить стриминг" else "Включить стриминг") },
                                onClick = { onToggleStreaming(!state.isStreamingEnabled); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.sendFullHistory) "Отправлять только последнее" else "Отправлять историю") },
                                onClick = { onToggleHistory(!state.sendFullHistory); showMenu = false }
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.messages) { message ->
                MessageBubble(message)
            }
        }

        // Input
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("Введите сообщение...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color(0xFFF5F5F5),
                        unfocusedContainerColor = Color(0xFFF5F5F5)
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = { if (input.isNotBlank()) onSendMessage(input) },
                    containerColor = if (input.isBlank()) Color.LightGray else Color(0xFF1976D2),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
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
    val maxTemp = if (state.selectedProvider is LLMProvider.DeepSeek) 2.0f else 1.0f
    
    val temperatureDescription = when {
        state.temperature <= 0.3 -> "максимально точные, детерминированные ответы\n→ идеально для: задач, кода, логики"
        state.temperature <= 0.8 -> "(самый популярный диапазон)\n→ баланс точности и вариативности\n→ обычный чат"
        state.temperature <= 1.5 && maxTemp > 1.0f -> "больше креатива\n→ возможны ошибки"
        state.temperature > 1.5 && maxTemp > 1.0f -> "хаотичные, иногда странные ответы\n→ используется редко (тесты, генерация идей)"
        else -> "больше креатива (для данной модели 1.0 - максимум)"
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
                value = state.temperature.toFloat().coerceIn(0f, maxTemp),
                onValueChange = { onUpdateTemperature(it.toDouble()) },
                valueRange = 0f..maxTemp,
                steps = if (maxTemp > 1.0f) 19 else 9, 
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
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage) {
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
            Text(
                text = message.message,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Text(
            text = if (isUser) "Вы" else "Помощник",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
    val openRouterModels = listOf("google/gemini-2.0-flash-001", "anthropic/claude-3.5-sonnet", "openai/gpt-4o")
    val yandexModels = listOf("yandexgpt/latest", "yandexgpt-lite/latest")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it }
        ) {
            OutlinedTextField(
                value = when(currentProvider) {
                    is LLMProvider.Yandex -> "Yandex"
                    is LLMProvider.DeepSeek -> "DeepSeek"
                    is LLMProvider.OpenRouter -> "OpenRouter"
                },
                onValueChange = {},
                readOnly = true,
                label = { Text("Провайдер") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false }
            ) {
                providers.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p) },
                        onClick = {
                            val firstModel = when(p) {
                                "DeepSeek" -> deepSeekModels.first()
                                "OpenRouter" -> openRouterModels.first()
                                else -> yandexModels.first()
                            }
                            onProviderChange(when(p) {
                                "DeepSeek" -> LLMProvider.DeepSeek(firstModel)
                                "OpenRouter" -> LLMProvider.OpenRouter(firstModel)
                                else -> LLMProvider.Yandex(firstModel)
                            })
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
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
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
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            onProviderChange(when(currentProvider) {
                                is LLMProvider.DeepSeek -> LLMProvider.DeepSeek(m)
                                is LLMProvider.OpenRouter -> LLMProvider.OpenRouter(m)
                                is LLMProvider.Yandex -> LLMProvider.Yandex(m)
                            })
                            modelExpanded = false
                        }
                    )
                }
            }
        }
    }
}
