package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

interface ChatRepository {
    fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>>

    suspend fun extractFacts(
        messages: List<ChatMessage>,
        currentFacts: ChatFacts,
        provider: LLMProvider
    ): Result<ChatFacts>

    suspend fun summarize(
        messages: List<ChatMessage>,
        previousSummary: String?,
        instruction: String,
        provider: LLMProvider
    ): Result<String>

    suspend fun analyzeTask(
        messages: List<ChatMessage>,
        instruction: String,
        provider: LLMProvider
    ): Result<TaskAnalysisResult>

    suspend fun analyzeWorkingMemory(
        messages: List<ChatMessage>,
        instruction: String,
        provider: LLMProvider
    ): Result<WorkingMemoryAnalysis>

    // Новые методы для Stateful Agent
    suspend fun saveAgentState(state: AgentState)
    suspend fun getAgentState(agentId: String): AgentState?
    suspend fun deleteAgent(agentId: String) {}
    
    suspend fun getProfile(agentId: String): UserProfile?
    suspend fun saveProfile(agentId: String, profile: UserProfile)
    
    suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant>
    suspend fun saveInvariant(invariant: Invariant)
    
    fun observeAgentState(agentId: String): Flow<AgentState?>
    
    /**
     * Возвращает список всех сохраненных состояний агентов.
     */
    fun observeAllAgents(): Flow<List<AgentState>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /**
     * Очищает чат и разговорную память агента задачи ([agentId] == [taskId]), сохраняя системные поля (промпт, температуру и т.д.).
     */
    suspend fun resetTaskConversation(taskId: String): Result<Unit> = Result.success(Unit)
}

@Serializable
data class TaskAnalysisResult(
    val stage: AgentStage = AgentStage.PLANNING,
    val plan: AgentPlan = AgentPlan()
)

@Serializable
data class WorkingMemoryAnalysis(
    val currentTask: String? = null,
    val progress: String? = null
)
