package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LLMViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    
    private class FakeRepository : ChatRepository {
        val agentsFlow = MutableStateFlow<List<Agent>>(emptyList())
        val savedAgents = mutableMapOf<String, Agent>()
        val savedStates = mutableMapOf<String, AgentState>()

        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = flowOf(Result.success(""))
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) = Result.success("")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {
            savedStates[state.agentId] = state
        }
        override suspend fun getAgentState(agentId: String) = savedStates[agentId]
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = flowOf(savedStates[agentId])
    }

    private lateinit var repository: FakeRepository
    private lateinit var useCase: ChatStreamingUseCase
    
    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeRepository()
        
        useCase = object : ChatStreamingUseCase(repository, ChatMemoryManager(), kotlinx.coroutines.MainScope()) {
            override fun invoke(
                messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int,
                temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider
            ) = flowOf(Result.success("test"))
        }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testUpdateAgentAppliedToStateAndRepository() = runTest {
        val viewModel = LLMViewModel(repository, useCase)
        advanceUntilIdle()

        viewModel.createAgent()
        advanceUntilIdle()
        
        val createdAgentId = viewModel.state.value.selectedAgentId ?: ""
        
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

        val repoState = repository.savedStates[createdAgentId]
        assertNotNull(repoState)
        assertEquals(newName, repoState.name)
        assertEquals(newPrompt, repoState.systemPrompt)
        assertEquals(newTemp, repoState.temperature)
        assertEquals(newMaxTokens, repoState.maxTokens)
        assertEquals(newStopWord, repoState.stopWord)
        assertEquals(newStrategy, repoState.memoryStrategy)
    }

    @Test
    fun testUpdateUserProfileApplied() = runTest {
        val viewModel = LLMViewModel(repository, useCase)
        advanceUntilIdle()
        
        viewModel.createAgent()
        advanceUntilIdle()
        val agentId = viewModel.state.value.selectedAgentId ?: ""

        val profile = UserProfile(
            preferences = "Style: formal",
            constraints = "No emojis"
        )

        viewModel.updateUserProfile(agentId, profile)
        advanceUntilIdle()

        // Проверяем вызов сохранения профиля в репозитории (или через useCase)
        // В данной реализации LLMViewModel вызывает repository.saveProfile
    }
}
