package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskSagaTest {

    private class FakeLocalRepository : LocalChatRepository {
        val _tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        val _agents = MutableStateFlow<List<Agent>>(emptyList())
        val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun getAgents(): Flow<List<Agent>> = _agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(null)
        override suspend fun saveAgent(agent: Agent): Result<Unit> { 
            _agents.value = _agents.value.filterNot { it.id == agent.id } + agent 
            return Result.success(Unit)
        }
        override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> { return saveAgent(agent) }
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit> {
            _messages.value = _messages.value + message.copy(taskId = taskId, taskState = taskState)
            return Result.success(Unit)
        }
        override suspend fun deleteAgent(agentId: String): Result<Unit> = Result.success(Unit)
        override fun getTasks(): Flow<List<TaskContext>> = _tasks.asStateFlow()
        override suspend fun saveTask(task: TaskContext): Result<Unit> {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId } + task
            return Result.success(Unit)
        }
        override suspend fun deleteTask(task: TaskContext): Result<Unit> = Result.success(Unit)
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = _messages.map { list -> list.filter { it.taskId == taskId } }
        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> {
            _messages.value = _messages.value.filterNot { it.taskId == taskId }
            return Result.success(Unit)
        }
    }

    private class FakeChatRepository : ChatRepository {
        var nextResponse: String = ""
        var summarizeCalled = false
        var errorToThrow: Exception? = null

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> = flow {
            errorToThrow?.let { throw it }
            emit(Result.success(nextResponse))
        }

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> {
            summarizeCalled = true
            return Result.success("Summary of planning")
        }
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

    @Test
    fun testIsReadyToRunProperty() {
        val agent = Agent(id="a1", name="A", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)
        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1"
        )
        assertTrue(context.isReadyToRun)
        
        val contextMissing = context.copy(executorAgentId = null)
        assertTrue(!contextMissing.isReadyToRun)
        assertEquals(listOf("Исполнитель"), contextMissing.missingAgents)
    }

    @Test
    fun testResumePreventsStartIfAgentsMissing() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        
        val agent = Agent(id="a1", name="A", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)
        localRepo.saveAgent(agent)

        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1", // Только один агент
            isPaused = true
        )
        localRepo.saveTask(context)

        val saga = TaskSaga(chatRepo, localRepo, agent, null, null, context, memoryManager, testDispatcher)
        
        saga.resume()
        advanceUntilIdle()

        // Должно остаться на паузе
        assertTrue(saga.context.value.isPaused, "Saga should remain paused if agents are missing")
        
        // Должно быть системное сообщение
        val messages = localRepo.getMessagesForTask("t1").first()
        assertTrue(messages.any { it.message.contains("Не назначены агенты: Исполнитель, Валидатор") })
    }

    @Test
    fun testSagaPreventsForeignKeyError() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        
        // Создаем задачу БЕЗ агентов
        val context = TaskContext(
            taskId = "task_error",
            title = "No Agent Task",
            state = AgentTaskState(TaskState.PLANNING, Agent(name="D", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)),
            architectAgentId = null,
            isPaused = false
        )
        localRepo.saveTask(context)

        val saga = TaskSaga(chatRepo, localRepo, null, null, null, context, memoryManager, testDispatcher)
        
        // Запуск не должен упасть, а должен поставить на паузу
        saga.start()
        advanceUntilIdle()

        // Проверяем, что задача встала на паузу
        assertTrue(saga.context.value.isPaused, "Saga should pause if agent is missing")
    }

    @Test
    fun testSagaHandlesApiErrorAndSavesMessage() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        
        val agent = Agent(id="a1", name="A1", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)
        localRepo.saveAgent(agent)
        
        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        localRepo.saveTask(context)
        
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)
        
        // Эмулируем ошибку сети (например, 127.0.0.1 failed)
        chatRepo.errorToThrow = Exception("Failed to connect to 127.0.0.1")
        
        saga.start()
        advanceUntilIdle()
        
        // Проверяем, что в истории появилось сообщение об ошибке
        val messages = localRepo.getMessagesForTask("t1").first()
        assertTrue(messages.any { it.message.contains("❌ Ошибка API") }, "Error message should be saved to chat")
        assertTrue(saga.context.value.isPaused, "Saga should pause on API error")
    }

    @Test
    fun testSagaReactiveToExternalChanges() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        
        val agent = Agent(id="a1", name="A", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)
        localRepo.saveAgent(agent)
        
        val context = TaskContext(
            taskId="t1", 
            title="T", 
            state=AgentTaskState(TaskState.PLANNING, agent), 
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused=true
        )
        localRepo.saveTask(context)
        
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)
        
        // Внешнее изменение: снимаем с паузы через репозиторий
        localRepo.saveTask(context.copy(isPaused = false))
        advanceUntilIdle()
        
        // Saga должна была "проснуться" и вызвать чат
        assertTrue(saga.context.value.isPaused == false)
    }
}
