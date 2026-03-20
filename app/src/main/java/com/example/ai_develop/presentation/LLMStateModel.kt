package com.example.ai_develop.presentation

import java.util.UUID

data class LLMStateModel(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val systemPrompt: String = "You are a helpful assistant.",
    val maxTokens: Int = 300,
    val stopWord: String = "",
    val isJsonMode: Boolean = false
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val source: SourceType,
)

enum class SourceType(val role: String) {
    USER("user"),
    DEEPSEEK("assistant"),
    SYSTEM("system"),
}
