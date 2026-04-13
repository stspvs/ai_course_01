@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

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
    private val taskIdForMessagePersistence: String? = null
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
                messages = messages
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
            
            // 4. Tool Calling Loop — дедуп по (имя инструмента + вход), а не по тексту результата:
            // один и тот же запрос к API может вернуть слегка разный текст, из‑за чего раньше
            // показывалось несколько одинаковых «Tool Result» подряд.
            var toolCall = engine.parseToolCall(responseText)
            var iterations = 0
            val visitedToolCalls = mutableSetOf<String>()

            while (toolCall != null && iterations < MAX_TOOL_CHAIN_ITERATIONS) {
                val callKey = "${toolCall.toolName}\u0000${toolCall.input.trim()}"
                if (!visitedToolCalls.add(callKey)) break

                _agentActivity.value = AgentActivity.RunningTool(toolCall.toolName)
                val toolResultText: String = try {
                    engine.executeToolCall(toolCall) ?: run {
                        val names = engine.registeredToolNames()
                        val hint = if (names.isNotEmpty()) names.joinToString(", ") else "none"
                        "Tool error: unknown tool «${toolCall.toolName}». Registered: $hint"
                    }
                } catch (e: Exception) {
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
                        // Ответ пользователю даёт инструмент, не модель — текст до вызова не сохраняем.
                        val merged = engine.formatMergedAssistantWithToolResult(
                            strippedPreamble = "",
                            toolName = toolCall.toolName,
                            toolResult = toolResultText
                        )
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

                if (engine.toolSuppressesLlmFollowUp(toolCall.toolName)) {
                    break
                }

                agentSnapshot = _agent.value ?: break
                responseText = executeStreamingStep(this, agentSnapshot, fsm.getCurrentState().currentStage)
                _agent.value?.let { syncWithRepository(it) }
                toolCall = engine.parseToolCall(responseText)
                iterations++
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
        stage: AgentStage
    ): String = executeStreamingStepWithPrepared(
        collector,
        agent,
        stage,
        engine.prepareChatRequest(agent, stage, isJsonMode = false)
    )

    private suspend fun executeStreamingStepWithPrepared(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        prepared: PreparedLlmRequest
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
            val parentId = _agent.value?.messages?.lastOrNull()?.id
            val aiMsg = createMessage(
                "assistant",
                stripLeadingJsonColonLabel(sb.toString()),
                parentId,
                stage,
                prepared.snapshot
            )
            _agent.update { it?.copy(messages = it.messages + aiMsg) }
        }

        return sb.toString()
    }

    private companion object {
        /** Максимум раундов «инструмент → ответ LLM» за одно пользовательское сообщение (защита от бесконечного цикла). */
        private const val MAX_TOOL_CHAIN_ITERATIONS = 16

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
                memoryStrategy = agent.memoryStrategy
            )
        )
    }

    fun dispose() {
        stateSyncJob?.cancel()
        job.cancel()
    }
}
