@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagPipelineSettingsRepository
import com.example.ai_develop.data.stripLeadingJsonColonLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Автономный агент — координатор состояния и жизненного цикла.
 * Делегирует исполнение AgentEngine.
 */
open class AutonomousAgent(
    val agentId: String,
    private val repository: ChatRepository,
    private val engine: AgentEngine,
    externalScope: CoroutineScope,
    private val taskIdForMessagePersistence: String? = null,
    private val ragContextRetriever: RagContextRetriever? = null,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository? = null,
) {
    private val job = SupervisorJob(externalScope.coroutineContext[Job])
    private val scope = CoroutineScope(externalScope.coroutineContext.minusKey(Job) + job)

    private val _agent = MutableStateFlow<Agent?>(null)
    open val agent: StateFlow<Agent?> = _agent.asStateFlow()

    private val _stateMachine = MutableStateFlow<AgentStateMachine?>(null)

    private val _partialResponse = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val partialResponse: SharedFlow<String> = _partialResponse.asSharedFlow()

    private val _isProcessing = MutableStateFlow(false)
    open val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _agentActivity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    open val agentActivity: StateFlow<AgentActivity> = _agentActivity.asStateFlow()

    private val processingMutex = Mutex()
    private var stateSyncJob: Job? = null

    init {
        loadAndSubscribe()
    }

    private fun loadAndSubscribe() {
        stateSyncJob?.cancel()
        stateSyncJob = scope.launch {
            refreshAgent()
            repository.observeAgentState(agentId).collect { state ->
                if (state != null) updateSnapshot(state)
            }
        }
    }

    open suspend fun refreshAgent() {
        val state = repository.getAgentState(agentId) ?: AgentState(
            agentId = agentId,
            name = if (agentId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент"
        ).also { repository.saveAgentState(it) }
        
        updateSnapshot(state)
    }

    private suspend fun updateSnapshot(state: AgentState) {
        val profile = repository.getProfile(agentId)
        _stateMachine.value = AgentStateMachine(state)

        val messages = resolveMessagesSnapshot(state)

        _agent.update {
            Agent(
                id = state.agentId,
                name = state.name,
                systemPrompt = state.systemPrompt,
                temperature = state.temperature,
                provider = state.provider,
                stopWord = state.stopWord,
                maxTokens = state.maxTokens,
                userProfile = profile,
                memoryStrategy = state.memoryStrategy,
                workingMemory = state.workingMemory,
                messages = messages,
                ragEnabled = state.ragEnabled,
            )
        }
    }

    /**
     * [repository.observeAgentState] иногда отдаёт пустой [AgentState.messages] при гонке с сохранением
     * (или кратковременно несогласованный снимок), хотя в БД история уже есть — после перезапуска она видна.
     * Прямой [getAgentState] в этом случае обычно возвращает актуальный список.
     *
     * Пока идёт обработка ответа, снимок из БД может отставать (ещё не сохранили промежуточные шаги) —
     * не откатываем более длинную локальную историю.
     */
    private suspend fun resolveMessagesSnapshot(state: AgentState): List<ChatMessage> {
        val local = _agent.value?.messages.orEmpty()
        val fromDb = repository.getAgentState(agentId)?.messages.orEmpty()
        return mergeObserveMessages(_isProcessing.value, local, state, fromDb)
    }

    suspend fun transitionTo(nextStage: AgentStage): Result<AgentState> {
        val fsm = _stateMachine.value ?: return Result.failure(IllegalStateException("Agent not initialized"))
        val result = fsm.transitionTo(nextStage)
        if (result.isSuccess) {
            val currentAgent = _agent.value ?: return result
            syncWithRepository(currentAgent)
        }
        return result
    }

    fun sendMessage(text: String): Flow<String> = flow {
        val fsm = _stateMachine.filterNotNull().first()
        
        try {
            // 1. Добавляем пользовательское сообщение под локом, чтобы гарантировать порядок
            processingMutex.withLock {
                val currentAgent = _agent.value ?: return@flow
                val msg = createMessage(
                    "user",
                    text,
                    currentAgent.messages.lastOrNull()?.id,
                    fsm.getCurrentState().currentStage
                )
                _agent.update { it?.copy(messages = it.messages + msg) }
                _isProcessing.value = true
                _agentActivity.value = AgentActivity.Working
            }

            _agent.value?.let { syncWithRepository(it) }

            // 2. Берем snapshot для текущей итерации LLM
            var agentSnapshot = _agent.value ?: return@flow
            
            // 3. Первая итерация LLM
            var responseText = executeStreamingStep(this, agentSnapshot, fsm.getCurrentState().currentStage)
            _agent.value?.let { syncWithRepository(it) }

            val batchCalls = engine.parseAllToolCalls(responseText)
            val suppressOnlyBatch =
                batchCalls.isNotEmpty() && batchCalls.all { engine.toolSuppressesLlmFollowUp(it.toolName) }

            if (suppressOnlyBatch) {
                runSuppressOnlyToolSequence(
                    calls = batchCalls.take(MAX_TOOL_CHAIN_ITERATIONS),
                    fsm = fsm,
                    rawModelResponse = responseText
                )
                // После любого полностью MCP-батча — ещё один раунд LLM с историей с результатами инструментов.
                // Иначе при 2+ [TOOL:] в первом ответе (например два запроса котировок) второй раунд не вызывался,
                // и графики/файл не появлялись без отдельного «продолжай».
                val snap = _agent.value ?: return@flow
                val continuationText = executeStreamingStep(
                    this,
                    snap,
                    fsm.getCurrentState().currentStage,
                    replaceLastAssistant = false
                )
                _agent.value?.let { syncWithRepository(it) }
                runToolChainLoop(this, fsm, continuationText)
            } else {
                runToolChainLoop(this, fsm, responseText)
            }

            // 5. Финализация стейта
            val finalAgent = _agent.value ?: return@flow
            syncWithRepository(finalAgent)
            
            // 6. Фоновое обслуживание
            val updatedMemory = engine.performMaintenance(finalAgent)
            if (updatedMemory != finalAgent.workingMemory) {
                processingMutex.withLock {
                    _agent.update { it?.copy(workingMemory = updatedMemory) }
                }
                syncWithRepository(_agent.value!!)
            }

        } catch (e: Exception) {
            val err = "Error: ${e.message}"
            _partialResponse.emit(err)
            emit(err)
        } finally {
            _isProcessing.value = false
            _agentActivity.value = AgentActivity.Idle
        }
    }

    /**
     * Цепочка «инструмент(ы) из ответа → при необходимости LLM → снова инструменты».
     * [suppressLlmFollowUp] не обрывает цикл: после MCP без следующего [TOOL:] в том же ответе всё равно вызывается LLM,
     * чтобы довести многошаговый запрос (графики, файл) без отдельного «продолжай».
     */
    private suspend fun runToolChainLoop(
        collector: FlowCollector<String>,
        fsm: AgentStateMachine,
        initialResponseText: String,
    ) {
        var rawToolSourceText = initialResponseText
        var toolCall = engine.parseToolCall(rawToolSourceText)
        var iterations = 0
        val visitedToolCalls = mutableSetOf<String>()
        var lastMergedAssistantBody: String? = null
        var mergedBodyBeforeLlmFollowUp: String? = null

        while (toolCall != null && iterations < MAX_TOOL_CHAIN_ITERATIONS) {
            val callKey = "${toolCall.toolName}\u0000${toolCall.input.trim()}"
            if (!visitedToolCalls.add(callKey)) {
                processingMutex.withLock {
                    _agent.update { agent ->
                        val msgs = agent?.messages ?: return@update agent
                        val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                            ?: return@update agent
                        val cleaned = engine.stripToolSyntaxFromAssistantText(lastAssistant.message)
                        val trimmed = cleaned.trim()
                        if (trimmed.isEmpty()) {
                            val fallback = mergedBodyBeforeLlmFollowUp?.trim().orEmpty()
                                .ifEmpty { lastMergedAssistantBody?.trim().orEmpty() }
                            if (fallback.isNotEmpty()) {
                                val newTokens = estimateTokens(fallback)
                                agent.copy(
                                    messages = msgs.map { msg ->
                                        if (msg.id == lastAssistant.id) {
                                            msg.copy(message = fallback, tokensUsed = newTokens)
                                        } else msg
                                    }
                                )
                            } else {
                                agent.copy(messages = msgs.filter { it.id != lastAssistant.id })
                            }
                        } else {
                            val newTokens = estimateTokens(trimmed)
                            agent.copy(
                                messages = msgs.map { msg ->
                                    if (msg.id == lastAssistant.id) {
                                        msg.copy(message = trimmed, tokensUsed = newTokens)
                                    } else msg
                                }
                            )
                        }
                    }
                }
                _agent.value?.let { syncWithRepository(it) }
                break
            }

            _agentActivity.value = AgentActivity.RunningTool(toolCall.toolName)
            var toolExecutionFailed = false
            val toolResultText: String = try {
                engine.executeToolCall(toolCall) ?: run {
                    toolExecutionFailed = true
                    val names = engine.registeredToolNames()
                    val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                    "Tool error: unknown tool «${toolCall.toolName}». Registered: $hint"
                }
            } catch (e: Exception) {
                toolExecutionFailed = true
                "Tool error: ${e.message ?: e::class.simpleName}"
            }
            _agentActivity.value = AgentActivity.Working

            processingMutex.withLock {
                _agent.update { agent ->
                    val msgs = agent?.messages ?: return@update agent
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    if (lastAssistant == null) {
                        val fallback = createMessage(
                            "system",
                            "Tool Result: ${stripLeadingJsonColonLabel(toolResultText)}",
                            msgs.lastOrNull()?.id,
                            fsm.getCurrentState().currentStage
                        )
                        return@update agent.copy(messages = msgs + fallback)
                    }
                    val block = engine.formatMergedAssistantWithToolResult(
                        strippedPreamble = "",
                        toolName = toolCall.toolName,
                        toolResult = toolResultText
                    )
                    val merged = lastMergedAssistantBody?.let { prev ->
                        "${prev.trimEnd()}\n\n$block"
                    } ?: block
                    lastMergedAssistantBody = merged
                    val newTokens = estimateTokens(merged)
                    val updated = msgs.map { msg ->
                        if (msg.id == lastAssistant.id) {
                            msg.copy(message = merged, tokensUsed = newTokens)
                        } else msg
                    }
                    agent.copy(messages = updated)
                }
            }
            _agent.value?.let { syncWithRepository(it) }

            rawToolSourceText = engine.stripFirstToolInvocation(rawToolSourceText)
            val nextToolInSameLlmResponse = engine.parseToolCall(rawToolSourceText.trim())
            if (nextToolInSameLlmResponse != null) {
                toolCall = nextToolInSameLlmResponse
                iterations++
                continue
            }

            if (toolExecutionFailed) {
                break
            }

            val agentSnapshot = _agent.value ?: break
            mergedBodyBeforeLlmFollowUp = lastMergedAssistantBody
            lastMergedAssistantBody = null
            val responseFromLlm = executeStreamingStep(
                collector,
                agentSnapshot,
                fsm.getCurrentState().currentStage,
                replaceLastAssistant = true
            )
            _agent.value?.let { syncWithRepository(it) }
            rawToolSourceText = responseFromLlm
            toolCall = engine.parseToolCall(rawToolSourceText)
            iterations++
        }
    }

    /**
     * Несколько инструментов с [AgentTool.suppressLlmFollowUp] (MCP) из одного ответа модели: выполняются по очереди,
     * результаты накапливаются в одном сообщении, без дополнительного вызова LLM.
     * Текст модели вне синтаксиса [TOOL:…] (краткое пояснение, подтверждение сохранения и т.д.) сохраняется.
     */
    private suspend fun runSuppressOnlyToolSequence(
        calls: List<ParsedToolCall>,
        fsm: AgentStateMachine,
        rawModelResponse: String,
    ) {
        val visitedToolCalls = mutableSetOf<String>()
        var cumulative = ""
        val stage = fsm.getCurrentState().currentStage
        val proseFromModel = engine.stripToolSyntaxFromAssistantText(rawModelResponse).trim()

        fun blocksWithProse(toolBlocks: String): String {
            return when {
                proseFromModel.isEmpty() -> toolBlocks
                toolBlocks.isEmpty() -> proseFromModel
                else -> "$proseFromModel\n\n$toolBlocks"
            }
        }

        for (toolCall in calls) {
            val callKey = "${toolCall.toolName}\u0000${toolCall.input.trim()}"
            if (!visitedToolCalls.add(callKey)) continue

            _agentActivity.value = AgentActivity.RunningTool(toolCall.toolName)
            var toolExecutionFailed = false
            val toolResultText: String = try {
                engine.executeToolCall(toolCall) ?: run {
                    toolExecutionFailed = true
                    val names = engine.registeredToolNames()
                    val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                    "Tool error: unknown tool «${toolCall.toolName}». Registered: $hint"
                }
            } catch (e: Exception) {
                toolExecutionFailed = true
                "Tool error: ${e.message ?: e::class.simpleName}"
            }
            _agentActivity.value = AgentActivity.Working

            val block = engine.formatMergedAssistantWithToolResult(
                strippedPreamble = "",
                toolName = toolCall.toolName,
                toolResult = toolResultText
            )
            cumulative = if (cumulative.isEmpty()) block else "$cumulative\n\n$block"

            processingMutex.withLock {
                _agent.update { agent ->
                    val msgs = agent?.messages ?: return@update agent
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    if (lastAssistant == null) {
                        val fallback = createMessage(
                            "system",
                            blocksWithProse(cumulative),
                            msgs.lastOrNull()?.id,
                            stage
                        )
                        return@update agent.copy(messages = msgs + fallback)
                    }
                    val display = blocksWithProse(cumulative)
                    val newTokens = estimateTokens(display)
                    val updated = msgs.map { msg ->
                        if (msg.id == lastAssistant.id) {
                            msg.copy(message = display, tokensUsed = newTokens)
                        } else msg
                    }
                    agent.copy(messages = updated)
                }
            }
            _agent.value?.let { syncWithRepository(it) }

            if (toolExecutionFailed) break
        }
    }

    /**
     * Первое сообщение ассистента без пользовательского ввода: краткое приветствие (пустой чат).
     */
    fun sendWelcomeMessage(): Flow<String> = flow {
        val fsm = _stateMachine.filterNotNull().first()
        try {
            processingMutex.withLock {
                val currentAgent = _agent.value ?: return@flow
                if (currentAgent.messages.isNotEmpty()) return@flow
                _isProcessing.value = true
                _agentActivity.value = AgentActivity.Working
            }

            val stage = fsm.getCurrentState().currentStage
            val agentSnapshot = _agent.value ?: return@flow
            val prepared = engine.prepareChatRequest(agentSnapshot, stage, isJsonMode = false)
            val welcomePrompt = prepared.systemPrompt + WELCOME_SYSTEM_SUFFIX
            val preparedWelcome = prepared.copy(
                systemPrompt = welcomePrompt,
                snapshot = prepared.snapshot.copy(effectiveSystemPrompt = welcomePrompt)
            )
            executeStreamingStepWithPrepared(this, agentSnapshot, stage, preparedWelcome)
            _agent.value?.let { syncWithRepository(it) }
        } catch (e: Exception) {
            val err = "Error: ${e.message}"
            _partialResponse.emit(err)
            emit(err)
        } finally {
            _isProcessing.value = false
            _agentActivity.value = AgentActivity.Idle
        }
    }

    private suspend fun executeStreamingStep(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        replaceLastAssistant: Boolean = false
    ): String = executeStreamingStepWithPrepared(
        collector,
        agent,
        stage,
        prepareLlmRequestWithOptionalRag(agent, stage),
        replaceLastAssistant
    )

    private suspend fun prepareLlmRequestWithOptionalRag(agent: Agent, stage: AgentStage): PreparedLlmRequest {
        if (!agent.ragEnabled) {
            return engine.prepareChatRequest(agent, stage, isJsonMode = false)
        }
        val last = agent.messages.lastOrNull()
        if (last == null || last.role.lowercase() != "user") {
            return engine.prepareChatRequest(agent, stage, isJsonMode = false)
        }
        val query = last.message.trim()
        if (query.isEmpty()) {
            return engine.prepareChatRequest(
                agent,
                stage,
                isJsonMode = false,
                ragContext = null,
                ragAttribution = RagAttribution(used = false),
            )
        }
        val config = runCatching { ragPipelineSettingsRepository?.getConfig() }.getOrNull()
            ?: RagRetrievalConfig.Default
        var retrievalQuery = query
        var rewriteApplied = false
        if (config.queryRewriteEnabled) {
            val rewriteProvider = resolveRewriteProvider(agent, config)
            repository.rewriteQueryForRag(query, rewriteProvider).onSuccess { rw ->
                if (rw.isNotBlank()) {
                    retrievalQuery = rw.trim()
                    rewriteApplied = true
                }
            }
        }
        if (retrievalQuery.isBlank()) retrievalQuery = query

        val retrieved = ragContextRetriever?.retrieve(
            originalQuery = query,
            retrievalQuery = retrievalQuery,
            config = config,
            rewriteApplied = rewriteApplied,
        )
        return if (retrieved != null) {
            engine.prepareChatRequest(
                agent,
                stage,
                isJsonMode = false,
                ragContext = if (retrieved.attribution.used) retrieved.contextText else null,
                ragAttribution = retrieved.attribution,
            )
        } else {
            engine.prepareChatRequest(
                agent,
                stage,
                isJsonMode = false,
                ragContext = null,
                ragAttribution = RagAttribution(used = false),
            )
        }
    }

    private fun resolveRewriteProvider(agent: Agent, config: RagRetrievalConfig): LLMProvider {
        val m = config.rewriteOllamaModel.trim()
        val p = agent.provider
        return if (p is LLMProvider.Ollama && m.isNotEmpty()) p.copy(model = m) else p
    }

    private suspend fun executeStreamingStepWithPrepared(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        prepared: PreparedLlmRequest,
        replaceLastAssistant: Boolean = false
    ): String {
        val sb = StringBuilder()
        _agentActivity.value = AgentActivity.Streaming
        try {
            engine.streamFromPrepared(agent, prepared).collect { chunk ->
                currentCoroutineContext().ensureActive()
                sb.append(chunk)
                _partialResponse.emit(chunk)
                collector.emit(chunk)
            }
        } catch (e: Exception) {
            _partialResponse.emit("Error during streaming: ${e.message}")
            throw e
        } finally {
            _agentActivity.value = AgentActivity.Working
        }

        processingMutex.withLock {
            val msgs = _agent.value?.messages ?: return@withLock
            val content = stripLeadingJsonColonLabel(sb.toString())
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
            if (replaceLastAssistant && lastAssistant != null) {
                val newTokens = estimateTokens(content)
                val updated = msgs.map { msg ->
                    if (msg.id == lastAssistant.id) {
                        msg.copy(
                            message = content,
                            tokensUsed = newTokens,
                            llmRequestSnapshot = prepared.snapshot
                        )
                    } else msg
                }
                _agent.update { it?.copy(messages = updated) }
            } else {
                val parentId = msgs.lastOrNull()?.id
                val aiMsg = createMessage(
                    "assistant",
                    content,
                    parentId,
                    stage,
                    prepared.snapshot
                )
                _agent.update { it?.copy(messages = it.messages + aiMsg) }
            }
        }

        return sb.toString()
    }

    private companion object {
        /** Максимум раундов «инструмент → ответ LLM» за одно пользовательское сообщение (защита от бесконечного цикла). */
        private const val MAX_TOOL_CHAIN_ITERATIONS = 32

        private const val WELCOME_SYSTEM_SUFFIX =
            "\n\n[ИНСТРУКЦИЯ] Пользователь ещё не начал диалог. Кратко поприветствуй его и предложи начать обсуждение задачи. Ответь одним коротким сообщением."
    }

    private fun createMessage(
        role: String,
        content: String,
        parentId: String?,
        agentStage: AgentStage,
        llmSnapshot: LlmRequestSnapshot? = null
    ) = ChatMessage(
        id = Uuid.random().toString(),
        role = role,
        message = content,
        timestamp = System.currentTimeMillis(),
        source = when (role.lowercase()) {
            "user" -> SourceType.USER
            "assistant" -> SourceType.AI
            "system" -> SourceType.SYSTEM
            else -> SourceType.AI
        },
        parentId = parentId,
        taskId = taskIdForMessagePersistence,
        taskState = taskIdForMessagePersistence?.let { agentStageToTaskState(agentStage) },
        llmRequestSnapshot = llmSnapshot
    )

    private fun agentStageToTaskState(stage: AgentStage): TaskState = when (stage) {
        AgentStage.PLANNING -> TaskState.PLANNING
        AgentStage.EXECUTION -> TaskState.EXECUTION
        AgentStage.REVIEW -> TaskState.VERIFICATION
        AgentStage.DONE -> TaskState.DONE
    }

    private suspend fun syncWithRepository(agent: Agent) {
        val fsm = _stateMachine.value ?: return
        repository.saveAgentState(
            fsm.getCurrentState().copy(
                name = agent.name,
                systemPrompt = agent.systemPrompt,
                temperature = agent.temperature,
                provider = agent.provider,
                maxTokens = agent.maxTokens,
                stopWord = agent.stopWord,
                messages = agent.messages,
                workingMemory = agent.workingMemory,
                memoryStrategy = agent.memoryStrategy,
                ragEnabled = agent.ragEnabled,
            )
        )
    }

    fun dispose() {
        stateSyncJob?.cancel()
        job.cancel()
    }
}
