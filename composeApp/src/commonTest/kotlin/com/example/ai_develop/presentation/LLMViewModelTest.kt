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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LLMViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    
    private class FakeRepository : LocalChatRepository {
        val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
        val savedAgents = mutableMapOf<String, Agent>()

        override fun getAgents(): Flow<List<Agent>> = agentsFlow
        
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> {
             return MutableStateFlow(savedAgents[agentId])
        }
        
        override suspend fun saveAgent(agent: Agent) {
            savedAgents[agent.id] = agent
            agentsFlow.value = savedAgents.values.toList()
        }

        override suspend fun saveAgentMetadata(agent: Agent) {
            val existing = savedAgents[agent.id]
            savedAgents[agent.id] = agent.copy(messages = existing?.messages ?: agent.messages)
            agentsFlow.value = savedAgents.values.toList()
        }

        override suspend fun saveMessage(agentId: String, message: ChatMessage) {
            val agent = savedAgents[agentId] ?: return
            val updatedMessages = agent.messages + message
            saveAgent(agent.copy(messages = updatedMessages))
        }

        override suspend fun deleteAgent(agentId: String) {
            savedAgents.remove(agentId)
            agentsFlow.value = savedAgents.values.toList()
        }
    }

    private lateinit var repository: FakeRepository
    private lateinit var interactor: ChatInteractor
    
    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeRepository()
        
        val fakeChatRepo = FakeChatRepo()
        val chatStreamingUseCase = object : ChatStreamingUseCase(fakeChatRepo) {
            override fun invoke(
                messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int,
                temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider
            ) = flowOf(Result.success("test"))
        }
        
        val updateWorkingMemoryUseCase = UpdateWorkingMemoryUseCase(fakeChatRepo)
        val strategyFactory = StrategyDelegateFactory(
            extractFactsUseCase = ExtractFactsUseCase(fakeChatRepo),
            summarizeChatUseCase = SummarizeChatUseCase(fakeChatRepo),
            updateWorkingMemoryUseCase = updateWorkingMemoryUseCase,
            repository = fakeChatRepo
        )
        
        interactor = ChatInteractor(
            chatStreamingUseCase = chatStreamingUseCase,
            repository = repository,
            memoryManager = ChatMemoryManager(),
            strategyFactory = strategyFactory
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testUpdateAgentAppliedToStateAndRepository() = runTest {
        val viewModel = LLMViewModel(repository, interactor)
        advanceUntilIdle()

        viewModel.createAgent()
        advanceUntilIdle()
        
        val createdAgentId = viewModel.state.value.agents.first { it.id != GENERAL_CHAT_ID }.id
        viewModel.selectAgent(createdAgentId)
        
        val newName = "Updated Name"
        val newPrompt = "Updated Prompt"
        val newTemp = 0.5
        val newProvider = LLMProvider.DeepSeek("custom-model")
        val newStopWord = "stop"
        val newMaxTokens = 1234
        val newStrategy = ChatMemoryStrategy.Summarization(15)

        viewModel.updateAgent(
            id = createdAgentId,
            name = newName,
            systemPrompt = newPrompt,
            temperature = newTemp,
            provider = newProvider,
            stopWord = newStopWord,
            maxTokens = newMaxTokens,
            memoryStrategy = newStrategy
        )
        
        advanceUntilIdle()

        val stateAgent = viewModel.state.value.selectedAgent
        assertNotNull(stateAgent)
        assertEquals(newName, stateAgent.name)
        assertEquals(newPrompt, stateAgent.systemPrompt)
        assertEquals(newTemp, stateAgent.temperature)
        assertEquals(newProvider, stateAgent.provider)
        assertEquals(newStopWord, stateAgent.stopWord)
        assertEquals(newMaxTokens, stateAgent.maxTokens)
        assertEquals(newStrategy, stateAgent.memoryStrategy)

        val repoAgent = repository.savedAgents[createdAgentId]
        assertNotNull(repoAgent)
        assertEquals(newName, repoAgent.name)
        assertEquals(newStrategy, repoAgent.memoryStrategy)
    }

    @Test
    fun testUpdateUserProfileApplied() = runTest {
        val viewModel = LLMViewModel(repository, interactor)
        advanceUntilIdle()
        
        viewModel.createAgent()
        advanceUntilIdle()
        val agentId = viewModel.state.value.agents.first { it.id != GENERAL_CHAT_ID }.id
        viewModel.selectAgent(agentId)

        val profile = UserProfile(
            preferences = "Style: formal",
            constraints = "No emojis"
        )

        viewModel.updateUserProfile(agentId, profile)
        advanceUntilIdle()

        assertEquals(profile, viewModel.state.value.selectedAgent?.userProfile)
        assertEquals(profile, repository.savedAgents[agentId]?.userProfile)
    }

    @Test
    fun testMergeLogicDoesNotRevertSettings() = runTest {
        val viewModel = LLMViewModel(repository, interactor)
        advanceUntilIdle()
        
        viewModel.createAgent()
        advanceUntilIdle()
        val agentId = viewModel.state.value.agents.first { it.id != GENERAL_CHAT_ID }.id
        
        val currentAgent = viewModel.state.value.agents.find { it.id == agentId }!!
        val updatedLocally = currentAgent.copy(name = "Local Name")
        
        val staleFromRepo = currentAgent.copy(name = "Stale Name")
        val merged = updatedLocally.mergeWith(staleFromRepo)
        
        assertEquals("Local Name", merged.name, "Имя должно сохраниться локальное при слиянии с БД")
    }
}

private class FakeChatRepo : ChatRepository {
    override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = flowOf(Result.success(""))
    override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
    override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
    override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
    override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
    override suspend fun saveAgentState(state: AgentState) {}
    override suspend fun getAgentState(agentId: String) = null
    override suspend fun getProfile(agentId: String): UserProfile? = null
    override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
    override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
    override suspend fun saveInvariant(invariant: Invariant) {}
    override fun observeAgentState(agentId: String) = flowOf(null)
}
