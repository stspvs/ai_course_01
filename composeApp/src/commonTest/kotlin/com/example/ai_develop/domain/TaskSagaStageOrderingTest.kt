package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Регрессия: системное «▶ Начинается этап…» должно сохраняться **до** [LocalChatRepository.saveTask],
 * иначе синхронная эмиссия getTasks() запускает следующий LLM раньше баннера в ленте.
 */
class TaskSagaStageOrderingTest {

    private sealed class LedgerEvent {
        data class Message(val text: String, val taskState: TaskState?) : LedgerEvent()
        data class Task(val taskState: TaskState) : LedgerEvent()
    }

    private class RecordingLocalRepository : LocalChatRepository {
        val ledger = mutableListOf<LedgerEvent>()
        private val _tasks = MutableStateFlow<List<TaskContext>>(emptyList())
        private val _agents = MutableStateFlow<List<Agent>>(emptyList())
        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun getAgents(): Flow<List<Agent>> = _agents.asStateFlow()
        override fun getAgentWithMessages(agentId: String): Flow<Agent?> =
            _agents.map { list -> list.find { it.id == agentId } }

        override suspend fun saveAgent(agent: Agent): Result<Unit> {
            _agents.value = _agents.value.filterNot { it.id == agent.id } + agent
            return Result.success(Unit)
        }

        override suspend fun saveAgentMetadata(agent: Agent): Result<Unit> = saveAgent(agent)

        override suspend fun saveMessage(
            agentId: String,
            message: ChatMessage,
            taskId: String?,
            taskState: TaskState?
        ): Result<Unit> {
            ledger.add(LedgerEvent.Message(message.message, taskState))
            _messages.value = _messages.value + message.copy(taskId = taskId, taskState = taskState)
            return Result.success(Unit)
        }

        override suspend fun deleteAgent(agentId: String) {
            _agents.value = _agents.value.filterNot { it.id == agentId }
        }

        override fun getTasks(): Flow<List<TaskContext>> = _tasks.asStateFlow()

        override suspend fun saveTask(task: TaskContext): Result<Unit> {
            ledger.add(LedgerEvent.Task(task.state.taskState))
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId } + task
            return Result.success(Unit)
        }

        override suspend fun deleteTask(task: TaskContext): Result<Unit> {
            _tasks.value = _tasks.value.filterNot { it.taskId == task.taskId }
            return Result.success(Unit)
        }

        override fun getMessagesForTask(taskId: String): Flow<List<ChatMessage>> =
            _messages.map { list -> list.filter { it.taskId == taskId } }

