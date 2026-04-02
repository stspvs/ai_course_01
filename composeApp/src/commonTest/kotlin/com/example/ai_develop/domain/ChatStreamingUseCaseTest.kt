package com.example.ai_develop.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

class ChatStreamingUseCaseTest {

    private val repository = object : ChatRepository {
        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ) = flowOf(Result.success("chunk1"), Result.success("chunk2"))

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider
        ): Result<ChatFacts> = Result.success(ChatFacts())

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ): Result<String> = Result.success("summary")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): AgentProfile? = null
        override suspend fun saveProfile(agentId: String, profile: AgentProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    private val useCase = ChatStreamingUseCase(repository)

    @Test
    fun `invoke should return flow from repository`() = runTest {
        val results = useCase(
            messages = emptyList(),
            systemPrompt = "",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = LLMProvider.Yandex()
        ).toList()

        assertEquals(2, results.size)
        assertEquals("chunk1", results[0].getOrNull())
        assertEquals("chunk2", results[1].getOrNull())
    }
}
