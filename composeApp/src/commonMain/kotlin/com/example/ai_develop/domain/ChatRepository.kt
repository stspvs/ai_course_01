package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

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

    // Новые методы для Stateful Agent
    suspend fun saveAgentState(state: AgentState)
    suspend fun getAgentState(agentId: String): AgentState?
    
    suspend fun getProfile(agentId: String): AgentProfile?
    suspend fun saveProfile(agentId: String, profile: AgentProfile)
    
    suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant>
    suspend fun saveInvariant(invariant: Invariant)
    
    fun observeAgentState(agentId: String): Flow<AgentState?>
}

@kotlinx.serialization.Serializable
data class TaskAnalysisResult(
    val task: String,
    val progress: String,
    val facts: Map<String, String>
)
