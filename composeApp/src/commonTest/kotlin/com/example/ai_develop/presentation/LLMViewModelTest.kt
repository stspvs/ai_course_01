package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LLMViewModelTest : KoinTest {

    private lateinit var viewModel: LLMViewModel
    private val testDispatcher = StandardTestDispatcher()
    
    private class MockLocalRepository : LocalChatRepository {
        var agentsFlow = flowOf(listOf<Agent>())
        var savedMetadata: Agent? = null
        var lastDeletedId: String? = null

        override fun getAgents() = agentsFlow
        override fun getAgentWithMessages(agentId: String) = flowOf(null)
        override suspend fun saveAgentMetadata(agent: Agent) { savedMetadata = agent }
        override suspend fun deleteAgent(agentId: String) { lastDeletedId = agentId }
        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun saveAgent(agent: Agent) {}
    }

    private class MockChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("summary")
    }

    private val mockRepo = MockLocalRepository()
    private val mockChatRepo = MockChatRepository()
    private val mockUseCase = ChatStreamingUseCase(mockChatRepo)
    private val mockMemoryManager = ChatMemoryManager()
    private val mockStrategyFactory = StrategyDelegateFactory(
        ExtractFactsUseCase(mockChatRepo),
        SummarizeChatUseCase(mockChatRepo)
    )
    // Use real interactor but with mocks
    private val interactor = ChatInteractor(mockUseCase, mockRepo, mockMemoryManager, mockStrategyFactory)

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = LLMViewModel(mockRepo, interactor)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() = runTest {
        val state = viewModel.state.value
        assertEquals(GENERAL_CHAT_ID, state.selectedAgentId)
    }

    @Test
    fun testSendMessageUpdatesLoading() = runTest {
        viewModel.sendMessage("Hello")
        // interactor.sendMessage updates loading to true immediately
        assertTrue(viewModel.state.value.isLoading)
    }

    @Test
    fun testCreateAgent() = runTest {
        val initialSize = viewModel.state.value.agents.size
        viewModel.createAgent()
        
        assertEquals(initialSize + 1, viewModel.state.value.agents.size)
        val newAgent = viewModel.state.value.agents.last()
        assertEquals(newAgent.id, viewModel.state.value.selectedAgentId)
    }

    @Test
    fun testDeleteAgent() = runTest {
        viewModel.createAgent()
        val agentId = viewModel.state.value.selectedAgentId!!
        
        viewModel.deleteAgent(agentId)
        testScheduler.advanceUntilIdle()
        
        assertEquals(agentId, mockRepo.lastDeletedId)
    }
}
