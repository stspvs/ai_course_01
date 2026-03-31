package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.koin.test.KoinTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LLMViewModelTest : KoinTest {

    private lateinit var viewModel: LLMViewModel
    private val testDispatcher = StandardTestDispatcher()
    
    private class MockRepository : DatabaseChatRepository(null) {
        var agentsFlow = flowOf(listOf<Agent>())
        var savedMetadata: Agent? = null
        var lastDeletedId: String? = null

        override fun getAgents() = agentsFlow
        override fun getAgentWithMessages(agentId: String) = emptyFlow<Agent?>()
        override suspend fun saveAgentMetadata(agent: Agent) { savedMetadata = agent }
        override suspend fun deleteAgent(agentId: String) { lastDeletedId = agentId }
        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun saveAgent(agent: Agent) {}
    }

    private class MockChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
    }

    private class MockUseCase(repo: ChatRepository) : ChatStreamingUseCase(repo) {
        override fun invoke(
            messages: List<ChatMessage>, 
            systemPrompt: String, 
            maxTokens: Int, 
            temperature: Double, 
            stopWord: String, 
            isJsonMode: Boolean, 
            provider: LLMProvider
        ) = emptyFlow<Result<String>>()
    }

    private val mockRepo = MockRepository()
    private val mockChatRepo = MockChatRepository()
    private val mockUseCase = MockUseCase(mockChatRepo)

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = LLMViewModel(mockUseCase, mockRepo)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest {
        val state = viewModel.state.value
        assertEquals(GENERAL_CHAT_ID, state.selectedAgentId)
        assertEquals(1, state.agents.size)
        assertEquals("Общий чат", state.agents.first().name)
    }

    @Test
    fun testSelectAgent() = runTest {
        viewModel.selectAgent("agent_123")
        assertEquals("agent_123", viewModel.state.value.selectedAgentId)
    }

    @Test
    fun testUpdateStreamingEnabled() = runTest {
        viewModel.updateStreamingEnabled(false)
        assertEquals(false, viewModel.state.value.isStreamingEnabled)
    }

    @Test
    fun testCreateAgent() = runTest {
        val initialSize = viewModel.state.value.agents.size
        viewModel.createAgent()
        
        assertEquals(initialSize + 1, viewModel.state.value.agents.size)
        val newAgent = viewModel.state.value.agents.last()
        assertEquals("Новый агент", newAgent.name)
        assertEquals(newAgent.id, viewModel.state.value.selectedAgentId)
    }

    @Test
    fun testDeleteAgent() = runTest {
        viewModel.createAgent()
        val agentId = viewModel.state.value.selectedAgentId!!
        
        viewModel.deleteAgent(agentId)
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertEquals(1, viewModel.state.value.agents.size)
        assertEquals(agentId, mockRepo.lastDeletedId)
    }

    @Test
    fun testUpdateAgent() = runTest {
        val agentId = GENERAL_CHAT_ID
        viewModel.updateAgent(
            id = agentId,
            name = "Super Bot",
            systemPrompt = "Be super",
            temperature = 0.1,
            provider = LLMProvider.DeepSeek("coder"),
            stopWord = "END",
            maxTokens = 100,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(5)
        )
        
        val updatedAgent = viewModel.state.value.agents.find { it.id == agentId }!!
        assertEquals("Super Bot", updatedAgent.name)
        assertEquals("Be super", updatedAgent.systemPrompt)
        assertEquals(0.1, updatedAgent.temperature)
        assertEquals("coder", updatedAgent.provider.model)
        assertEquals("END", updatedAgent.stopWord)
        assertEquals(100, updatedAgent.maxTokens)
    }
}
