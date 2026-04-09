package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

private const val DEFAULT_LLM_TIMEOUT_MS = 120_000L

/**
 * Оркестратор этапов задачи (PLANNING → EXECUTION → VERIFICATION → DONE).
 * Слои FSM vs лента чата: [TaskSagaDomainLayer].
 */
@OptIn(ExperimentalUuidApi::class)
class TaskSaga(
    private val repository: ChatRepository,
    private val localRepository: LocalChatRepository,
    private var architect: Agent?,
    private var executor: Agent?,
    private var validator: Agent?,
    initialContext: TaskContext,
    private val memoryManager: ChatMemoryManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    providedScope: CoroutineScope? = null
) {
    private val _context = MutableStateFlow(initialContext)
    val context: StateFlow<TaskContext> = _context.asStateFlow()

    private val ownedSupervisorJob = if (providedScope == null) SupervisorJob() else null
    private val scope = providedScope ?: CoroutineScope(dispatcher + ownedSupervisorJob!!)
    private val json = Json { ignoreUnknownKeys = true }

    private var isStageRunning = false
    private val stageMutex = Mutex()

    /**
     * Очередь тиков FSM: один потребитель вызывает [processCurrentState], чтобы не гоняться
     * с синхронной эмиссией [LocalChatRepository.saveTask] и несколькими входами ([start], collect getTasks, сообщение пользователя).
     */
    private val sagaTickChannel = Channel<Unit>(Channel.UNLIMITED)

    /**
     * Last task snapshot that arrived via [LocalChatRepository.getTasks] emissions.
     * [updateStateInDb] assigns [_context] before [LocalChatRepository.saveTask], so comparing
     * "old" state against [_context] in the collector misses PLANNING→EXECUTION transitions.
     */
    private var lastTaskStateFromFlow: TaskState? = null
    private var lastPausedFromFlow: Boolean? = null

    private class APIException(val error: Throwable) : Exception(error.message)

    init {
        scope.launch {
            for (ignored in sagaTickChannel) {
                processCurrentState()
            }
        }

        scope.launch {
            localRepository.getTasks()
                .map { tasks -> tasks.find { it.taskId == initialContext.taskId } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { updatedContext ->
                    val wasPaused = lastPausedFromFlow ?: _context.value.isPaused
                    val oldState = lastTaskStateFromFlow ?: _context.value.state.taskState
                    lastTaskStateFromFlow = updatedContext.state.taskState
                    lastPausedFromFlow = updatedContext.isPaused
                    _context.value = updatedContext

                    val becameUnpaused = wasPaused && !updatedContext.isPaused
                    val stateChangedWhileRunning = !updatedContext.isPaused && oldState != updatedContext.state.taskState

                    if (becameUnpaused || stateChangedWhileRunning) {
                        scheduleProcessCurrentState()
                    }
                }
        }

        scope.launch {
            localRepository.getAgents().collect { allAgents ->
                val newArchitect = allAgents.find { it.id == _context.value.architectAgentId }
                if (newArchitect != null) architect = newArchitect

                val newExecutor = allAgents.find { it.id == _context.value.executorAgentId }
                if (newExecutor != null) executor = newExecutor

                val newValidator = allAgents.find { it.id == _context.value.validatorAgentId }
                if (newValidator != null) validator = newValidator
            }
        }
    }

    private fun getValidAgentId(): String? {
        val ctx = _context.value
        return architect?.id
            ?: executor?.id
            ?: validator?.id
            ?: ctx.architectAgentId
            ?: ctx.executorAgentId
            ?: ctx.validatorAgentId
    }

    private fun getRoleForState(state: TaskState): TaskRole {
        return when (state) {
            TaskState.PLANNING -> ArchitectRole()
            TaskState.EXECUTION -> ExecutorRole()
            TaskState.VERIFICATION -> ValidatorRole()
            TaskState.DONE -> throw IllegalStateException("No role for DONE state")
        }
    }

    fun start() {
        if (!_context.value.isPaused) {
            scheduleProcessCurrentState()
        }
    }

    fun stop() {}

    /** Подставляет лимиты итераций/LLM из сохранённой задачи в память саги (см. [TaskSagaCoordinator.applyRuntimeLimitsAfterTaskSaved]). */
    fun applyRuntimeLimitsFrom(source: TaskContext) {
        if (source.taskId != _context.value.taskId) return
        val cur = _context.value
        val src = source.runtimeState
        val merged = cur.runtimeState.copy(
            maxSteps = src.maxSteps,
            maxPlanningSteps = src.maxPlanningSteps,
            maxExecutionSteps = src.maxExecutionSteps,
            maxVerificationSteps = src.maxVerificationSteps
        )
        if (merged != cur.runtimeState) {
            _context.value = cur.copy(runtimeState = merged)
        }
    }

    /** Cancels the saga scope when this instance owns it (see [providedScope]). */
    fun dispose() {
        ownedSupervisorJob?.cancel()
        if (ownedSupervisorJob != null) {
            sagaTickChannel.close()
        }
    }

    private fun scheduleProcessCurrentState() {
        sagaTickChannel.trySend(Unit)
    }

    fun pause() {
        scope.launch {
            updateStateInDb { it.copy(isPaused = true) }
        }
    }

    fun resume() {
        scope.launch {
            if (!_context.value.isReadyToRun) {
                val missing = _context.value.missingAgents.joinToString(", ")
                handleStageError(null, Exception("Не назначены агенты: $missing"))
                return@launch
            }
            updateStateInDb { it.copy(isPaused = false) }
        }
    }

    fun reset() {
        scope.launch {
            localRepository.deleteMessagesForTask(_context.value.taskId)
            updateStateInDb {
                it.copy(
                    state = it.state.copy(taskState = TaskState.PLANNING),
                    step = 0,
                    plan = emptyList(),
                    planDone = emptyList(),
                    currentPlanStep = null,
                    isPaused = true,
                    runtimeState = TaskRuntimeState.resetProgressPreservingUserSettings(it.runtimeState)
                )
            }
        }
    }

    /**
     * Подтверждение плана пользователем: принудительное сжатие и переход PLANNING → EXECUTION.
     */
    fun confirmPlan() {
        scope.launch {
            val ctx = _context.value
            if (ctx.state.taskState != TaskState.PLANNING) return@launch
            if (!ctx.runtimeState.awaitingPlanConfirmation && ctx.runtimeState.planResult == null) return@launch
            val plan = ctx.runtimeState.planResult ?: return@launch
            if (!AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.EXECUTION)) return@launch
            if (AutonomousTaskStateMachine.isGlobalStepLimitExceeded(ctx.runtimeState)) {
                stageMutex.withLock { finishWithOutcome(TaskOutcome.FAILED) }
                return@launch
            }
            // Must not hold [stageMutex] here: forceCompress updates DB and triggers processCurrentState → same mutex.
            forceCompressPlanningContext()
            stageMutex.withLock {
                val ctx2 = _context.value
                val plan2 = ctx2.runtimeState.planResult ?: return@withLock
                if (ctx2.state.taskState != TaskState.PLANNING) return@withLock
                transitionToNextState(
                    sourceAgentId = ctx2.architectAgentId ?: return@withLock,
                    from = TaskState.PLANNING,
                    to = TaskState.EXECUTION,
                    planResult = plan2,
                    notification = "--- PLAN CONFIRMED ---\n${plan2.goal}"
                )
            }
        }
    }

    fun cancelTask() {
        scope.launch {
            stageMutex.withLock {
                val ctx = _context.value
                if (ctx.state.taskState == TaskState.DONE) return@withLock
                val next = TaskSagaReducer.finishCancelled(ctx) ?: return@withLock
                updateStateInDb { next }
                notifySystem("Задача отменена пользователем.", ctx.state.taskState)
            }
        }
    }

    private suspend fun updateStateInDb(block: (TaskContext) -> TaskContext) {
        val newState = block(_context.value).let { it.copy(runtimeState = it.runtimeState.copy(stage = it.state.taskState, taskId = it.taskId)) }
        _context.value = newState
        localRepository.saveTask(newState)
    }

    /** Последний persist задачи из [LocalChatRepository] — не расходится с коллектором [getTasks] и индексом шага. */
    private suspend fun resolvedTaskContext(): TaskContext {
        val id = _context.value.taskId
        return localRepository.getTasks().first().find { it.taskId == id } ?: _context.value
    }

    private fun processCurrentState() {
        val currentContext = _context.value
        if (currentContext.isPaused || currentContext.state.taskState == TaskState.DONE) return
        if (!currentContext.isReadyToRun) {
            pause()
            return
        }
        if (AutonomousTaskStateMachine.isGlobalStepLimitExceeded(currentContext.runtimeState)) {
            scope.launch { finishWithOutcome(TaskOutcome.FAILED) }
            return
        }

        val role = getRoleForState(currentContext.state.taskState)
        val agent = when (currentContext.state.taskState) {
            TaskState.PLANNING -> architect
            TaskState.EXECUTION -> executor
            TaskState.VERIFICATION -> validator
            TaskState.DONE -> null
        }

        when (currentContext.state.taskState) {
            TaskState.PLANNING -> runPlanningStage(agent, role as ArchitectRole)
            TaskState.EXECUTION -> runExecutionStage(agent, role as ExecutorRole)
            TaskState.VERIFICATION -> runVerificationStage(agent, role as ValidatorRole)
            TaskState.DONE -> {}
        }
    }

    private fun runPlanningStage(agent: Agent?, role: ArchitectRole) {
        if (agent == null) {
            scope.launch { handleStageError(null, Exception("Агент для этапа PLANNING не назначен")) }
            return
        }
        scope.launch {
            stageMutex.withLock {
                if (isStageRunning) return@launch
                isStageRunning = true
            }
            try {
                runPlanningStageLocked(agent, role)
            } catch (e: APIException) {
                handleStageError(agent, e.error)
            } catch (e: Exception) {
                handleStageError(agent, e)
            } finally {
                isStageRunning = false
            }
        }
    }

    /**
     * [LocalChatRepository.getAgents] обновляется только при изменении AgentState, а не при новых сообщениях,
     * поэтому [architect].messages устаревает. Для промпта планирования всегда берём актуальный поток задачи.
     */
    private suspend fun agentWithCurrentTaskMessages(base: Agent): Agent {
        val msgs = localRepository.getMessagesForTask(_context.value.taskId).first()
        return base.copy(messages = msgs)
    }

    private suspend fun runPlanningStageLocked(agent: Agent, role: ArchitectRole) {
        val currentContext = _context.value
        if (currentContext.isPaused) return

        val rs = currentContext.runtimeState
        if (rs.planningLlmCalls == 0) {
            notifyStageEntry(TaskState.PLANNING)
        }
        val newRs = rs.copy(planningLlmCalls = rs.planningLlmCalls + 1)
        updateStateInDb { it.copy(runtimeState = newRs) }

        if (AutonomousTaskStateMachine.shouldTimeoutPlanning(newRs)) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        maybeCompressPlanningDuringDialog(agent)

        val agentForLlm = agentWithCurrentTaskMessages(agent)
        val finalHistory = role.processHistory(agentForLlm, memoryManager)
        val systemInstruction = role.getSystemInstruction(currentContext)
        val fullSystemPrompt = role.buildSystemPrompt(agentForLlm, systemInstruction, memoryManager, includeUserProfile = true)

        val contextMessages = mutableListOf<ChatMessage>()
        memoryManager.getShortTermMemoryMessage(agentForLlm)?.let { contextMessages.add(it) }
        contextMessages.addAll(finalHistory)

        val llmSnapshot = buildLlmRequestSnapshot(
            effectiveSystemPrompt = fullSystemPrompt,
            inputMessages = contextMessages,
            agent = agentForLlm,
            agentStageLabel = currentContext.state.taskState.name,
            isJsonMode = role.isJsonMode()
        )

        var fullResponse = ""
        val timeoutMs = currentContext.runtimeState.maxPlanningSteps * 1000L + DEFAULT_LLM_TIMEOUT_MS
        try {
            withTimeout(timeoutMs.coerceAtMost(600_000L)) {
                repository.chatStreaming(
                    messages = contextMessages,
                    systemPrompt = fullSystemPrompt,
                    maxTokens = agent.maxTokens,
                    temperature = agent.temperature,
                    stopWord = agent.stopWord,
                    isJsonMode = role.isJsonMode(),
                    provider = agent.provider
                ).collect { result ->
                    result.onSuccess { chunk -> fullResponse += chunk }
                        .onFailure { error -> throw APIException(error) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        val trimmedResponse = fullResponse.trim()
        if (trimmedResponse.isBlank() || _context.value.isPaused) return

        val plannerOut = AutonomousTaskJsonParsers.parsePlannerOutput(trimmedResponse)
        val sagaResp = parseSagaResponse(trimmedResponse)

        val lastMsgId = agentForLlm.messages.lastOrNull()?.id
        val chatMessage = ChatMessage(
            message = trimmedResponse,
            role = "assistant",
            source = SourceType.AI,
            timestamp = System.currentTimeMillis(),
            taskId = currentContext.taskId,
            taskState = currentContext.state.taskState,
            parentId = lastMsgId,
            llmRequestSnapshot = llmSnapshot
        )
        localRepository.saveMessage(
            agentId = agent.id,
            message = chatMessage,
            taskId = currentContext.taskId,
            taskState = currentContext.state.taskState
        )

        when {
            plannerOut != null -> handlePlannerOutput(agent.id, plannerOut, trimmedResponse, sagaResp)
            sagaResp != null -> handleLegacyPlannerSaga(agent.id, sagaResp)
            else -> {
                if (role.handleResponse(trimmedResponse, sagaResp) is RoleResult.Partial) {
                    logVerbose("Planning partial (no JSON)")
                }
            }
        }
    }

    private fun resolvePlanFromPlannerOutput(ctx: TaskContext, out: PlannerOutput, raw: String, sagaResp: SagaResponse?): PlanResult {
        val base = when {
            out.plan != null -> out.plan
            sagaResp != null && sagaResp.status.equals("SUCCESS", ignoreCase = true) && sagaResp.result.isNotBlank() ->
                PlanResult(
                    goal = ctx.title,
                    steps = listOf(sagaResp.result.trim()),
                    successCriteria = "Satisfy the stated goal.",
                    constraints = null,
                    contextSummary = null
                )
            else -> AutonomousTaskJsonParsers.parsePlanResult(raw)
                ?: PlanResult(
                    goal = ctx.title,
                    steps = ctx.plan.ifEmpty { listOf(raw.take(500)) },
                    successCriteria = "Satisfy the stated goal.",
                    constraints = null,
                    contextSummary = null
                )
        }
        return AutonomousTaskJsonParsers.normalizePlanResult(base).expandNumberedSteps()
    }

    private suspend fun handlePlannerOutput(sourceAgentId: String, out: PlannerOutput, raw: String, sagaResp: SagaResponse?) {
        val ctx = _context.value
        if (out.questions?.isNotEmpty() == true) {
            updateStateInDb { TaskSagaReducer.incrementPlanningMessagesSinceCompress(it) }
            logVerbose("Planner has open questions")
            return
        }
        if (!out.success) {
            if (AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE)) {
                finishWithOutcome(TaskOutcome.FAILED)
            }
            return
        }
        val plan = resolvePlanFromPlannerOutput(ctx, out, raw, sagaResp)
        if (out.requiresUserConfirmation) {
            updateStateInDb { TaskSagaReducer.setPlanAwaitingUserConfirmation(it, plan) }
            logVerbose("Awaiting plan confirmation")
            return
        }
        forceCompressPlanningContext()
        transitionToNextState(
            sourceAgentId = sourceAgentId,
            from = TaskState.PLANNING,
            to = TaskState.EXECUTION,
            planResult = plan,
            notification = "--- PLAN READY ---\n${plan.goal}"
        )
    }

    private suspend fun handleLegacyPlannerSaga(sourceAgentId: String, sagaResp: SagaResponse) {
        when (sagaResp.status.uppercase()) {
            "FAILED" -> {
                if (AutonomousTaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE)) {
                    finishWithOutcome(TaskOutcome.FAILED)
                }
            }
            "SUCCESS" -> {
                val ctx = _context.value
                val plan = AutonomousTaskJsonParsers.normalizePlanResult(
                    PlanResult(
                        goal = ctx.title,
                        steps = ctx.plan.ifEmpty { listOf(sagaResp.result) },
                        successCriteria = sagaResp.result,
                        constraints = null,
                        contextSummary = null
                    )
                ).expandNumberedSteps()
                updateStateInDb { TaskSagaReducer.setPlanAwaitingUserConfirmation(it, plan) }
                logVerbose("Legacy SUCCESS — awaiting confirmPlan()")
            }
            else -> {}
        }
    }

    private fun runExecutionStage(agent: Agent?, role: ExecutorRole) {
        if (agent == null) {
            scope.launch { handleStageError(null, Exception("Агент для EXECUTION не назначен")) }
            return
        }
        scope.launch {
            stageMutex.withLock {
                if (isStageRunning) return@launch
                isStageRunning = true
            }
            try {
                runExecutionStageLocked(agent, role)
            } catch (e: APIException) {
                handleStageError(agent, e.error)
            } catch (e: Exception) {
                handleStageError(agent, e)
            } finally {
                isStageRunning = false
            }
        }
    }

    private suspend fun runExecutionStageLocked(agent: Agent, role: ExecutorRole) {
        val ctx = _context.value
        if (ctx.isPaused) return
        val agentForLlm = agentWithCurrentTaskMessages(agent)
        val rs = ctx.runtimeState
        val newRs = rs.copy(executionLlmCalls = rs.executionLlmCalls + 1, executionRetryCount = rs.executionRetryCount)
        updateStateInDb { it.copy(runtimeState = newRs) }

        if (AutonomousTaskStateMachine.shouldTimeoutExecution(newRs)) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        // После updateStateInDb: индекс шага и план из последнего persist (иначе залипание на шаге 0 из-за рассинхрона _context).
        val snap = resolvedTaskContext()
        val plan = snap.runtimeState.planResult ?: PlanResult(
            goal = snap.title,
            steps = snap.plan,
            successCriteria = "",
            constraints = null,
            contextSummary = null
        )
        val stepIndex = snap.runtimeState.currentPlanStepIndex.coerceIn(0, (plan.steps.size - 1).coerceAtLeast(0))

        val systemInstruction = role.getSystemInstruction(snap)
        val fullSystemPrompt = role.buildSystemPrompt(
            agentForLlm,
            systemInstruction,
            memoryManager,
            includeUserProfile = false,
            includeAgentWorkingMemoryInSystem = false
        )

        // lastVerification / lastExecution / workingMemory — из _context: снимок из Flow/БД может отставать
        // и приходить без только что записанного вердикта инспектора после провала верификации.
        val liveRs = _context.value.runtimeState
        val userContent = TaskOrchestratorPrompts.executorUserContent(
            plan = plan,
            stepIndex = stepIndex,
            lastVerification = liveRs.lastVerification,
            workingMemory = liveRs.workingMemory,
            lastExecution = liveRs.lastExecution
        )
        val contextMessages = listOf(
            ChatMessage(
                id = Uuid.random().toString(),
                role = "user",
                message = userContent,
                timestamp = System.currentTimeMillis(),
                taskId = snap.taskId,
                taskState = TaskState.EXECUTION
            )
        )

        val llmSnapshot = buildLlmRequestSnapshot(
            effectiveSystemPrompt = fullSystemPrompt,
            inputMessages = contextMessages,
            agent = agentForLlm,
            agentStageLabel = TaskState.EXECUTION.name,
            isJsonMode = true
        )

        var fullResponse = ""
        try {
            withTimeout(DEFAULT_LLM_TIMEOUT_MS) {
                repository.chatStreaming(
                    messages = contextMessages,
                    systemPrompt = fullSystemPrompt,
                    maxTokens = agent.maxTokens,
                    temperature = agent.temperature,
                    stopWord = agent.stopWord,
                    isJsonMode = true,
                    provider = agent.provider
                ).collect { result ->
                    result.onSuccess { chunk -> fullResponse += chunk }
                        .onFailure { throw APIException(it) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        if (fullResponse.isBlank() || _context.value.isPaused) return

        val execResult = AutonomousTaskJsonParsers.parseExecutionResult(fullResponse)
            ?: run {
                val retry = _context.value.runtimeState.executionRetryCount
                val maxRetries = _context.value.runtimeState.maxRetries
                if (retry < maxRetries) {
                    updateStateInDb {
                        it.copy(runtimeState = it.runtimeState.copy(executionRetryCount = retry + 1))
                    }
                    runExecutionStageLocked(agent, role)
                } else {
                    if (AutonomousTaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.DONE)) {
                        finishWithOutcome(TaskOutcome.FAILED)
                    }
                }
                return
            }

        val lastMsgId = agentForLlm.messages.lastOrNull()?.id
        localRepository.saveMessage(
            agentId = agent.id,
            message = ChatMessage(
                message = fullResponse,
                role = "assistant",
                source = SourceType.AI,
                timestamp = System.currentTimeMillis(),
                taskId = snap.taskId,
                taskState = TaskState.EXECUTION,
                parentId = lastMsgId,
                llmRequestSnapshot = llmSnapshot
            ),
            taskId = snap.taskId,
            taskState = TaskState.EXECUTION
        )

        updateStateInDb {
            it.copy(
                runtimeState = it.runtimeState.copy(
                    lastExecution = execResult,
                    executionRetryCount = if (execResult.success) 0 else it.runtimeState.executionRetryCount,
                    workingMemory = listOfNotNull(it.runtimeState.workingMemory, execResult.output).joinToString("\n").take(8000)
                )
            )
        }

        if (!execResult.success) {
            val retry = _context.value.runtimeState.executionRetryCount
            if (retry < _context.value.runtimeState.maxRetries) {
                updateStateInDb { it.copy(runtimeState = it.runtimeState.copy(executionRetryCount = retry + 1)) }
                runExecutionStageLocked(agent, role)
                return
            }
            if (AutonomousTaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.DONE)) {
                finishWithOutcome(TaskOutcome.FAILED)
            }
            return
        }

        transitionToNextState(
            sourceAgentId = agent.id,
            from = TaskState.EXECUTION,
            to = TaskState.VERIFICATION,
            planResult = plan,
            notification = "--- EXECUTION OK ---\n${execResult.output}"
        )
    }

    private fun runVerificationStage(agent: Agent?, role: ValidatorRole) {
        if (agent == null) {
            scope.launch { handleStageError(null, Exception("Агент для VERIFICATION не назначен")) }
            return
        }
        scope.launch {
            stageMutex.withLock {
                if (isStageRunning) return@launch
                isStageRunning = true
            }
            try {
                runVerificationStageLocked(agent, role)
            } catch (e: APIException) {
                handleStageError(agent, e.error)
            } catch (e: Exception) {
                handleStageError(agent, e)
            } finally {
                isStageRunning = false
            }
        }
    }

    private suspend fun runVerificationStageLocked(agent: Agent, role: ValidatorRole) {
        val ctx = _context.value
        if (ctx.isPaused) return
        val agentForLlm = agentWithCurrentTaskMessages(agent)
        val rs = ctx.runtimeState
        val newRs = rs.copy(verificationLlmCalls = rs.verificationLlmCalls + 1, verificationRetryCount = rs.verificationRetryCount)
        updateStateInDb { it.copy(runtimeState = newRs) }

        if (AutonomousTaskStateMachine.shouldTimeoutVerification(newRs)) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        val systemInstruction = role.getSystemInstruction(ctx)
        val fullSystemPrompt = role.buildSystemPrompt(
            agentForLlm,
            systemInstruction,
            memoryManager,
            includeUserProfile = false,
            includeAgentWorkingMemoryInSystem = false
        )

        // Снимок из persist: план, lastExecution, lastVerification (ответ исполнителя — только в lastExecution).
        val snap = resolvedTaskContext()
        val planForInspector = snap.runtimeState.planResult ?: PlanResult(
            goal = snap.title,
            steps = snap.plan,
            successCriteria = "",
            constraints = null,
            contextSummary = null
        )
        val execForInspector = snap.runtimeState.lastExecution
            ?: ExecutionResult(false, "", listOf("No execution"))

        val verifyStepIndex = snap.runtimeState.currentPlanStepIndex.coerceIn(
            0,
            (planForInspector.steps.size - 1).coerceAtLeast(0)
        )

        val userContent = TaskOrchestratorPrompts.inspectorUserContent(
            plan = planForInspector,
            stepIndex = verifyStepIndex,
            execution = execForInspector,
            successCriteria = planForInspector.successCriteria,
            lastVerification = snap.runtimeState.lastVerification
        )
        val contextMessages = listOf(
            ChatMessage(
                id = Uuid.random().toString(),
                role = "user",
                message = userContent,
                timestamp = System.currentTimeMillis(),
                taskId = ctx.taskId,
                taskState = TaskState.VERIFICATION
            )
        )

        val llmSnapshot = buildLlmRequestSnapshot(
            effectiveSystemPrompt = fullSystemPrompt,
            inputMessages = contextMessages,
            agent = agentForLlm,
            agentStageLabel = TaskState.VERIFICATION.name,
            isJsonMode = true
        )

        var fullResponse = ""
        try {
            withTimeout(DEFAULT_LLM_TIMEOUT_MS) {
                repository.chatStreaming(
                    messages = contextMessages,
                    systemPrompt = fullSystemPrompt,
                    maxTokens = agent.maxTokens,
                    temperature = agent.temperature,
                    stopWord = agent.stopWord,
                    isJsonMode = true,
                    provider = agent.provider
                ).collect { result ->
                    result.onSuccess { chunk -> fullResponse += chunk }
                        .onFailure { throw APIException(it) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            finishWithOutcome(TaskOutcome.TIMEOUT)
            return
        }

        if (fullResponse.isBlank() || _context.value.isPaused) return

        val verResult = AutonomousTaskJsonParsers.parseVerificationResult(fullResponse)
            ?: run {
                val retry = _context.value.runtimeState.verificationRetryCount
                val maxRetries = _context.value.runtimeState.maxRetries
                if (retry < maxRetries) {
                    updateStateInDb {
                        it.copy(runtimeState = it.runtimeState.copy(verificationRetryCount = retry + 1))
                    }
                    runVerificationStageLocked(agent, role)
                } else {
                    finishWithOutcome(TaskOutcome.FAILED)
                }
                return
            }

        val lastMsgId = agentForLlm.messages.lastOrNull()?.id
        localRepository.saveMessage(
            agentId = agent.id,
            message = ChatMessage(
                message = fullResponse,
                role = "assistant",
                source = SourceType.AI,
                timestamp = System.currentTimeMillis(),
                taskId = ctx.taskId,
                taskState = TaskState.VERIFICATION,
                parentId = lastMsgId,
                llmRequestSnapshot = llmSnapshot
            ),
            taskId = ctx.taskId,
            taskState = TaskState.VERIFICATION
        )

        updateStateInDb {
            it.copy(
                runtimeState = it.runtimeState.copy(
                    lastVerification = verResult,
                    verificationRetryCount = if (verResult.success) 0 else it.runtimeState.verificationRetryCount
                )
            )
        }

        val postVerify = resolvedTaskContext()
        val stepIndex = postVerify.runtimeState.currentPlanStepIndex
        val planForStepDecision = postVerify.runtimeState.planResult ?: PlanResult(
            goal = postVerify.title,
            steps = postVerify.plan,
            successCriteria = "",
            constraints = null,
            contextSummary = null
        )
        val lastIndex = planForStepDecision.steps.lastIndex.coerceAtLeast(0)

        if (verResult.success) {
            if (stepIndex >= lastIndex) {
                if (AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.DONE)) {
                    val successNext = TaskSagaReducer.verificationSuccessTerminal(_context.value) ?: return
                    updateStateInDb { successNext }
                    notifySystem("--- TASK SUCCESS ---", TaskState.VERIFICATION)
                }
            } else {
                val nextIndex = stepIndex + 1
                if (AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION)) {
                    val nextStepText = planForStepDecision.steps.getOrNull(nextIndex)
                    notifyStageEntry(TaskState.EXECUTION)
                    notifySystem("--- NEXT STEP ---\n$nextStepText", TaskState.EXECUTION)
                    // До persist снимаем блокировку: иначе collect(processCurrentState) запускает EXECUTION,
                    // пока isStageRunning ещё true, launch отменяется; затем явный processCurrentState()
                    // дублирует запуск после снятия блокировки → второй LLM-вызов исполнителя на том же шаге.
                    isStageRunning = false
                    val execNext = TaskSagaReducer.verificationSuccessNextStep(_context.value, nextIndex, nextStepText) ?: return
                    updateStateInDb { execNext }
                }
            }
        } else {
            val retry = _context.value.runtimeState.verificationRetryCount
            if (retry < _context.value.runtimeState.maxRetries) {
                updateStateInDb { it.copy(runtimeState = it.runtimeState.copy(verificationRetryCount = retry + 1)) }
                if (AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION)) {
                    notifyStageEntry(TaskState.EXECUTION)
                    isStageRunning = false
                    val failNext = TaskSagaReducer.verificationFailToExecution(
                        _context.value,
                        _context.value.runtimeState.verificationRetryCount
                    ) ?: return
                    updateStateInDb { failNext }
                }
            } else {
                if (AutonomousTaskStateMachine.canTransition(TaskState.VERIFICATION, TaskState.EXECUTION)) {
                    notifyStageEntry(TaskState.EXECUTION)
                    isStageRunning = false
                    val failNext = TaskSagaReducer.verificationFailToExecution(
                        _context.value,
                        _context.value.runtimeState.verificationRetryCount
                    ) ?: return
                    updateStateInDb { failNext }
                }
            }
        }
    }

    /**
     * Порядок эффектов при смене этапа (регрессия: баннер до persist — см. [TaskSagaStageOrderingTest]):
     * 1. Системное сообщение о переходе ([notification], привязано к этапу [from])
     * 2. [notifyStageEntry] для этапа [to]
     * 3. [updateStateInDb] с [TaskSagaReducer.stageTransition]
     */
    private suspend fun transitionToNextState(
        sourceAgentId: String,
        from: TaskState,
        to: TaskState,
        planResult: PlanResult?,
        notification: String
    ) {
        // Снимаем блокировку до persist, иначе collect { processCurrentState } пропустит следующую стадию
        isStageRunning = false
        if (!AutonomousTaskStateMachine.canTransition(from, to)) return
        val currentAgent = when (from) {
            TaskState.PLANNING -> architect
            TaskState.EXECUTION -> executor
            TaskState.VERIFICATION -> validator
            else -> null
        }
        val lastMsgId = currentAgent?.let { agentWithCurrentTaskMessages(it).messages.lastOrNull()?.id }
        localRepository.saveMessage(
            agentId = sourceAgentId,
            message = ChatMessage(
                message = notification,
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = from,
                isSystemNotification = true,
                parentId = lastMsgId
            ),
            taskId = _context.value.taskId,
            taskState = from
        )

        // До persist: иначе saveTask синхронно эмитит getTasks → processCurrentState() и LLM
        // следующего этапа стартует раньше, чем появится «▶ Начинается этап…» в ленте.
        notifyStageEntry(to)

        val next = TaskSagaReducer.stageTransition(_context.value, from, to, planResult) ?: return
        updateStateInDb { next }
    }

    private suspend fun finishWithOutcome(outcome: TaskOutcome) {
        isStageRunning = false
        val ctx = _context.value
        val next = TaskSagaReducer.finishOutcome(ctx, outcome) ?: return
        updateStateInDb { next }
        notifySystem("--- TASK END: $outcome ---", ctx.state.taskState)
    }

    private suspend fun notifySystem(text: String, taskState: TaskState) {
        val agentId = getValidAgentId() ?: return
        localRepository.saveMessage(
            agentId = agentId,
            message = ChatMessage(
                message = text,
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = taskState,
                isSystemNotification = true
            ),
            taskId = _context.value.taskId,
            taskState = taskState
        )
    }

    /**
     * Системное сообщение о входе в этап (видно в общей ленте задачи).
     * [taskState] — этап, который начинается (метка пузыря в UI).
     */
    private suspend fun notifyStageEntry(stage: TaskState) {
        val text = when (stage) {
            TaskState.PLANNING -> "▶ Начинается этап: планирование (архитектор)"
            TaskState.EXECUTION -> "▶ Начинается этап: исполнение (исполнитель)"
            TaskState.VERIFICATION -> "▶ Начинается этап: проверка (инспектор)"
            TaskState.DONE -> "▶ Задача завершена"
        }
        val taskId = _context.value.taskId
        val lastId = localRepository.getMessagesForTask(taskId).first().lastOrNull()?.id
        val agentId = getValidAgentId() ?: return
        localRepository.saveMessage(
            agentId = agentId,
            message = ChatMessage(
                message = text,
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = taskId,
                taskState = stage,
                isSystemNotification = true,
                parentId = lastId
            ),
            taskId = taskId,
            taskState = stage
        )
    }

    private fun logVerbose(msg: String) {
        if (_context.value.runtimeState.verbose) {
            println("[TaskSaga] ${_context.value.taskId}: $msg")
        }
    }

    private suspend fun maybeCompressPlanningDuringDialog(agent: Agent) {
        val ctx = _context.value
        val rs = ctx.runtimeState
        if (!rs.autoCompress) return
        if (rs.planningMessagesSinceCompress < rs.compressAfterMessages) return
        forceCompressPlanningContext()
        updateStateInDb {
            it.copy(runtimeState = it.runtimeState.copy(planningMessagesSinceCompress = 0))
        }
    }

    private suspend fun forceCompressPlanningContext() {
        val architectAgent = architect ?: return
        val withTaskMessages = agentWithCurrentTaskMessages(architectAgent)
        val summaryInstr = (withTaskMessages.memoryStrategy as? ChatMemoryStrategy.Summarization)?.summaryPrompt
            ?: "Summarize the planning conversation: goals, constraints, and agreed steps briefly."
        val result = repository.summarize(
            messages = withTaskMessages.messages,
            previousSummary = (withTaskMessages.memoryStrategy as? ChatMemoryStrategy.Summarization)?.summary,
            instruction = summaryInstr,
            provider = withTaskMessages.provider
        )
        val summary = result.getOrNull() ?: return
        val updated = when (val s = withTaskMessages.memoryStrategy) {
            is ChatMemoryStrategy.Summarization -> withTaskMessages.copy(memoryStrategy = s.copy(summary = summary))
            else -> withTaskMessages.copy(memoryStrategy = ChatMemoryStrategy.Summarization(windowSize = 50, summary = summary))
        }
        localRepository.saveAgentMetadata(updated)
        updateStateInDb {
            it.copy(
                runtimeState = it.runtimeState.copy(
                    workingMemory = listOfNotNull(it.runtimeState.workingMemory, summary).joinToString("\n").take(8000)
                )
            )
        }
    }

    private suspend fun handleStageError(agent: Agent?, error: Throwable) {
        updateStateInDb { it.copy(isPaused = true) }
        val currentContext = _context.value
        val agentId = agent?.id ?: getValidAgentId() ?: return
        val lastMsgId = agent?.messages?.lastOrNull()?.id

        val agentName = agent?.name ?: when (agentId) {
            currentContext.architectAgentId -> "Architect"
            currentContext.executorAgentId -> "Executor"
            currentContext.validatorAgentId -> "Validator"
            else -> null
        }

        val taskId = currentContext.taskId
        val taskState = currentContext.state.taskState

        val prefix = if (agentName != null) "❌ Ошибка API ($agentName): " else "⚠️ "
        localRepository.saveMessage(
            agentId = agentId,
            message = ChatMessage(
                message = "$prefix${error.message ?: "Неизвестная ошибка"}",
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = taskId,
                taskState = taskState,
                isSystemNotification = true,
                parentId = lastMsgId
            ),
            taskId = taskId,
            taskState = taskState
        )
    }

    fun handleUserMessage(message: String) {
        if (_context.value.state.taskState != TaskState.PLANNING) return
        val activeAgent = architect ?: return

        scope.launch {
            val lastMessage = localRepository.getMessagesForTask(_context.value.taskId).first().lastOrNull()

            val chatMessage = ChatMessage(
                message = message,
                role = "user",
                source = SourceType.USER,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = TaskState.PLANNING,
                parentId = lastMessage?.id
            )
            localRepository.saveMessage(
                agentId = activeAgent.id,
                message = chatMessage,
                taskId = _context.value.taskId,
                taskState = TaskState.PLANNING
            )

            updateStateInDb {
                it.copy(
                    runtimeState = it.runtimeState.copy(
                        planningMessagesSinceCompress = it.runtimeState.planningMessagesSinceCompress + 1
                    )
                )
            }

            if (!_context.value.isPaused) {
                scheduleProcessCurrentState()
            }
        }
    }

    private fun parseSagaResponse(text: String): SagaResponse? {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start != -1 && end != -1) {
                val jsonStr = text.substring(start, end + 1)
                json.decodeFromString<SagaResponse>(jsonStr)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

