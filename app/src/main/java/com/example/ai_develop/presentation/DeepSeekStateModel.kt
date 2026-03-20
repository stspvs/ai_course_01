package com.example.ai_develop.presentation

internal data class DeepSeekStateModel(
    val messages: List<ChatMessage>,
)

internal data class ChatMessage(
    val message: String,
    val source: SourceType,
)

internal enum class SourceType {
    USER,
    DEEPSEEK,
}