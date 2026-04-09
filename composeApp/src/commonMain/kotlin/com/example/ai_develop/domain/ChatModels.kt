@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.example.ai_develop.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class SourceType {
    USER, AI, SYSTEM, ASSISTANT
}

@Serializable
data class ChatMessage(
    val id: String = Uuid.random().toString(),
    val role: String = "user",
    val message: String = "",
    val timestamp: Long = 0L,
    val model: String? = null,
    val tokensUsed: Int? = null,
    val isError: Boolean = false,
    val parentId: String? = null,
    val branchId: String? = null,
    val source: SourceType = SourceType.SYSTEM,
    val isSystemNotification: Boolean = false,
    val taskId: String? = null,
    val taskState: TaskState? = null,
    val llmRequestSnapshot: LlmRequestSnapshot? = null
) {
    val content: String get() = message
    val tokenCount: Int get() = tokensUsed ?: 0
}

@Serializable
data class ChatBranch(
    val id: String,
    val name: String,
    val lastMessageId: String?
)

@Serializable
sealed class ChatMemoryStrategy {
    abstract val windowSize: Int

    @Serializable
    data class SlidingWindow(override val windowSize: Int) : ChatMemoryStrategy()

    @Serializable
    data class StickyFacts(
        override val windowSize: Int,
        val facts: ChatFacts = ChatFacts(),
        val updateInterval: Int = 10
    ) : ChatMemoryStrategy()

    @Serializable
    data class Branching(override val windowSize: Int) : ChatMemoryStrategy()

    @Serializable
    data class Summarization(
        override val windowSize: Int,
        val summary: String? = null,
        val summaryPrompt: String = "Summarize the conversation so far, focusing on key decisions and facts.",
        val summaryDepth: SummaryDepth = SummaryDepth.BALANCED
    ) : ChatMemoryStrategy()

    @Serializable
    data class TaskOriented(
        override val windowSize: Int,
        val currentGoal: String? = null
    ) : ChatMemoryStrategy()
}

@Serializable
enum class SummaryDepth(val description: String) {
    CONCISE("Кратко (только главное)"),
    BALANCED("Сбалансированно"),
    DETAILED("Детально (все подробности)")
}

@Serializable
data class ChatFacts(
    val facts: List<String> = emptyList(),
    val lastUpdatedMessageId: String? = null
)

@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val progress: String? = null,
    val extractedFacts: ChatFacts = ChatFacts(),
    val updateInterval: Int = 10,
    val analysisWindowSize: Int = 5,
    val isAutoUpdateEnabled: Boolean = true
)

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
    val totalTokensUsed: Int = 0,
    val userProfile: UserProfile? = null,
    val workingMemory: WorkingMemory = WorkingMemory()
)

data class AgentUpdate(
    val workingMemory: WorkingMemory? = null,
    val memoryStrategy: ChatMemoryStrategy? = null
)

fun Agent.applyUpdate(update: AgentUpdate): Agent {
    return copy(
        workingMemory = update.workingMemory ?: workingMemory,
        memoryStrategy = update.memoryStrategy ?: memoryStrategy
    )
}

/** Сбрасывает содержимое разговорной памяти, сохраняя параметры окна/автообновления. */
fun WorkingMemory.clearConversation(): WorkingMemory = copy(
    currentTask = null,
    progress = null,
    extractedFacts = ChatFacts()
)

/** Убирает данные прошлых диалогов из стратегии, сохраняя настройки (размер окна, промпты и т.д.). */
fun ChatMemoryStrategy.clearConversationData(): ChatMemoryStrategy = when (this) {
    is ChatMemoryStrategy.SlidingWindow -> this
    is ChatMemoryStrategy.Branching -> this
    is ChatMemoryStrategy.StickyFacts -> copy(facts = ChatFacts())
    is ChatMemoryStrategy.Summarization -> copy(summary = null)
    is ChatMemoryStrategy.TaskOriented -> copy(currentGoal = null)
}

@Serializable
data class AgentTemplate(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Double,
    val maxTokens: Int = 2000
)
