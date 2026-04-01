package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StickyFactsStrategyDelegateTest {

    private class MockLocalRepository : LocalChatRepository {
        var savedMetadata: Agent? = null
        override fun getAgents() = emptyFlow<List<Agent>>()
        override fun getAgentWithMessages(agentId: String) = flowOf(null)
        override suspend fun saveAgent(agent: Agent) {}
        override suspend fun saveAgentMetadata(agent: Agent) { savedMetadata = agent }
        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun deleteAgent(agentId: String) {}
    }

    private class MockExtractUseCase : ExtractFactsUseCase(object : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = emptyFlow<Result<String>>()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts(mapOf("new" to "fact")))
    })

    @Test
    fun `onMessageReceived should trigger facts extraction on interval`() = runTest {
        val repo = MockLocalRepository()
        val extractUseCase = MockExtractUseCase()
        val delegate = StickyFactsStrategyDelegate(extractUseCase)
        
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, updateInterval = 2, facts = ChatFacts(emptyMap()))
        val agent = Agent(
            id = "a1", name = "A1", systemPrompt = "", temperature = 1.0, provider = LLMProvider.Yandex(), stopWord = "", maxTokens = 100,
            messages = listOf(
                ChatMessage(id = "1", message = "U1", source = SourceType.USER),
                ChatMessage(id = "2", message = "A1", source = SourceType.ASSISTANT),
                ChatMessage(id = "3", message = "U2", source = SourceType.USER),
                ChatMessage(id = "4", message = "A2", source = SourceType.ASSISTANT) // 2nd assistant message, interval hit
            ),
            memoryStrategy = strategy
        )

        var updatedAgent: Agent? = null
        delegate.onMessageReceived(
            scope = this,
            agent = agent,
            repository = repo,
            onAgentUpdated = { updatedAgent = it }
        )

        advanceUntilIdle()

        // Проверяем, что агент был обновлен новыми фактами
        val updatedStrategy = updatedAgent?.memoryStrategy as? ChatMemoryStrategy.StickyFacts
        assertEquals("fact", updatedStrategy?.facts?.facts?.get("new"))
        
        // Проверяем, что изменения сохранены в БД
        assertEquals("fact", (repo.savedMetadata?.memoryStrategy as? ChatMemoryStrategy.StickyFacts)?.facts?.facts?.get("new"))
    }

    @Test
    fun `onMessageReceived should not trigger extraction if interval not reached`() = runTest {
        val repo = MockLocalRepository()
        val extractUseCase = MockExtractUseCase()
        val delegate = StickyFactsStrategyDelegate(extractUseCase)
        
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, updateInterval = 5)
        val agent = Agent(
            id = "a1", name = "A1", systemPrompt = "", temperature = 1.0, provider = LLMProvider.Yandex(), stopWord = "", maxTokens = 100,
            messages = listOf(ChatMessage(id = "1", message = "A1", source = SourceType.ASSISTANT)),
            memoryStrategy = strategy
        )

        var updatedAgent: Agent? = null
        delegate.onMessageReceived(this, agent, repo) { updatedAgent = it }
        advanceUntilIdle()

        assertTrue(updatedAgent == null, "Agent should not be updated yet")
    }
}
