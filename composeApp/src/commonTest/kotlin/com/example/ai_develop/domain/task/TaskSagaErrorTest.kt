package com.example.ai_develop.domain.task
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskSagaErrorTest {

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
        override suspend fun deleteAgent(agentId: String) {}
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
            // If no error, just empty success
            emit(Result.success(""))
        }

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> = Result.success("")
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
    fun testErrorMessageVisibilityInChat() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        
        val agent = Agent(id="agent_1", name="Test Agent", systemPrompt="", provider=LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 2000)
        localRepo.saveAgent(agent)
        
        val context = TaskContext(
            taskId = "task_1",
            title = "Test",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "agent_1",
            executorAgentId = "agent_1",
            validatorAgentId = "agent_1",
            isPaused = false,
            isStarted = true
        )
        localRepo.saveTask(context)
        
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)
        
        // Эмулируем ту самую ошибку соединения
        val errorMessage = "Failed to connect to 127.0.0.1"
        chatRepo.errorToThrow = Exception(errorMessage)
        
        saga.start()
        advanceUntilIdle()
        
        // Проверяем, что сообщение об ошибке сохранено в репозиторий
        val messages = localRepo.getMessagesForTask("task_1").first()
        
        val hasErrorMessage = messages.any { it.message.contains("❌ Ошибка API") && it.message.contains(errorMessage) }
        assertTrue(hasErrorMessage, "Error message should be present in the task chat messages")
        
        // Проверяем, что оно помечено как системное уведомление
        val errorMsg = messages.find { it.message.contains(errorMessage) }
        assertTrue(errorMsg?.isSystemNotification == true, "Error message should be marked as system notification")
    }
}
