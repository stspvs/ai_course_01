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

class TaskSagaDetailedTest {

    private class FakeLocalRepository : LocalChatRepository {
        private val _tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())

        override fun getAgents(): Flow<List<Agent>> = _agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> = _agents.map { list -> list.find { it.id == agentId } }
        override suspend fun saveAgent(agent: Agent): Result<Unit> {
            _agents.value = _agents.value.filterNot { it.id == agent.id } + agent
            return Result.success(Unit)
        }

        override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> {
            return saveAgent(agent)
        }

        override suspend fun saveMessage(
            agentId: String,
            message: ChatMessage,
            taskId: String?,
            taskState: TaskState?
        ): Result<Unit> {
            _messages.value = _messages.value + message.copy(taskId = taskId, taskState = taskState)
            return Result.success(Unit)
        }

        override suspend fun deleteAgent(agentId: String) {
            _agents.value = _agents.value.filterNot { it.id == agentId }
        }

        override fun getTasks(): Flow<List<TaskContext>> = _tasks.asStateFlow()
        override suspend fun saveTask(task: TaskContext): Result<Unit> {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId } + task
            return Result.success(Unit)
        }

        override suspend fun deleteTask(task: TaskContext): Result<Unit> {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId }
            return Result.success(Unit)
        }

        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> = _messages.map { list -> list.filter { it.taskId == taskId } }
        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> {
            _messages.value = _messages.value.filterNot { it.taskId == taskId }
            return Result.success(Unit)
        }
    }

    private class FakeChatRepository : ChatRepository {
        val systemPrompts = mutableListOf<String>()
        var nextResponse: String = ""
        val responseQueue = ArrayDeque<String>()

        override fun chatStreaming(
            messages: List<ChatMessage>,
            systemPrompt: String,
            maxTokens: Int,
            temperature: Double,
            stopWord: String,
            isJsonMode: Boolean,
            provider: LLMProvider
        ): Flow<Result<String>> {
            systemPrompts.add(systemPrompt)
            val text = responseQueue.removeFirstOrNull() ?: nextResponse
            return flowOf(Result.success(text))
        }

        override suspend fun extractFacts(
            messages: List<ChatMessage>,
            currentFacts: ChatFacts,
            provider: LLMProvider
        ) = Result.success(ChatFacts())

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ) = Result.success("summary")

        override suspend fun analyzeTask(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) = Result.success(TaskAnalysisResult())

        override suspend fun analyzeWorkingMemory(
            messages: List<ChatMessage>,
            instruction: String,
            provider: LLMProvider
        ) = Result.success(WorkingMemoryAnalysis())

        override suspend fun saveAgentState(state: AgentState) {}
        override suspend fun getAgentState(agentId: String) = null
        override suspend fun getProfile(agentId: String) = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) =
            emptyList<Invariant>()

        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String) = flowOf(null)
    }

    @Test
    fun `runStage should include correct system instruction for each stage`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "Agent Prompt",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )

        val context = TaskContext(
            taskId = "t1",
            title = "Task Title",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)

        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

        val planJson =
            """{"success":true,"plan":{"goal":"g","steps":["one"],"successCriteria":"c"},"questions":[],"requiresUserConfirmation":false}"""
        assertNotNull(AutonomousTaskJsonParsers.parsePlannerOutput(planJson))
        val execJson = """{"success":true,"output":"done","errors":null}"""
        val verJson = """{"success":true,"issues":null,"suggestions":null}"""
        chatRepo.responseQueue.add(planJson)
        chatRepo.responseQueue.add(execJson)
        chatRepo.responseQueue.add(verJson)
        saga.start()
        advanceUntilIdle()

        assertEquals(
            3,
            chatRepo.systemPrompts.size,
            "Expected PLANNING→EXECUTION→VERIFICATION (no task invariants: plan verification skips LLM). Got:\n${chatRepo.systemPrompts.joinToString("\n---\n")}"
        )
        assertTrue(chatRepo.systemPrompts.any { it.contains("PLANNING stage") }, "Should have called PLANNING stage")
        assertTrue(chatRepo.systemPrompts.any { it.contains("EXECUTION stage") }, "Should have called EXECUTION stage")
        assertTrue(chatRepo.systemPrompts.any { it.contains("VERIFICATION stage") }, "Should have called VERIFICATION stage")
    }

    @Test
    fun `transitionBack should create failed notification and return to previous state`() =
        runTest {
            val localRepo = FakeLocalRepository()
            val chatRepo = FakeChatRepository()
            val memoryManager = ChatMemoryManager()
            val testDispatcher = UnconfinedTestDispatcher(testScheduler)
            val agent = Agent(
                id = "a1",
                name = "A",
                systemPrompt = "P",
                temperature = 0.7,
                provider = LLMProvider.DeepSeek(),
                stopWord = "",
                maxTokens = 2000
            )

            val planResult = PlanResult("Task Title", listOf("step1"), "c")
            val context = TaskContext(
                taskId = "t1",
                title = "Task Title",
                state = AgentTaskState(TaskState.EXECUTION, agent),
                executorAgentId = "a1",
                architectAgentId = "a1",
                validatorAgentId = "a1",
                isPaused = false,
                plan = planResult.steps,
                runtimeState = TaskRuntimeState.defaultFor("t1").copy(
                    stage = TaskState.EXECUTION,
                    planResult = planResult
                )
            )
            localRepo.saveAgent(agent)
            localRepo.saveTask(context)

            val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

            val failExec = """{"success":false,"output":"","errors":["Error reason"]}"""
            repeat(4) { chatRepo.responseQueue.add(failExec) }
            saga.start()
            advanceUntilIdle()

            assertEquals(TaskState.DONE, saga.context.value.state.taskState)
            assertEquals(TaskOutcome.FAILED, saga.context.value.runtimeState.outcome)

            val messages = localRepo.getMessagesForTask("t1").first()
            assertTrue(messages.any { it.message.contains("--- TASK END: FAILED ---") })
        }

    @Test
    fun `confirmPlan moves from awaiting confirmation to execution`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "P",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )
        val context = TaskContext(
            taskId = "t1",
            title = "Task Title",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            runtimeState = TaskRuntimeState.defaultFor("t1").copy(
                awaitingPlanConfirmation = true,
                planResult = PlanResult("Task Title", listOf("s1"), "c")
            )
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)

        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)
        chatRepo.responseQueue.add("""{"success":true,"issues":null,"suggestions":null}""")
        chatRepo.responseQueue.add("""{"success":true,"output":"x","errors":null}""")
        chatRepo.responseQueue.add("""{"success":true,"issues":null,"suggestions":null}""")

        saga.confirmPlan()
        advanceUntilIdle()

        assertTrue(chatRepo.systemPrompts.any { it.contains("EXECUTION stage") })
        // Single-step plan: after execution + verification the task may already be DONE.
        assertTrue(
            saga.context.value.state.taskState == TaskState.EXECUTION ||
                saga.context.value.state.taskState == TaskState.DONE
        )
    }

    @Test
    fun `cancelTask sets outcome CANCELLED`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "P",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )
        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

        saga.cancelTask()
        advanceUntilIdle()

        assertEquals(TaskState.DONE, saga.context.value.state.taskState)
        assertEquals(TaskOutcome.CANCELLED, saga.context.value.runtimeState.outcome)
    }

    @Test
    fun `runStage should handle invalid JSON as FAILED`() = runTest {
        val localRepo = FakeLocalRepository()
        val chatRepo = FakeChatRepository()
        val memoryManager = ChatMemoryManager()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = Agent(
            id = "a1",
            name = "A",
            systemPrompt = "P",
            temperature = 0.7,
            provider = LLMProvider.DeepSeek(),
            stopWord = "",
            maxTokens = 2000
        )

        // Переключаем в EXECUTION, где JSON обязателен
        val pr = PlanResult("T", listOf("s"), "c")
        val context = TaskContext(
            taskId = "t1",
            title = "T",
            state = AgentTaskState(TaskState.EXECUTION, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            plan = pr.steps,
            runtimeState = TaskRuntimeState.defaultFor("t1").copy(stage = TaskState.EXECUTION, planResult = pr)
        )
        localRepo.saveAgent(agent)
        localRepo.saveTask(context)
        val saga = TaskSaga(chatRepo, localRepo, agent, agent, agent, context, memoryManager, testDispatcher)

        repeat(4) { chatRepo.responseQueue.add("This is not JSON at all") }
        saga.start()
        advanceUntilIdle()

        assertEquals(TaskState.DONE, saga.context.value.state.taskState)
        assertEquals(TaskOutcome.FAILED, saga.context.value.runtimeState.outcome)
    }
}
