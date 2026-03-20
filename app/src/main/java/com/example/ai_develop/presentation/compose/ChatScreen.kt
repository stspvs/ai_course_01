package com.example.ai_develop.presentation.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_develop.presentation.ChatMessage
import com.example.ai_develop.presentation.DeepSeekStateModel
import com.example.ai_develop.presentation.DeepSeekViewModel
import com.example.ai_develop.presentation.SourceType

@Composable
internal fun ChatScreen(viewModel: DeepSeekViewModel) {
    val state by viewModel.state.collectAsState()
    ChatContent(
        state = state,
        onSendMessage = { viewModel.sendMessage(it) }
    )
}

@Composable
internal fun ChatContent(
    state: DeepSeekStateModel,
    onSendMessage: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Автоматическая прокрутка вниз
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
            .systemBarsPadding()
            .imePadding()
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

            // Используем item с ключом и AnimatedVisibility для надежного скрытия
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
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Введите сообщение...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = inputBgColor,
                    unfocusedContainerColor = inputBgColor,
                    focusedBorderColor = Color.DarkGray,
                    unfocusedBorderColor = Color.Gray,
                )
            )
            
            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        onSendMessage(input)
                        input = ""
                    }
                },
                enabled = !state.isLoading,
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
    val mockState = DeepSeekStateModel(
        messages = listOf(
            ChatMessage(message = "Привет!", source = SourceType.USER),
            ChatMessage(message = "Я DeepSeek.", source = SourceType.DEEPSEEK)
        )
    )
    ChatContent(state = mockState, onSendMessage = {})
}
