package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocalRepository : LocalChatRepository {
        val tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        val agents = MutableStateFlow<List<Agent>>(emptyList())
        val messages = mutableMapOf<String, MutableList<ChatMessage>>()

        override fun getAgents(): Flow<List<Agent>> = agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(agents.value.find { it.id == agentId })
        override suspend fun saveAgent(agent: Agent) {
            agents.value = agents.value.filterNot { it.id == agent.id } + agent
        }
        override suspend fun saveAgentMetadata(agent: Agent) = saveAgent(agent)
        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?) {
            if (taskId != null) {
                messages.getOrPut(taskId) { mutableListOf() }.add(message)
            }
        }
        override suspend fun deleteAgent(agentId: String) {
            agents.value = agents.value.filterNot { it.id == agentId }
        }
        override fun getTasks(): Flow<List<TaskContext>> = tasks.asStateFlow()
        override suspend fun saveTask(task: TaskContext) {
            tasks.value = tasks.value.filterNot { it.taskId == task.taskId } + task
        }
        override suspend fun deleteTask(task: TaskContext) {
            tasks.value = tasks.value.filterNot { it.taskId == task.taskId }
        }
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = flow {
            emit(messages[taskId] ?: emptyList())
        }
        override suspend fun deleteMessagesForTask(taskId: String) {
            messages.remove(taskId)
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

    private lateinit var localRepository: FakeLocalRepository
    private lateinit var chatRepository: FakeChatRepo
    private lateinit var memoryManager: ChatMemoryManager
    private lateinit var viewModel: TaskViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        localRepository = FakeLocalRepository()
        chatRepository = FakeChatRepo()
        memoryManager = ChatMemoryManager()
        viewModel = TaskViewModel(chatRepository, localRepository, memoryManager)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCreateTask() = runTest {
        val title = "New Task"
        viewModel.createTask(title)
        advanceUntilIdle()

        val task = viewModel.tasks.value.find { it.title == title }
        assertNotNull(task)
        assertEquals(title, task.title)
        assertTrue(task.isPaused)
        assertEquals(task.taskId, viewModel.selectedTaskId.value)
    }

    @Test
    fun testSelectTask() = runTest {
        val taskId = "task-123"
        val task = TaskContext(taskId = taskId, title = "Task", state = AgentTaskState(TaskState.PLANNING, createTestAgent()))
        localRepository.saveTask(task)
        advanceUntilIdle()

        viewModel.selectTask(taskId)
        advanceUntilIdle()

        assertEquals(taskId, viewModel.selectedTaskId.value)
    }

    @Test
    fun testDeleteTask() = runTest {
        val taskId = "task-123"
        val task = TaskContext(taskId = taskId, title = "Task", state = AgentTaskState(TaskState.PLANNING, createTestAgent()))
        localRepository.saveTask(task)
        viewModel.selectTask(taskId)
        advanceUntilIdle()

        viewModel.deleteTask(task)
        advanceUntilIdle()

        assertTrue(viewModel.tasks.value.isEmpty())
        assertNull(viewModel.selectedTaskId.value)
    }

    private fun createTestAgent() = Agent(
        name = "Test",
        systemPrompt = "",
        temperature = 0.7,
        provider = LLMProvider.DeepSeek(),
        stopWord = "",
        maxTokens = 2000
    )
}
