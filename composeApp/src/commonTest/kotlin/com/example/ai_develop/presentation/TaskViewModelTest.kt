package com.example.ai_develop.presentation

import com.example.ai_develop.data.database.LocalChatRepository
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

        override suspend fun pauseAllTasksOnAppLaunch(): Result<Unit> {
            tasks.value = tasks.value.map { it.copy(isPaused = true) }
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

    private class FakeLocalChatRepo(
        private val taskRepo: FakeTaskRepository,
        private val messageRepo: FakeMessageRepository
    ) : LocalChatRepository {
        override fun getAgents(): Flow<List<Agent>> = flowOf(emptyList())
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = flowOf(null)
        override suspend fun saveAgent(agent: Agent): Result<Unit> = Result.success(Unit)
        override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> = Result.success(Unit)
        override suspend fun saveMessage(
            agentId: String,
            message: ChatMessage,
            taskId: String?,
            taskState: TaskState?
        ): Result<Unit> = messageRepo.saveMessage(agentId, message, taskId, taskState)

        override suspend fun deleteAgent(agentId: String) {}
        override fun getTasks(): Flow<List<TaskContext>> = taskRepo.getTasks()
        override suspend fun saveTask(task: TaskContext): Result<Unit> = taskRepo.saveTask(task)
        override suspend fun deleteTask(task: TaskContext): Result<Unit> = taskRepo.deleteTask(task)
        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> =
            messageRepo.getMessagesForTask(taskId)

        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> =
            messageRepo.deleteMessagesForTask(taskId)
    }

    private class FakeChatRepo(
        private val messageRepo: FakeMessageRepository,
        private val taskRepo: FakeTaskRepository
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
            val tid = state.messages.firstOrNull()?.taskId ?: state.agentId
            messageRepo.syncFromAgentState(tid, state.messages)
        }

        override suspend fun resetTaskConversation(taskId: String): Result<Unit> = runCatching {
            messageRepo.deleteMessagesForTask(taskId)
            val task = taskRepo.getTask(taskId)
            val agentIds = buildSet {
                add(taskId)
                task?.architectAgentId?.let { add(it) }
                task?.executorAgentId?.let { add(it) }
                task?.validatorAgentId?.let { add(it) }
            }
            for (id in agentIds) {
                val state = states[id] ?: continue
                val cleared = state.copy(
                    workingMemory = state.workingMemory.clearConversation(),
                    memoryStrategy = state.memoryStrategy.clearConversationData(),
                    messages = state.messages.filterNot { msg ->
                        msg.taskId == taskId || (msg.taskId == null && id == taskId)
                    },
                    plan = AgentPlan(),
                    currentStepId = null,
                    currentStage = AgentStage.PLANNING
                )
                saveAgentState(cleared)
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
        chatRepo = FakeChatRepo(messageRepo, taskRepo)
        val localChatRepo = FakeLocalChatRepo(taskRepo, messageRepo)
        val taskSagaCoordinator = TaskSagaCoordinator(chatRepo, localChatRepo, ChatMemoryManager(), testDispatcher)

        val chatStreamingUseCase = ChatStreamingUseCase(chatRepo, ChatMemoryManager(), CoroutineScope(testDispatcher), testAgentToolRegistry())
        val agentFactory = DefaultAgentFactory()

        viewModel = TaskViewModel(
            getTasksUseCase = GetTasksUseCase(taskRepo),
            getTaskUseCase = GetTaskUseCase(taskRepo),
            createTaskUseCase = CreateTaskUseCase(taskRepo),
            updateTaskUseCase = UpdateTaskUseCase(taskRepo),
            deleteTaskUseCase = DeleteTaskUseCase(taskRepo),
            resetTaskUseCase = ResetTaskUseCase(chatRepo, chatStreamingUseCase),
            getMessagesUseCase = GetMessagesUseCase(messageRepo),
            chatStreamingUseCase = chatStreamingUseCase,
            getAgentsUseCase = GetAgentsUseCase(localChatRepo),
            agentFactory = agentFactory,
            taskSagaCoordinator = taskSagaCoordinator
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
    fun activeSagaContext_matchesDbAfterSelect_notStalePauseState() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.activeSagaContext.collect() }

        val taskId = "fresh-task"
        taskRepo.saveTask(
            TaskContext(
                taskId = taskId,
                title = "Fresh",
                state = AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
                isPaused = false,
                isStarted = false
            )
        )
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()

        val ctx = viewModel.activeSagaContext.value
        assertNotNull(ctx)
        val showsAsRunning = ctx.isStarted && !ctx.isPaused && ctx.state.taskState != TaskState.DONE
        assertFalse(showsAsRunning, "Незапущенная задача не должна показывать кнопку паузы (состояние из БД)")
    }

    @Test
    fun selectTask_switchingFromRunningTask_pausesPrevious() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.activeSagaContext.collect() }

        val running = TaskContext(
            taskId = "t-run",
            title = "Running",
            state = AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
            isPaused = false,
            isStarted = true
        )
        val other = TaskContext(
            taskId = "t-other",
            title = "Other",
            state = AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
            isPaused = false,
            isStarted = false
        )
        taskRepo.saveTask(running)
        taskRepo.saveTask(other)
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.SelectTask("t-run"))
        advanceUntilIdle()
        viewModel.onEvent(TaskEvent.SelectTask("t-other"))
        advanceUntilIdle()

        val pausedRun = viewModel.tasks.value.find { it.taskId == "t-run" }
        assertNotNull(pausedRun)
        assertTrue(pausedRun.isPaused, "При выборе другой задачи предыдущая активная должна быть на паузе")
        assertEquals("t-other", viewModel.selectedTaskId.value)
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
        taskRepo.saveTask(
            TaskContext(
                id1, "T1", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
                isPaused = true, isStarted = true
            )
        )
        taskRepo.saveTask(
            TaskContext(
                id2, "T2", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
                isPaused = true, isStarted = true
            )
        )
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
    fun testUpdateTask_RuntimeLimitsPersisted() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        val taskId = "limits-task"
        taskRepo.saveTask(
            TaskContext(
                taskId,
                "T",
                AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()),
                runtimeState = TaskRuntimeState.defaultFor(taskId).copy(maxSteps = 50)
            )
        )
        advanceUntilIdle()
        val task = viewModel.tasks.value.first { it.taskId == taskId }
        viewModel.onEvent(TaskEvent.UpdateTask(task.copy(runtimeState = task.runtimeState.copy(maxSteps = 99))))
        advanceUntilIdle()
        assertEquals(99, viewModel.tasks.value.find { it.taskId == taskId }?.runtimeState?.maxSteps)
    }

    @Test
    fun testResetTask_MessagesClearedAndStateReset() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.taskMessages.collect() }

        val taskId = "reset-me"
        taskRepo.saveTask(
            TaskContext(
                taskId,
                "Title",
                AgentTaskState(TaskState.EXECUTION, DefaultAgentFactory().create()),
                isPaused = false,
                isStarted = true,
                step = 3,
                plan = listOf("step"),
                planDone = listOf("done"),
                currentPlanStep = "cur",
                runtimeState = TaskRuntimeState.defaultFor(taskId).copy(
                    maxSteps = 77,
                    maxPlanningSteps = 12,
                    maxExecutionSteps = 13,
                    maxVerificationSteps = 14
                )
            )
        )
        messageRepo.saveMessage("agent", ChatMessage(message = "test", role = "user", source = SourceType.USER, timestamp = 0), taskId, TaskState.EXECUTION)
        chatRepo.saveAgentState(
            AgentState(
                agentId = taskId,
                workingMemory = WorkingMemory(currentTask = "old"),
                memoryStrategy = ChatMemoryStrategy.Summarization(10, summary = "old")
            )
        )
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.ResetTask(taskId))
        advanceUntilIdle()

        val task = viewModel.tasks.value.find { it.taskId == taskId }
        assertNotNull(task)
        assertEquals(TaskState.PLANNING, task.state.taskState)
        assertFalse(task.isPaused)
        assertFalse(task.isStarted)
        assertEquals(0, task.step)
        assertTrue(task.plan.isEmpty())
        assertTrue(task.planDone.isEmpty())
        assertNull(task.currentPlanStep)
        assertEquals(77, task.runtimeState.maxSteps, "maxSteps must survive reset")
        assertEquals(12, task.runtimeState.maxPlanningSteps)
        assertEquals(13, task.runtimeState.maxExecutionSteps)
        assertEquals(14, task.runtimeState.maxVerificationSteps)
        assertEquals(0, task.runtimeState.stepCount)

        val cleared = chatRepo.lastSavedState(taskId)
        assertNotNull(cleared)
        assertNull(cleared?.workingMemory?.currentTask)
        assertNull((cleared?.memoryStrategy as? ChatMemoryStrategy.Summarization)?.summary)

        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()
        assertTrue(viewModel.taskMessages.value.isEmpty())
    }

    @Test
    fun testWelcomeOnUnpauseEmptyChat() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        backgroundScope.launch { viewModel.taskMessages.collect() }
        backgroundScope.launch { viewModel.activeAgent.collect() }

        val taskId = "welcome-task"
        taskRepo.saveTask(
            TaskContext(taskId, "T", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()), isPaused = false, isStarted = false)
        )
        advanceUntilIdle()
        viewModel.onEvent(TaskEvent.SelectTask(taskId))
        advanceUntilIdle()

        viewModel.onEvent(TaskEvent.TogglePause(taskId))
        advanceUntilIdle()

        assertTrue(viewModel.taskMessages.value.any { it.role == "assistant" }, "welcome should add assistant message")
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

    @Test
    fun testCorner_SelectTask_nullThenId() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        taskRepo.saveTask(
            TaskContext("t1", "A", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()))
        )
        advanceUntilIdle()
        viewModel.onEvent(TaskEvent.SelectTask("t1"))
        advanceUntilIdle()
        assertEquals("t1", viewModel.selectedTaskId.value)
        viewModel.onEvent(TaskEvent.SelectTask(null))
        advanceUntilIdle()
        assertNull(viewModel.selectedTaskId.value)
    }

    @Test
    fun testStress_SequentialResetAndRuntimeUpdates() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        val taskId = "stress-reset"
        taskRepo.saveTask(
            TaskContext(
                taskId,
                "S",
                AgentTaskState(TaskState.EXECUTION, DefaultAgentFactory().create()),
                isPaused = true,
                isStarted = true,
                step = 2,
                runtimeState = TaskRuntimeState.defaultFor(taskId).copy(maxSteps = 100, stepCount = 5)
            )
        )
        advanceUntilIdle()

        repeat(20) { i ->
            val before = viewModel.tasks.value.first { it.taskId == taskId }
            viewModel.onEvent(
                TaskEvent.UpdateTask(before.copy(runtimeState = before.runtimeState.copy(maxSteps = 100 + i)))
            )
            advanceUntilIdle()
            viewModel.onEvent(TaskEvent.ResetTask(taskId))
            advanceUntilIdle()
            val t = viewModel.tasks.value.first { it.taskId == taskId }
            assertEquals(100 + i, t.runtimeState.maxSteps, "iteration $i")
            assertEquals(0, t.runtimeState.stepCount)
            assertEquals(TaskState.PLANNING, t.state.taskState)
        }
    }

    @Test
    fun testStress_RapidSelectBetweenTasks() = runTest {
        backgroundScope.launch { viewModel.tasks.collect() }
        repeat(30) { i ->
            taskRepo.saveTask(
                TaskContext("s-$i", "T$i", AgentTaskState(TaskState.PLANNING, DefaultAgentFactory().create()))
            )
        }
        advanceUntilIdle()
        repeat(100) { r ->
            val id = "s-${r % 30}"
            viewModel.onEvent(TaskEvent.SelectTask(id))
        }
        advanceUntilIdle()
        assertEquals("s-9", viewModel.selectedTaskId.value)
    }
}

