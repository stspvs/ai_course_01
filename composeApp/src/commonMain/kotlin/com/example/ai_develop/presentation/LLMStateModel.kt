package com.example.ai_develop.presentation

import com.example.ai_develop.domain.LLMProvider
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val GENERAL_CHAT_ID = "general_chat_id"

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Agent(
    val id: String = Uuid.random().toString(),
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val provider: LLMProvider,
    val stopWord: String,
    val maxTokens: Int,
    val messages: List<ChatMessage> = emptyList(),
    val totalTokensUsed: Int = 0
)

data class AgentTemplate(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2000
)

@Serializable
data class LLMStateModel(
    val agents: List<Agent> = listOf(
        Agent(
            id = GENERAL_CHAT_ID,
            name = "Общий чат",
            systemPrompt = "You are a helpful assistant.",
            temperature = 1.0,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 3000
        )
    ),
    val selectedAgentId: String? = GENERAL_CHAT_ID,
    val isLoading: Boolean = false,
    val isStreamingEnabled: Boolean = true,
    val sendFullHistory: Boolean = true,
    val isJsonMode: Boolean = false,
) {
    val selectedAgent: Agent?
        get() = agents.find { it.id == selectedAgentId }

    val currentMessages: List<ChatMessage>
        get() = selectedAgent?.messages ?: emptyList()
    
    val currentTokensUsed: Int
        get() = selectedAgent?.totalTokensUsed ?: 0
}

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ChatMessage(
    val id: String = Uuid.random().toString(),
    val message: String,
    val source: SourceType,
    val tokenCount: Int = 0
)

@Serializable
enum class SourceType(val role: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
}
