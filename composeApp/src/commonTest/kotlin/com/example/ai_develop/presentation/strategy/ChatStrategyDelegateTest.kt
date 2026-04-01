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

    @Test
    fun testDefaultStrategyDelegate() = runTest {
        val localRepo = FakeLocalChatRepository()
        val delegate = DefaultStrategyDelegate()
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
            onAgentUpdated = {}
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
