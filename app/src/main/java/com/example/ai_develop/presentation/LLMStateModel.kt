package com.example.ai_develop.presentation

import com.example.ai_develop.domain.LLMProvider
import java.util.UUID

data class LLMStateModel(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val systemPrompt: String = "You are a helpful assistant.",
    val maxTokens: Int = 300,
    val temperature: Double = 1.0,
    val stopWord: String = "",
    val isJsonMode: Boolean = false,
    val selectedProvider: LLMProvider = LLMProvider.DeepSeek()
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
