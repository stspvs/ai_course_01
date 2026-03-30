package com.example.ai_develop.domain

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class SourceType(val role: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
}

@Serializable
enum class SummaryDepth(val description: String) {
    LOW("Краткая (только суть)"),
    MEDIUM("Средняя (основные детали)"),
    HIGH("Глубокая (подробный контекст)")
}

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ChatMessage(
    val id: String = Uuid.random().toString(),
    val parentId: String? = null,
    val message: String,
    val source: SourceType,
    val tokenCount: Int = 0,
    val timestamp: Long = 0L,
    val isSystemNotification: Boolean = false
)

@Serializable
data class ChatFacts(
    val facts: Map<String, String> = emptyMap()
) {
    fun toSystemPrompt(): String {
        if (facts.isEmpty()) return ""
        val factsString = facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return "\n\nIMPORTANT CONTEXT AND FACTS:\n$factsString"
    }
}

@Serializable
sealed interface ChatMemoryStrategy {
    @Serializable
    data class SlidingWindow(val windowSize: Int) : ChatMemoryStrategy
    
    @Serializable
    data class StickyFacts(
        val windowSize: Int,
        val facts: ChatFacts = ChatFacts()
    ) : ChatMemoryStrategy

    @Serializable
    data class Summarization(
        val windowSize: Int,
        val summary: String? = null
    ) : ChatMemoryStrategy
}

@Serializable
data class ChatBranch(
    val id: String,
    val name: String,
    val lastMessageId: String?
)

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
    val branches: List<ChatBranch> = emptyList(),
    val currentBranchId: String? = null,
    val memoryStrategy: ChatMemoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
    val keepLastMessagesCount: Int = 10,
    val totalTokensUsed: Int = 0,
    val summary: String? = null,
    val summaryPrompt: String = "Кратко суммируй ключевые моменты этого диалога, чтобы сохранить контекст для продолжения беседы. Пиши только саму суть.",
    val summaryDepth: SummaryDepth = SummaryDepth.LOW
)

@Serializable
data class AgentTemplate(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Double,
    val maxTokens: Int = 2000,
    val keepLastMessagesCount: Int = 10
)
