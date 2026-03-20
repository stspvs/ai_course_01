package com.example.ai_develop.presentation

import java.util.UUID

internal data class DeepSeekStateModel(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false
)

internal data class ChatMessage(
    val id: String = UUID.randomUUID().toString(), // Уникальный ключ для анимаций
    val message: String,
    val source: SourceType,
)

internal enum class SourceType {
    USER,
    DEEPSEEK,
}
