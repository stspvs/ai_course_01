package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.presentation.ChatMessage
import com.example.ai_develop.presentation.LLMStateModel
import com.example.ai_develop.presentation.LLMViewModel
import com.example.ai_develop.presentation.SourceType
import kotlin.math.roundToInt

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
                    onClearChat = { viewModel.clearChat() }
                )
                1 -> SettingsContent(
                    state = state,
                    onUpdatePrompt = { viewModel.updateSystemPrompt(it) },
                    onUpdateMaxTokens = { viewModel.updateMaxTokens(it) },
                    onUpdateTemperature = { viewModel.updateTemperature(it) },
                    onUpdateStopWord = { viewModel.updateStopWord(it) },
                    onUpdateJsonMode = { viewModel.updateJsonMode(it) }
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
    onUpdateJsonMode: (Boolean) -> Unit
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
    ) {
        Text(
            text = "Настройки",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

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
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Например: Ты — профессиональный переводчик...") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )
    }
}

@Composable
internal fun ChatContent(
    state: LLMStateModel,
    input: String,
    onInputChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit
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
                    MessageBubble(
                        message = ChatMessage(message = "DeepSeek думает...", source = SourceType.DEEPSEEK),
                        backgroundColor = Color(0xFFFFF59D)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    val mockState = LLMStateModel(
        messages = listOf(
            ChatMessage(message = "Привет!", source = SourceType.USER),
            ChatMessage(message = "Я DeepSeek.", source = SourceType.DEEPSEEK)
        )
    )
    ChatContent(state = mockState, input = "", onInputChange = {}, onSendMessage = {}, onClearChat = {})
}
