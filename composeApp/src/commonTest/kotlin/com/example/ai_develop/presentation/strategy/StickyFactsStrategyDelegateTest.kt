package com.example.ai_develop.presentation.strategy

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?) {}
        override suspend fun deleteAgent(agentId: String) {}
        override fun getTasks(): Flow<List<TaskContext>> = emptyFlow()
        override suspend fun saveTask(task: TaskContext) {}
        override suspend fun deleteTask(task: TaskContext) {}
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = emptyFlow()
        override suspend fun deleteMessagesForTask(taskId: String) {}
    }

    private class MockChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts(facts = listOf("Fact 1: Value 1")))
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("summary")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis(currentTask = "New", progress = "Done"))
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    private class MockExtractFactsUseCase(repo: ChatRepository) : ExtractFactsUseCase(repo) {
        var called = false
        override suspend fun invoke(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider, windowSize: Int): Result<ChatFacts> {
            called = true
            return Result.success(ChatFacts(facts = listOf("New Fact: New Value")))
        }
    }

    @Test
    fun `should extract facts when interval reached`() = runTest {
        val chatRepo = MockChatRepository()
        val extractUseCase = MockExtractFactsUseCase(chatRepo)
        val updateWorkingMemoryUseCase = UpdateWorkingMemoryUseCase(chatRepo)
        val delegate = StickyFactsStrategyDelegate(extractUseCase, updateWorkingMemoryUseCase)
        val repo = MockLocalRepository()
        
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, updateInterval = 2)
        val agent = Agent(
            id = "agent1",
            name = "Test",
            systemPrompt = "",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100,
            memoryStrategy = strategy,
            messages = listOf(
                createChatMessage("1", SourceType.USER, null, "main_branch"),
                createChatMessage("2", SourceType.ASSISTANT, "id1", "main_branch"),
                createChatMessage("3", SourceType.USER, "id2", "main_branch"),
                createChatMessage("4", SourceType.ASSISTANT, "id3", "main_branch")
            )
        )

        var updatedAgent: Agent? = null
        delegate.onMessageReceived(this, agent, repo) { updatedAgent = it }

        testScheduler.advanceUntilIdle()

        assertTrue(extractUseCase.called, "ExtractFactsUseCase should be called")
        val finalStrategy = updatedAgent?.memoryStrategy as? ChatMemoryStrategy.StickyFacts
        assertEquals("New Fact: New Value", finalStrategy?.facts?.facts?.first())
    }
}