        override suspend fun deleteMessagesForTask(taskId: String): Result<Unit> {
            _messages.value = _messages.value.filterNot { it.taskId == taskId }
            return Result.success(Unit)
        }
    }

    private class QueueChatRepository : ChatRepository {
        val systemPrompts = mutableListOf<String>()
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
            val text = responseQueue.removeFirstOrNull()
                ?: error("responseQueue empty (expected ${systemPrompts.size} responses)")
            return kotlinx.coroutines.flow.flowOf(Result.success(text))
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
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> =
            kotlinx.coroutines.flow.flowOf(null)
    }

    /**
     * Как [QueueChatRepository], но сохраняет аргументы [ChatRepository.chatStreaming] для проверки промпта инспектора.
     */
    private class RecordingQueueChatRepository : ChatRepository {
        val chatStreamingMessages = mutableListOf<List<ChatMessage>>()
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
            chatStreamingMessages.add(messages)
            val text = responseQueue.removeFirstOrNull()
                ?: error("responseQueue empty (expected ${chatStreamingMessages.size} responses)")
            return kotlinx.coroutines.flow.flowOf(Result.success(text))
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
        override suspend fun getAgentState(agentId: String): AgentState? = null
        override suspend fun getProfile(agentId: String): UserProfile? = null
        override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
        override suspend fun getInvariants(agentId: String, stage: AgentStage) = emptyList<Invariant>()
        override suspend fun saveInvariant(invariant: Invariant) {}
        override fun observeAgentState(agentId: String): Flow<AgentState?> =
            kotlinx.coroutines.flow.flowOf(null)
    }

    private val executionResultJson = Json { ignoreUnknownKeys = true }

    /** [TaskOrchestratorPrompts.inspectorUserContent] кладёт JSON сюда — извлекаем поле output. */
    private fun executionOutputEmbeddedInInspectorPrompt(userContent: String): String {
        val marker = "=== EXECUTION RESULT (executor deliverable for this step, JSON) ==="
        check(userContent.contains(marker)) { "ожидался промпт инспектора с маркером EXECUTION RESULT" }
        val tail = userContent.substringAfter(marker).trim()
        val jsonLine = tail.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: error("нет JSON после EXECUTION RESULT")
        return executionResultJson.decodeFromString(ExecutionResult.serializer(), jsonLine.trim()).output
    }

    /** Все вызовы LLM, где пользовательский промпт — для инспектора (есть блок последнего исполнения). */
    private fun inspectorExecutionOutputsFromRecordedCalls(repo: RecordingQueueChatRepository): List<String> =
        repo.chatStreamingMessages.mapNotNull { msgs ->
            val u = msgs.firstOrNull()?.message ?: return@mapNotNull null
            if (!u.contains("=== EXECUTION RESULT (executor deliverable for this step, JSON) ===")) return@mapNotNull null
            executionOutputEmbeddedInInspectorPrompt(u)
        }

    private fun stageEntryMarker(to: TaskState): String = when (to) {
        TaskState.PLANNING -> "▶ Начинается этап: планирование"
        TaskState.PLAN_VERIFICATION -> "проверка плана"
        TaskState.EXECUTION -> "▶ Начинается этап: исполнение"
        TaskState.VERIFICATION -> "▶ Начинается этап: проверка"
        TaskState.DONE -> "▶ Задача завершена"
    }

    /**
     * Между двумя подряд [LedgerEvent.Task] с разным [TaskState] в окне (prevTaskIdx .. currentIdx)
     * должен быть хотя бы один [LedgerEvent.Message] с маркером входа в целевой этап.
     * Переходы в DONE не требуют того же баннера (завершение через notifySystem).
     */
    private fun stageEntryInvariantHolds(events: List<LedgerEvent>): Boolean {
        var lastTaskIdx = -1
        var lastTaskState: TaskState? = null
        for (i in events.indices) {
            when (val e = events[i]) {
                is LedgerEvent.Task -> {
                    if (lastTaskState != null && e.taskState != lastTaskState && e.taskState != TaskState.DONE) {
                        val marker = stageEntryMarker(e.taskState)
                        val window = (lastTaskIdx + 1) until i
                        val hasEntry = window.any { j ->
                            events[j] is LedgerEvent.Message &&
                                (events[j] as LedgerEvent.Message).text.contains(marker)
                        }
                        if (!hasEntry) return false
                    }
                    lastTaskState = e.taskState
                    lastTaskIdx = i
                }
                else -> {}
            }
        }
        return true
    }

    private fun assertStageEntryBeforeTaskPersistOnStateChange(events: List<LedgerEvent>) {
        assertTrue(
            stageEntryInvariantHolds(events),
            "Ожидался баннер входа до persist задачи. Фрагмент журнала: $events"
        )
    }

    /** Синтетический корректный журнал: перед каждым сменой этапа в persist — сообщение с маркером [stageEntryMarker]. */
    private fun syntheticValidLedger(taskPersistSequence: List<TaskState>): List<LedgerEvent> {
        val out = ArrayList<LedgerEvent>(taskPersistSequence.size * 2)
        var lastTaskState: TaskState? = null
        for (s in taskPersistSequence) {
            if (lastTaskState != null && s != lastTaskState && s != TaskState.DONE) {
                out.add(LedgerEvent.Message(stageEntryMarker(s), s))
            }
            out.add(LedgerEvent.Task(s))
            lastTaskState = s
        }
        return out
    }

    @Test
    fun `unit - parseExecutionResult accepts synthetic step2 json`() {
        val s = """{"success":true,"output":"EXEC_OUTPUT_STEP_INDEX_1","errors":null}"""
        assertNotNull(AutonomousTaskJsonParsers.parseExecutionResult(s))
    }

    private fun baseAgent() = Agent(
        id = "a1",
        name = "A",
        systemPrompt = "p",
        temperature = 0.7,
        provider = LLMProvider.DeepSeek(),
        stopWord = "",
        maxTokens = 2000
    )

    @Test
    fun `core - PLANNING to EXECUTION to VERIFICATION stage entry precedes task persist`() = runTest {
        val local = RecordingLocalRepository()
        val chat = QueueChatRepository()
        val memoryManager = ChatMemoryManager()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = baseAgent()
        local.saveAgent(agent)
        val context = TaskContext(
            taskId = "t_order",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            isStarted = true
        )
        local.saveTask(context)

        val planJson =
            """{"success":true,"plan":{"goal":"g","steps":["one"],"successCriteria":"c"},"questions":[],"requiresUserConfirmation":false}"""
        val planVerJson = """{"success":true,"issues":null,"suggestions":null}"""
        val execJson = """{"success":true,"output":"done","errors":null}"""
        val verJson = """{"success":true,"issues":null,"suggestions":null}"""
        chat.responseQueue.add(planJson)
        chat.responseQueue.add(planVerJson)
        chat.responseQueue.add(execJson)
        chat.responseQueue.add(verJson)

        val saga = TaskSaga(chat, local, agent, agent, agent, context, memoryManager, dispatcher)
        saga.start()
        advanceUntilIdle()

        assertTrue(local.ledger.any { it is LedgerEvent.Message && (it as LedgerEvent.Message).text.contains("проверка") })
        assertTrue(local.ledger.any { it is LedgerEvent.Message && (it as LedgerEvent.Message).text.contains("проверка плана") })
        assertStageEntryBeforeTaskPersistOnStateChange(local.ledger)
    }

    @Test
    fun `corner - verification fails then EXECUTION banner before persist`() = runTest {
        val local = RecordingLocalRepository()
        val chat = QueueChatRepository()
        val memoryManager = ChatMemoryManager()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = baseAgent()
        local.saveAgent(agent)
        val plan = PlanResult("g", listOf("s1"), "c")
        val ctx = TaskContext(
            taskId = "t_fail",
            title = "T",
            state = AgentTaskState(TaskState.EXECUTION, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            isStarted = true,
            plan = plan.steps,
            runtimeState = TaskRuntimeState.defaultFor("t_fail").copy(
                stage = TaskState.EXECUTION,
                planResult = plan,
                lastExecution = ExecutionResult(true, "out", emptyList())
            )
        )
        local.saveTask(ctx)

        val execOk = """{"success":true,"output":"x","errors":null}"""
        val verFail = """{"success":false,"issues":["block"],"suggestions":[]}"""
        chat.responseQueue.add(execOk)
        chat.responseQueue.add(verFail)
        chat.responseQueue.add(execOk)

        val saga = TaskSaga(chat, local, agent, agent, agent, ctx, memoryManager, dispatcher)
        saga.start()
        advanceUntilIdle()

        assertStageEntryBeforeTaskPersistOnStateChange(local.ledger)
        assertTrue(local.ledger.any { it is LedgerEvent.Message && (it as LedgerEvent.Message).text.contains("исполнение") })
    }

    @Test
    fun `unit - synthetic valid ledger satisfies stage entry invariant`() {
        val ledger = syntheticValidLedger(
            listOf(
                TaskState.PLANNING,
                TaskState.PLANNING,
                TaskState.PLAN_VERIFICATION,
                TaskState.EXECUTION,
                TaskState.VERIFICATION,
                TaskState.EXECUTION,
                TaskState.VERIFICATION,
                TaskState.DONE
            )
        )
        assertTrue(stageEntryInvariantHolds(ledger))
    }

    @Test
    fun `unit - synthetic invalid ledger persist before banner fails invariant`() {
        val bad = listOf(
            LedgerEvent.Task(TaskState.PLANNING),
            LedgerEvent.Task(TaskState.EXECUTION),
            LedgerEvent.Message("▶ Начинается этап: исполнение (исполнитель)", TaskState.EXECUTION)
        )
        assertFalse(stageEntryInvariantHolds(bad), "Регрессия: saveTask до notifyStageEntry")
    }

    @Test
    fun `stress - many synthetic transition chains satisfy invariant`() {
        val pattern = listOf(
            TaskState.PLANNING,
            TaskState.PLAN_VERIFICATION,
            TaskState.EXECUTION,
            TaskState.VERIFICATION,
            TaskState.EXECUTION,
            TaskState.VERIFICATION,
            TaskState.DONE
        )
        repeat(5_000) { i ->
            val chain = buildList {
                add(TaskState.PLANNING)
                repeat(3 + (i % 7)) {
                    addAll(pattern.drop(1))
                }
            }
            assertTrue(stageEntryInvariantHolds(syntheticValidLedger(chain)), "iteration $i")
        }
    }

    @Test
    fun `stress - repeated single-step integration runs preserve ordering`() = runTest {
        val planJson =
            """{"success":true,"plan":{"goal":"g","steps":["one"],"successCriteria":"c"},"questions":[],"requiresUserConfirmation":false}"""
        val planVerJson = """{"success":true,"issues":null,"suggestions":null}"""
        val execJson = """{"success":true,"output":"done","errors":null}"""
        val verJson = """{"success":true,"issues":null,"suggestions":null}"""
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val memoryManager = ChatMemoryManager()
        val agent = baseAgent()
        repeat(32) { run ->
            val local = RecordingLocalRepository()
            val chat = QueueChatRepository()
            local.saveAgent(agent)
            val ctx = TaskContext(
                taskId = "t_stress_$run",
                title = "T",
                state = AgentTaskState(TaskState.PLANNING, agent),
                architectAgentId = "a1",
                executorAgentId = "a1",
                validatorAgentId = "a1",
                isPaused = false,
                isStarted = true
            )
            local.saveTask(ctx)
            chat.responseQueue.add(planJson)
            chat.responseQueue.add(planVerJson)
            chat.responseQueue.add(execJson)
            chat.responseQueue.add(verJson)
            val saga = TaskSaga(chat, local, agent, agent, agent, ctx, memoryManager, dispatcher)
            saga.start()
            advanceUntilIdle()
            assertTrue(chat.responseQueue.isEmpty(), "run=$run осталось ответов: ${chat.responseQueue.size}")
            assertEquals(TaskState.DONE, saga.context.value.state.taskState, "run=$run")
            assertStageEntryBeforeTaskPersistOnStateChange(local.ledger)
        }
    }

    @Test
    fun `regression - inspector LLM prompt embeds output of the execution that just finished - single step`() = runTest {
        val local = RecordingLocalRepository()
        val chat = RecordingQueueChatRepository()
        val memoryManager = ChatMemoryManager()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = baseAgent()
        local.saveAgent(agent)
        val planJson =
            """{"success":true,"plan":{"goal":"g","steps":["only"],"successCriteria":"c"},"questions":[],"requiresUserConfirmation":false}"""
        val execMarker = "LAST_EXEC_MARKER_SINGLE"
        val execJson = """{"success":true,"output":"$execMarker","errors":null}"""
        val planVerJson = """{"success":true,"issues":null,"suggestions":null}"""
        val verJson = """{"success":true,"issues":null,"suggestions":null}"""
        chat.responseQueue.add(planJson)
        chat.responseQueue.add(planVerJson)
        chat.responseQueue.add(execJson)
        chat.responseQueue.add(verJson)

        val ctx = TaskContext(
            taskId = "t_insp_single",
            title = "T",
            state = AgentTaskState(TaskState.PLANNING, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            isStarted = true
        )
        local.saveTask(ctx)

        val saga = TaskSaga(chat, local, agent, agent, agent, ctx, memoryManager, dispatcher)
        saga.start()
        advanceUntilIdle()

        val inspectorOutputs = inspectorExecutionOutputsFromRecordedCalls(chat)
        assertEquals(listOf(execMarker), inspectorOutputs, "инспектор должен видеть результат только что завершившегося исполнения")
    }

    @Test
    fun `regression - each verification sees last execution from current step not from earlier steps`() = runTest {
        val local = RecordingLocalRepository()
        val chat = RecordingQueueChatRepository()
        val memoryManager = ChatMemoryManager()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = baseAgent()
        local.saveAgent(agent)
        val out0 = "EXEC_OUTPUT_STEP_INDEX_0"
        val out1 = "EXEC_OUTPUT_STEP_INDEX_1"
        val exec0 = """{"success":true,"output":"$out0","errors":null}"""
        val verOk = """{"success":true,"issues":null,"suggestions":null}"""
        val exec1 = """{"success":true,"output":"$out1","errors":null}"""
        chat.responseQueue.add(exec0)
        chat.responseQueue.add(verOk)
        chat.responseQueue.add(exec1)
        chat.responseQueue.add(verOk)

        val plan = PlanResult("g", listOf("step_a", "step_b"), "crit")
        val ctx = TaskContext(
            taskId = "t_insp_chain",
            title = "T",
            state = AgentTaskState(TaskState.EXECUTION, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            isStarted = true,
            plan = plan.steps,
            runtimeState = TaskRuntimeState.defaultFor("t_insp_chain").copy(
                stage = TaskState.EXECUTION,
                planResult = plan,
                currentPlanStepIndex = 0
            )
        )
        local.saveTask(ctx)

        val saga = TaskSaga(chat, local, agent, agent, agent, ctx, memoryManager, dispatcher)
        saga.start()
        advanceUntilIdle()

        assertTrue(chat.responseQueue.isEmpty(), "осталось в очереди: ${chat.responseQueue.joinToString()}")
        fun labelCall(msg: String): String = when {
            msg.contains("=== EXECUTION RESULT (executor deliverable for this step, JSON) ===") -> "inspector"
            msg.contains("=== CURRENT STEP INDEX ===") -> "executor"
            else -> "planning"
        }
        val callLabels = chat.chatStreamingMessages.map { m -> labelCall(m.firstOrNull()?.message.orEmpty()) }
        assertEquals(
            listOf("executor", "inspector", "executor", "inspector"),
            callLabels,
            "два шага: exec→ver→exec→ver; фактически: $callLabels"
        )

        val inspectorOutputs = inspectorExecutionOutputsFromRecordedCalls(chat)
        assertEquals(
            listOf(out0, out1),
            inspectorOutputs,
            "в блоке EXECUTION RESULT должен попадать output последнего завершённого исполнения шага"
        )
        assertEquals(TaskState.DONE, saga.context.value.state.taskState)
    }

    @Test
    fun `regression - after failed verification re-execution inspector sees new output not stale`() = runTest {
        val local = RecordingLocalRepository()
        val chat = RecordingQueueChatRepository()
        val memoryManager = ChatMemoryManager()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val agent = baseAgent()
        local.saveAgent(agent)
        val plan = PlanResult("g", listOf("s1"), "c")
        val ctx = TaskContext(
            taskId = "t_insp_retry",
            title = "T",
            state = AgentTaskState(TaskState.EXECUTION, agent),
            architectAgentId = "a1",
            executorAgentId = "a1",
            validatorAgentId = "a1",
            isPaused = false,
            isStarted = true,
            plan = plan.steps,
            runtimeState = TaskRuntimeState.defaultFor("t_insp_retry").copy(
                stage = TaskState.EXECUTION,
                planResult = plan,
                lastExecution = ExecutionResult(true, "stale_should_be_replaced", emptyList())
            )
        )
        local.saveTask(ctx)

        val firstExec = """{"success":true,"output":"BEFORE_RETRY","errors":null}"""
        val verFail = """{"success":false,"issues":["block"],"suggestions":[]}"""
        val secondExec = """{"success":true,"output":"AFTER_RETRY","errors":null}"""
        chat.responseQueue.add(firstExec)
        chat.responseQueue.add(verFail)
        chat.responseQueue.add(secondExec)
        chat.responseQueue.add("""{"success":true,"issues":null,"suggestions":null}""")

        val saga = TaskSaga(chat, local, agent, agent, agent, ctx, memoryManager, dispatcher)
        saga.start()
        advanceUntilIdle()

        val inspectorOutputs = inspectorExecutionOutputsFromRecordedCalls(chat)
        assertEquals(2, inspectorOutputs.size, "два захода в VERIFICATION")
        assertEquals("BEFORE_RETRY", inspectorOutputs[0])
        assertEquals(
            "AFTER_RETRY",
            inspectorOutputs[1],
            "повторная проверка должна брать результат последнего исполнения, а не первый/устаревший"
        )
    }
}
