package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

open class ChatStreamingUseCase(
    private val repository: ChatRepository
) {
    open operator fun invoke(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>> {
        return repository.chatStreaming(
            messages, systemPrompt, maxTokens, temperature, stopWord, isJsonMode, provider
        )
    }

    open suspend fun invokeWithState(
        agentId: String,
        userMessage: String,
        provider: LLMProvider
    ): Flow<Result<String>> {
        val state = repository.getAgentState(agentId) ?: AgentState(agentId, AgentStage.PLANNING, null, AgentPlan())
        val profile = repository.getProfile(agentId) ?: UserProfile()
        val invariants = repository.getInvariants(agentId, state.currentStage)
        
        val systemPrompt = buildPrompt(state, profile, invariants)
        val messages = listOf(ChatMessage(message = userMessage, role = "user", source = SourceType.USER))
        
        var fullResponse = ""
        
        return repository.chatStreaming(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = 2000,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = provider
        ).onEach { result ->
            result.onSuccess { chunk -> fullResponse += chunk }
        }.onCompletion {
            // После завершения стриминга — проверяем инварианты
            validateResponse(agentId, fullResponse, invariants)
        }
    }

    private fun buildPrompt(state: AgentState, profile: UserProfile, invariants: List<Invariant>): String {
        return """
            USER PREFERENCES: ${profile.preferences}
            USER CONSTRAINTS: ${profile.constraints}
            CURRENT STAGE: ${state.currentStage}
            CURRENT PLAN: ${Json.encodeToString(AgentPlan.serializer(), state.plan)}
            
            ACTIVE INVARIANTS FOR THIS STAGE:
            ${invariants.joinToString("\n") { "- ${it.rule}" }}
            
            INSTRUCTIONS:
            If you are in PLANNING, output a structured plan.
            If you are in EXECUTION, perform the next step of the plan.
            Strictly follow the active invariants.
        """.trimIndent()
    }

    private suspend fun validateResponse(agentId: String, response: String, invariants: List<Invariant>) {
        // Здесь будет логика вызова extractFacts или LLM-валидации
        // Если обнаружено нарушение:
        // repository.saveAgentState(state.copy(currentStage = AgentStage.PLANNING))
    }
}
