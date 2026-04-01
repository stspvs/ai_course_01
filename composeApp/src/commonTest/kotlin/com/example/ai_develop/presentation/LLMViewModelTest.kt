package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LLMViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class MockLocalRepository : LocalChatRepository {
        private val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
        override fun getAgents(): Flow<List<Agent>> = agentsFlow
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(null)
        override suspend fun saveAgent(agent: Agent) {
            agentsFlow.value = listOf(agent)
        }
        override suspend fun saveAgentMetadata(agent: Agent) {
            agentsFlow.value = listOf(agent)
        }
        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun deleteAgent(agentId: String) {}
    }

    private class MockChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = flowOf()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("summary")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult("", "", emptyMap()))
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): AgentProfile? = null
        override suspend fun saveProfile(agentId: String, profile: AgentProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should have general chat`() = runTest {
        val repo = MockLocalRepository()
        val chatRepo = MockChatRepository()
        val interactor = ChatInteractor(
            ChatStreamingUseCase(chatRepo),
            repo,
            ChatMemoryManager(),
            StrategyDelegateFactory(ExtractFactsUseCase(chatRepo), SummarizeChatUseCase(chatRepo), chatRepo)
        )
        val viewModel = LLMViewModel(repo, interactor)
        
        assertEquals(GENERAL_CHAT_ID, viewModel.state.value.selectedAgentId)
    }
}
