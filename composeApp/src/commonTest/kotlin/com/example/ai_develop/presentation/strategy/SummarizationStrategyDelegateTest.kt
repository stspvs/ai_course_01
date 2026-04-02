package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SummarizationStrategyDelegateTest {

    @Test
    fun `test summarization is triggered after windowSize assistant messages`() = runTest {
        val chatRepo = FakeChatRepository()
        val mockUseCase = MockSummarizeChatUseCase(chatRepo)
        val wmUseCase = UpdateWorkingMemoryUseCase(chatRepo)
        val localRepo = FakeLocalChatRepository()
        val delegate = SummarizationStrategyDelegate(mockUseCase, wmUseCase)
        
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 2)
        val agent = Agent(
            id = "1",
            name = "Summarizer",
            systemPrompt = "system",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 1000,
            memoryStrategy = strategy,
            messages = listOf(
                ChatMessage(message = "hi", source = SourceType.USER),
                ChatMessage(message = "hello", source = SourceType.ASSISTANT),
                ChatMessage(message = "how are you", source = SourceType.USER),
                ChatMessage(message = "I am fine", source = SourceType.ASSISTANT)
            )
        )

        var updatedAgent: Agent? = null
        delegate.onMessageReceived(
            scope = this,
            agent = agent,
            repository = localRepo,
            onAgentUpdated = { 
                updatedAgent = it
                launch { localRepo.saveAgent(it) }
            }
        )
        
        advanceUntilIdle()

        assertTrue(mockUseCase.invoked, "SummarizeChatUseCase should be invoked")
        assertEquals("New Summary", (updatedAgent?.memoryStrategy as? ChatMemoryStrategy.Summarization)?.summary)
        // Verify it was saved to repository
        assertTrue(localRepo.savedAgents.any { (it.memoryStrategy as? ChatMemoryStrategy.Summarization)?.summary == "New Summary" })
    }

    @Test
    fun `test summarization is NOT triggered if windowSize not reached`() = runTest {
        val chatRepo = FakeChatRepository()
        val mockUseCase = MockSummarizeChatUseCase(chatRepo)
        val wmUseCase = UpdateWorkingMemoryUseCase(chatRepo)
        val localRepo = FakeLocalChatRepository()
        val delegate = SummarizationStrategyDelegate(mockUseCase, wmUseCase)
        
        val strategy = ChatMemoryStrategy.Summarization(windowSize = 5)
        val agent = Agent(
            id = "1",
            name = "Summarizer",
            systemPrompt = "system",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 1000,
            memoryStrategy = strategy,
            messages = listOf(
                ChatMessage(message = "hi", source = SourceType.USER),
                ChatMessage(message = "hello", source = SourceType.ASSISTANT)
            )
        )

        delegate.onMessageReceived(
            scope = this,
            agent = agent,
            repository = localRepo,
            onAgentUpdated = { }
        )
        
        advanceUntilIdle()

        assertTrue(!mockUseCase.invoked, "SummarizeChatUseCase should NOT be invoked")
    }

    private class MockSummarizeChatUseCase(repo: ChatRepository) : SummarizeChatUseCase(repo) {
        var invoked = false
        override suspend fun invoke(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ): Result<String> {
            invoked = true
            return Result.success("New Summary")
        }
    }

    private class FakeChatRepository : ChatRepository {
        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = flowOf()

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
        ): Result<String> = Result.success("")
        
        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis(currentTask = "Updated Task", progress = "80%"))

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    private class FakeLocalChatRepository : LocalChatRepository {
        val savedAgents = mutableListOf<Agent>()
        override fun getAgents(): Flow<List<Agent>> = flowOf(emptyList())
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(null)
        override suspend fun saveAgent(agent: Agent) { savedAgents.add(agent) }
        override suspend fun saveAgentMetadata(agent: Agent) { savedAgents.add(agent) }
        override suspend fun saveMessage(agentId: String, message: ChatMessage) {}
        override suspend fun deleteAgent(agentId: String) {}
    }
}
