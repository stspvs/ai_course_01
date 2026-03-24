package com.example.ai_develop.presentation

import com.example.ai_develop.domain.LLMProvider
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class LLMStateModel(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isStreamingEnabled: Boolean = true,
    val sendFullHistory: Boolean = true,
    val systemPrompt: String = "You are a helpful assistant.",
    val maxTokens: Int = 3000,
    val temperature: Double = 1.0,
    val stopWord: String = "",
    val isJsonMode: Boolean = false,
    val selectedProvider: LLMProvider = LLMProvider.Yandex()
)

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ChatMessage(
    val id: String = Uuid.random().toString(),
    val message: String,
    val source: SourceType,
)

@Serializable
enum class SourceType(val role: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
}
