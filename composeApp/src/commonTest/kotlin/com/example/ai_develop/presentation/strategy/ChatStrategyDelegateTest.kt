package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatStrategyDelegateTest {

    private class MockChatRepo : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = flowOf(Result.success(""))
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun getProfile(agentId: String) = null
        override suspend fun saveProfile(agentId: String, profile: AgentProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = flowOf(null)
    }

    @Test
    fun testDefaultStrategyDelegate() = runTest {
        val localRepo = FakeLocalChatRepository()
        val chatRepo = MockChatRepo()
        val updateWorkingMemoryUseCase = UpdateWorkingMemoryUseCase(chatRepo)
        val extractFactsUseCase = ExtractFactsUseCase(chatRepo)
        
        val delegate = DefaultStrategyDelegate(updateWorkingMemoryUseCase, extractFactsUseCase)
        val agent = Agent(
            id = "1",
            name = "Test",
            systemPrompt = "system",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 1000,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10)
        )

        delegate.onMessageReceived(
            scope = this,
            agent = agent,
            repository = localRepo,
            onAgentUpdated = { updated ->
                runTest { localRepo.saveAgent(updated) }
            }
        )
        
        advanceUntilIdle()

        // В DefaultStrategyDelegate.onMessageReceived обновление происходит только каждые 5 сообщений
        // Для теста просто проверим инициализацию или вызовем forceUpdate
        delegate.forceUpdate(
            scope = this,
            agent = agent,
            repository = localRepo,
            onAgentUpdated = { updated ->
                runTest { localRepo.saveAgent(updated) }
            }
        )
        advanceUntilIdle()

        assertEquals(1, localRepo.savedAgents.size)
        assertEquals("Test", localRepo.savedAgents[0].name)
    }

    private class FakeLocalChatRepository : LocalChatRepository {
        val savedAgents = mutableListOf<Agent>()
        override fun getAgents(): Flow<List<Agent>> = flowOf(emptyList())
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(null)
        override suspend fun saveAgent(agent: Agent) {
            savedAgents.add(agent)
        }

        override suspend fun saveAgentMetadata(agent: Agent) {
            savedAgents.add(agent)
        }

        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun deleteAgent(agentId: String) {}
    }
}
