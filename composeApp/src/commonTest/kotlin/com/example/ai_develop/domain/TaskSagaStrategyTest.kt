package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSagaStrategyTest {

    private class FakeLocalRepository : LocalChatRepository {
        val tasks = MutableStateFlow<Map<String, TaskContext>>(emptyMap())
        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val agents = MutableStateFlow<List<Agent>>(emptyList())

        override fun getAgents(): Flow<List<Agent>> = agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = agents.map { it.find { a -> a.id == agentId } }
        override suspend fun saveAgent(agent: Agent) { agents.value = agents.value.filterNot { it.id == agent.id } + agent }
        override suspend fun saveAgentMetadata(agent: Agent) { saveAgent(agent) }
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?) {
            messages.value = messages.value + message.copy(taskId = taskId, taskState = taskState)
        }
        override suspend fun deleteAgent(agentId: String) { agents.value = agents.value.filterNot { it.id == agentId } }
        override fun getTasks(): Flow<List<TaskContext>> = tasks.map { it.values.toList() }
        override suspend fun saveTask(task: TaskContext) { tasks.value = tasks.value + (task.taskId to task) }
        override suspend fun deleteTask(task: TaskContext) { tasks.value = tasks.value - task.taskId }
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = messages.map { it.filter { m -> m.taskId == taskId } }
        override suspend fun deleteMessagesForTask(taskId: String) { messages.value = messages.value.filterNot { it.taskId == taskId } }
    }

    private class FakeChatRepository : ChatRepository {
        var responseFlows = mutableListOf<Flow<Result<String>>>()
        var summarizeCalled = false

        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider): Flow<Result<String>> {
            return if (responseFlows.isNotEmpty()) responseFlows.removeAt(0) else emptyFlow()
        }
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) = Result.success(ChatFacts())
        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider): Result<String> {
            summarizeCalled = true
            return Result.success("Summarized context")
        }
        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(TaskAnalysisResult())
        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) = Result.success(WorkingMemoryAnalysis())
        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun getProfile(agentId: String) = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = flowOf(null)
    }

    private lateinit var localRepo: FakeLocalRepository
    private lateinit var chatRepo: FakeChatRepository
    private val memoryManager = ChatMemoryManager()
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        localRepo = FakeLocalRepository()
        chatRepo = FakeChatRepository()
    }

    private fun createAgent(id: String) = Agent(id = id, name = "Agent $id", systemPrompt = "Prompt", provider = LLMProvider.DeepSeek(), temperature = 0.7, stopWord = "", maxTokens = 1000)

    private suspend fun createSagaWithAgents(context: TaskContext, agents: List<Agent>): TaskSaga {
        agents.forEach { localRepo.saveAgent(it) }
        localRepo.saveTask(context)
        val saga = TaskSaga(
            chatRepo, localRepo,
            agents.find { it.id == context.architectAgentId },
            agents.find { it.id == context.executorAgentId },
            agents.find { it.id == context.validatorAgentId },
            context, memoryManager, testDispatcher
        )
        return saga
    }

    @Test
    fun `reset should clear messages and return to planning`() = runTest(testDispatcher) {
        val agent = createAgent("a1")
        val context = TaskContext(taskId = "t1", title = "T", state = AgentTaskState(TaskState.EXECUTION, agent), architectAgentId = "a1", isPaused = false)
        
        val saga = createSagaWithAgents(context, listOf(agent))
        advanceUntilIdle()
        localRepo.saveMessage("a1", ChatMessage(message = "M1", source = SourceType.USER), "t1", TaskState.EXECUTION)
        
        saga.reset()
        advanceUntilIdle()

        assertEquals(TaskState.PLANNING, saga.context.value.state.taskState)
        assertTrue(saga.context.value.isPaused)
        assertEquals(0, localRepo.messages.value.size, "Messages should be deleted")
    }

    @Test
    fun `handleStageError should prefix error message with agent name`() = runTest(testDispatcher) {
        val agent = createAgent("a1")
        // Добавляем всех агентов, чтобы сага была "ready"
        val context = TaskContext(
            taskId = "t1", 
            title = "T", 
            state = AgentTaskState(TaskState.PLANNING, agent), 
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        
        chatRepo.responseFlows.add(flowOf(Result.failure(Exception("Network Timeout"))))
        
        val saga = createSagaWithAgents(context, listOf(agent))
        advanceUntilIdle() 
        
        saga.start()
        advanceUntilIdle() 

        val messages = localRepo.messages.value
        val lastMessage = messages.lastOrNull { it.source == SourceType.SYSTEM && it.isSystemNotification }
        
        assertNotNull(lastMessage, "Should have an error message in repo.")
        assertTrue(lastMessage.message.contains("❌ Ошибка API (Agent a1)"), "Error should contain agent name. Got: ${lastMessage.message}")
        assertTrue(saga.context.value.isPaused, "Saga should pause on error")
    }
}
