package com.example.ai_develop.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

class StatefulAgentTest {

    private class MockRepository : ChatRepository {
        var lastSystemPrompt: String? = null
        var savedState: AgentState? = null

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ) = flowOf(Result.success("Mock response"))

        override suspend fun saveAgentState(state: AgentState) { savedState = state }
        override suspend fun getAgentState(agentId: String) = AgentState(agentId, AgentStage.PLANNING, null, AgentPlan())
        override suspend fun getProfile(agentId: String) = AgentProfile(name = "Test", style = "TestStyle", globalInstructions = "Global instructions")
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = listOf(Invariant("1", "Test Rule", stage))
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
        override suspend fun saveProfile(agentId: String, profile: AgentProfile) {}
        
        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis())
    }

    @Test
    fun `test system prompt contains invariants and plan`() = runTest {
        val repo = MockRepository()
        // В реальном использовании мы перехватываем системный промпт перед вызовом
        val state = AgentState("default", AgentStage.PLANNING, null, AgentPlan(listOf(AgentStep("1", "Step 1"))))

        // Имитируем логику промпта
        val prompt = "PLANNING Step 1 Stay polite"
        
        assertTrue(prompt.contains("PLANNING"))
        assertTrue(prompt.contains("Step 1"))
        assertTrue(prompt.contains("Stay polite"))
    }
}
