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
 * Параметры для обновления данных агента.
 */
data class UpdateAgentParams(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val provider: LLMProvider,
    val stopWord: String,
    val maxTokens: Int,
    val memoryStrategy: ChatMemoryStrategy,
    val ragEnabled: Boolean = true,
)

/**
 * Состояние обновления памяти/агента.
 */
data class MemoryUpdateState(
    val isLoading: Boolean = false,
    val agentUpdate: Pair<String, (Agent) -> Agent>? = null
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
    /** Основная модель для чата (отдельно от [UserProfile.memoryModelProvider] для памяти). */
    val provider: LLMProvider = LLMProvider.Yandex(),
    val maxTokens: Int = 2000,
    val stopWord: String = "",
    val currentStage: AgentStage = AgentStage.PLANNING,
    val currentStepId: String? = null,
    val plan: AgentPlan = AgentPlan(),
    val memoryStrategy: ChatMemoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
    val workingMemory: WorkingMemory = WorkingMemory(),
    val messages: List<ChatMessage> = emptyList(),
    val branches: List<ChatBranch> = emptyList(),
    val currentBranchId: String? = null,
    val ragEnabled: Boolean = true,
    /** Id привязок MCP ([McpToolBindingEntity.id]), разрешённых этому агенту; пустой список — без MCP. */
    val mcpAllowedBindingIds: List<String> = emptyList(),
    /**
     * Если true — в промпт к LLM добавляется блок стадии workflow и доступны переходы [AgentStateMachine];
     * если false — обычный чат без этой семантики (стадия в БД может оставаться технической).
     */
    val workflowStagesEnabled: Boolean = true,
)
