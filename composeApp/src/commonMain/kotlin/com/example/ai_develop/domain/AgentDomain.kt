package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

enum class AgentStage {
    PLANNING, EXECUTION, REVIEW, DONE
}

@Serializable
data class AgentStep(
    val id: String,
    val description: String,
    val isCompleted: Boolean = false,
    val output: String? = null
)

@Serializable
data class AgentPlan(
    val steps: List<AgentStep> = emptyList()
)

@Serializable
data class Invariant(
    val id: String,
    val rule: String,
    val stage: AgentStage,
    val isActive: Boolean = true
)

@Serializable
data class UserProfile(
    val preferences: String = "",
    val constraints: String = "",
    val memoryModelProvider: LLMProvider? = null
)

/**
 * Полное состояние агента, сохраняемое в БД.
 */
@Serializable
data class AgentState(
    val agentId: String,
    val name: String = "",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2000,
    val stopWord: String = "",
    val currentStage: AgentStage = AgentStage.PLANNING,
    val currentStepId: String? = null,
    val plan: AgentPlan = AgentPlan(),
    val memoryStrategy: ChatMemoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
    val workingMemory: WorkingMemory = WorkingMemory(),
    // Список сообщений может быть огромным, поэтому в AgentState 
    // мы можем хранить только метаданные или последние N сообщений, 
    // но для простоты на данном этапе оставим загрузку через репозиторий.
    val messages: List<ChatMessage> = emptyList()
)
