package com.example.ai_develop.presentation

import com.example.ai_develop.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // Fake Repositories
    private class FakeTaskRepository : TaskRepository {
        val tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        override fun getTasks(): Flow<List<TaskContext>> = tasks.asStateFlow()
        override suspend fun getTask(taskId: String): TaskContext? = tasks.value.find { it.taskId == taskId }
        override suspend fun saveTask(task: TaskContext): Result<Unit> {
            tasks.value = tasks.value.filterNot { it.taskId == task.taskId } + task
            return Result.success(Unit)
        }
        override suspend fun deleteTask(task: TaskContext): Result<Unit> {
            tasks.value = tasks.value.filterNot { it.taskId == task.taskId }
            return Result.success(Unit)
        }
    }

    private class FakeMessageRepository : MessageRepository {
        private val taskMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> =
            taskMessages.map { it[taskId] ?: emptyList() }

        fun syncFromAgentState(taskId: String, messages: List<ChatMessage>) {
            taskMessages.update { it + (taskId to messages) }
        }

        fun getTaskMessagesSnapshot(taskId: String): List<ChatMessage> =
            taskMessages.value[taskId] ?: emptyList()

        override suspend fun saveMessage(agentId: String, message: ChatMessage, taskId: String?, taskState: TaskState?): Result<Unit> {
            if (taskId != null) {
                taskMessages.update { curr ->
                    val list = (curr[taskId] ?: emptyList()) + message
                    curr + (taskId to list)
                }
            }
            return Result.success(Unit)
        }

        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> {
            taskMessages.update { it - taskId }
            return Result.success(Unit)
        }
    }

    private class FakeChatRepo(
        private val messageRepo: FakeMessageRepository
    ) : ChatRepository {
        private val states = mutableMapOf<String, AgentState>()
        private val stateFlows = mutableMapOf<String, MutableStateFlow<AgentState?>>()

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ) = flowOf(Result.success("reply"))

        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider) =
            Result.success(ChatFacts())

        override suspend fun summarize(messages: List<ChatMessage>, previousSummary: String?, instruction: String, provider: LLMProvider) =
            Result.success("")

        override suspend fun analyzeTask(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) =
            Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(messages: List<ChatMessage>, instruction: String, provider: LLMProvider) =
            Result.success(WorkingMemoryAnalysis())

        override suspend fun saveAgentState(state: AgentState) {
            states[state.agentId] = state
            stateFlows.getOrPut(state.agentId) { MutableStateFlow(null) }.value = state
            val tid = state.messages.firstOrNull()?.taskId
            if (tid != null) {
                messageRepo.syncFromAgentState(tid, state.messages)
            }
        }

        override suspend fun getAgentState(agentId: String): AgentState? = states[agentId]

        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}

        override fun observeAgentState(agentId: String): Flow<AgentState?> =
            stateFlows.getOrPut(agentId) { MutableStateFlow(states[agentId]) }

        override suspend fun deleteAgent(agentId: String) {
            states.remove(agentId)
            stateFlows.remove(agentId)
        }

        fun lastSavedState(agentId: String): AgentState? = states[agentId]
    }

    private lateinit var taskRepo: FakeTaskRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var chatRepo: FakeChatRepo
    private lateinit var viewModel: TaskViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        taskRepo = FakeTaskRepository()
        messageRepo = FakeMessageRepository()
        chatRepo = FakeChatRepo(messageRepo)
        
        val chatStreamingUseCase = ChatStreamingUseCase(chatRepo, ChatMemoryManager(), CoroutineScope(testDispatcher))
        val agentFactory = DefaultAgentFactory()

        viewModel = TaskViewModel(
            getTasksUseCase = GetTasksUseCase(taskRepo),
            createTaskUseCase = CreateTaskUseCase(taskRepo),
            updateTaskUseCase = UpdateTaskUseCase(taskRepo),
            deleteTaskUseCase = DeleteTaskUseCase(taskRepo),
            resetTaskUseCase = ResetTaskUseCase(messageRepo),
            getMessagesUseCase = GetMessagesUseCase(messageRepo),
            chatStreamingUseCase = chatStreamingUseCase,
            getAgentsUseCase = GetAgentsUseCase(chatRepo),
            agentFactory = agentFactory
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCreateTask_StateAndSelection() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        
        val title = "Stress Test Task"
        viewModel.onEvent(TaskEvent.CreateTask(title))
        advanceUntilIdle()

        val task = viewModel.tasks.value.find { it.title == title }
        assertNotNull(task, "Task should be created and found in list")
        assertEquals(title, task.title)
        assertEquals(task.taskId, viewModel.selectedTaskId.value)
        assertEquals(TaskState.PLANNING, task.state.taskState)
    }

    @Test
    fun testSelectTask_ReactiveAgentUpdate() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.activeAgent.collect() }

        val taskId = "test-id"
        taskRepo.saveTask(TaskContext(taskId, "Title", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create())))
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()

        assertEquals(taskId, viewModel.selectedTaskId.value)
        assertNotNull(viewModel.activeAgent.value)
        assertEquals(taskId, viewModel.activeAgent.value?.agentId)
    }

    @Test
    fun testSendMessage_EmptyText_Ignored() = runTest {
        viewModel.onEvent(TaskEvent.SendMessage("id", "   "))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun testSendMessage_TaskMessagesPopulated() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.taskMessages.collect() }
        backgroundScope.launch { viewModel.activeAgent.collect() }
        advanceUntilIdle()

        val taskId = "task-with-msgs"
        taskRepo.saveTask(
            TaskContext(
                taskId,
                "Title",
                AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create())
            )
        )
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.SendMessage(taskId, "hello"))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error, "sendMessage should not leave error: ${viewModel.uiState.value.error}")

        val saved = chatRepo.lastSavedState(taskId)
        assertNotNull(saved, "saveAgentState should have been called for task agent")
        assertTrue(
            (saved?.messages?.size ?: 0) >= 2,
            "expected user + assistant in saved agent state, got ${saved?.messages?.size}"
        )
        assertEquals(taskId, saved?.messages?.firstOrNull()?.taskId)
        assertTrue(
            messageRepo.getTaskMessagesSnapshot(taskId).size >= 2,
            "message repo should receive user + assistant after saveAgentState"
        )
        assertTrue(
            viewModel.taskMessages.value.size >= 2,
            "taskMessages should reflect persisted task chat"
        )
    }

    @Test
    fun testTogglePause_CorrectTask() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }

        val id1 = "id1"
        val id2 = "id2"
        taskRepo.saveTask(TaskContext(id1, "T1", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()), isPaused = true))
        taskRepo.saveTask(TaskContext(id2, "T2", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()), isPaused = true))
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.TogglePause(id1))
        advanceUntilIdle()

        val t1 = viewModel.tasks.value.find { it.taskId == id1 }
        val t2 = viewModel.tasks.value.find { it.taskId == id2 }
        
        assertNotNull(t1)
        assertNotNull(t2)
        assertFalse(t1.isPaused)
        assertTrue(t2.isPaused)
    }

    @Test
    fun testResetTask_MessagesClearedAndStateReset() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.taskMessages.collect() }

        val taskId = "reset-me"
        taskRepo.saveTask(TaskContext(taskId, "Title", AgentTaskState(TaskState.EXECUTION, DefaultAgentFactory().create()), isPaused = false))
        messageRepo.saveMessage("agent", ChatMessage(message = "test", role = "user", source = SourceType.USER, timestamp = 0), taskId, TaskState.EXECUTION)
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.ResetTask(taskId))
        advanceUntilIdle()

        val task = viewModel.tasks.value.find { it.taskId == taskId }
        assertNotNull(task)
        assertEquals(TaskState.PLANNING, task.state.taskState)
        assertTrue(task.isPaused)
        
        // Проверка через поток сообщений (нужно выбрать задачу)
        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()
        assertTrue(viewModel.taskMessages.value.isEmpty())
    }

    @Test
    fun testStress_RapidTaskSwitching() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }

        repeat(50) { i ->
            viewModel.onEvent(TaskEvent.CreateTask("Task $i"))
        }
        advanceUntilIdle()
        
        assertEquals(50, viewModel.tasks.value.size)
        val lastCreatedId = viewModel.tasks.value.last().taskId
        assertEquals(lastCreatedId, viewModel.selectedTaskId.value)
    }

    @Test
    fun testCorner_DeleteSelectedTask() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.activeAgent.collect() }

        val task = TaskContext("id", "Title", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()))
        taskRepo.saveTask(task)
        viewModel.onEvent(TaskEvent.SelectTask("id"))
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.DeleteTask(task))
        advanceUntilIdle()

        assertNull(viewModel.selectedTaskId.value)
        assertNull(viewModel.activeAgent.value)
    }
}
