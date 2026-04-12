package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import com.example.ai_develop.presentation.strategy.StrategyDelegateFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInteractorTest {

    private class MockLocalRepository : LocalChatRepository {
        val savedMessages = mutableListOf<ChatMessage>()
        var savedMetadata: Agent? = null
        override fun getAgents() = emptyFlow<List<Agent>>()
        override fun getAgentWithMessages(agentId: String) = flowOf(null)
        override suspend fun saveAgent(agent: Agent): Result<Unit> = Result.success(Unit)
        override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> { 
            savedMetadata = agent 
            return Result.success(Unit)
        }
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit> { 
            savedMessages.add(message) 
            return Result.success(Unit)
        }
        override suspend fun deleteAgent(agentId: String) {}
        override fun getTasks(): Flow<List<TaskContext>> = emptyFlow()
        override suspend fun saveTask(task: TaskContext): Result<Unit> = Result.success(Unit)
        override suspend fun deleteTask(task: TaskContext): Result<Unit> = Result.success(Unit)
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = emptyFlow()
        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> = Result.success(Unit)
    }

    private class MockChatRepository : ChatRepository {
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> = emptyFlow()
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("summary")
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<TaskAnalysisResult> = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider): Result<WorkingMemoryAnalysis> = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> = flowOf(null)
    }

    private class MockUseCase(repo: ChatRepository, scope: CoroutineScope) : ChatStreamingUseCase(repo, ChatMemoryManager(), scope, testAgentToolRegistry()) {
        override fun invoke(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> {
            return flowOf(Result.success("Hello "), Result.success("world!"))
        }
    }

    @Test
    fun `sendMessage should update local state and call repository`() = runTest {
        val repo = MockLocalRepository()
        val chatRepo = MockChatRepository()
        val useCase = MockUseCase(chatRepo, this)
        val memoryManager = ChatMemoryManager()
        val updateWorkingMemoryUseCase = UpdateWorkingMemoryUseCase(chatRepo)
        val strategyFactory = StrategyDelegateFactory(
            ExtractFactsUseCase(chatRepo),
            SummarizeChatUseCase(chatRepo),
            updateWorkingMemoryUseCase
        )
        
        val interactor = ChatInteractor(useCase, repo, memoryManager, strategyFactory)
        
        val agent = Agent(
            id = "agent1",
            name = "Test Agent",
            systemPrompt = "System",
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100,
            messages = emptyList()
        )

        val updates = mutableListOf<Agent>()
        var lastLoadingStatus = false

        val job = interactor.sendMessage(
            scope = this,
            agent = agent,
            messageText = "Hi",
            isJsonMode = false,
            onAgentUpdate = { _, updater -> 
                val current = updates.lastOrNull() ?: agent
                updates.add(updater(current)) 
            },
            onLoadingStatus = { lastLoadingStatus = it }
        )

        job.join()

        // Проверяем, что сообщение пользователя было добавлено
        assertTrue(updates.any { it.messages.any { m -> m.message == "Hi" && m.source == SourceType.USER } })
        
        // Проверяем финальное сообщение ассистента
        val finalAssistantMsg = updates.last().messages.find { it.source == SourceType.ASSISTANT }
        assertEquals("Hello world!", finalAssistantMsg?.message)
        
        // Проверяем сохранение в БД
        assertEquals(2, repo.savedMessages.size) 
        assertEquals("Hi", repo.savedMessages[0].message)
        assertEquals("Hello world!", repo.savedMessages[1].message)
        
        // Статус загрузки должен быть сброшен в конце
        assertTrue(!lastLoadingStatus)
    }
}
