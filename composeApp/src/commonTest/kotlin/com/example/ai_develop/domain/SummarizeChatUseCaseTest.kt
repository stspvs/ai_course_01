package com.example.ai_develop.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummarizeChatUseCaseTest {

    private class FakeChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = TODO()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = TODO()
        
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> {
            return Result.success("Новое резюме")
        }

        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = TODO()
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = TODO()
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = TODO()
    }

    @Test
    fun `invoke should call repository summarize`() = runTest {
        val repository = FakeChatRepository()
        val useCase = SummarizeChatUseCase(repository)
        
        val result = useCase(emptyList(), "Старое", "Инструкция", LLMProvider.Yandex())
        
        assertTrue(result.isSuccess)
        assertEquals("Новое резюме", result.getOrNull())
    }
}
