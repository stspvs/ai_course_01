@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagPipelineSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Автономный агент — **оркестратор** жизненного цикла и сценариев чата.
 *
 * **Принципы (контракт класса):**
 * 1. Оркестратор, не реализация — RAG, стриминг, инструменты вынесены в коллабораторы.
 * 2. Один публичный снимок для UI — [uiState].
 * 3. Узкое горлышко входа — [resolveRuntimeSnapshot] перед запросами к LLM/RAG.
 * 4. Один режим инференса на шаг — [AgentInferenceStrategy] по [Agent.ragEnabled].
 * 5. RAG и tools — последовательные фазы; парсинг [TOOL:] после завершения раунда LLM.
 * 6. Стриминг и буфер — одна цепочка; [AutonomousAgentUiState.delivery] и [phaseHint].
 * 7. Телеметрия фаз — [AgentPhaseTimings] на assistant-сообщении; детали по тапу в UI.
 * 8. Не раздувать этот файл — новая логика в отдельных типах.
 *
 * @param agentId Идентификатор агента в хранилище (тот же id, что у чата в БД).
 * @param repository Репозиторий чата: состояние агента, сообщения, профиль.
 * @param engine Движок LLM: подготовка запроса, стриминг, инструменты, обслуживание памяти.
 * @param externalScope Внешняя область корутин; жизненный цикл агента привязан к ней (отмена при dispose).
 * @param taskIdForMessagePersistence Если задан (например id задачи), новые [ChatMessage] получают [ChatMessage.taskId] для связи с задачей.
 * @param ragContextRetriever Опционально: поиск контекста в RAG; без него RAG-стратегия не подтянет базу знаний.
 * @param ragPipelineSettingsRepository Опционально: настройки пайплайна RAG (rewrite и т.д.).
 * @param uiStateHub Внутренний изменяемый поток UI-состояния; наследники/тесты могут подставить свой экземпляр.
 */
open class AutonomousAgent(
    val agentId: String,
    private val repository: ChatRepository,
    private val engine: AgentEngine,
    externalScope: CoroutineScope,
    private val taskIdForMessagePersistence: String? = null,
    private val ragContextRetriever: RagContextRetriever? = null,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository? = null,
    protected val uiStateHub: MutableStateFlow<AutonomousAgentUiState> = MutableStateFlow(AutonomousAgentUiState()),
) {
    /** Дочерний job: при [dispose] отменяет все корутины агента. */
    private val job = SupervisorJob(externalScope.coroutineContext[Job])

    /** Область корутин агента: подписка на БД, отправка сообщений и т.д. */
    private val scope = CoroutineScope(externalScope.coroutineContext.minusKey(Job) + job)

    /**
     * Главный поток состояния для интерфейса: агент, флаги обработки, активность, превью стрима, подсказки фаз, ошибки, тайминги.
     * Имеет смысл подписываться на него вместо разрозненных [agent] / [isProcessing] / [agentActivity].
     */
    val uiState: StateFlow<AutonomousAgentUiState> = uiStateHub.asStateFlow()

    /** Зеркало [AutonomousAgentUiState.agent] для обратной совместимости и тестов. */
    private val _agentMirror = MutableStateFlow<Agent?>(uiStateHub.value.agent)

    /** Текущий агент с сообщениями и настройками; значения синхронизированы с [uiState]. */
    open val agent: StateFlow<Agent?> = _agentMirror.asStateFlow()

    /** Поток фрагментов ответа модели по мере генерации (для CLI и старых подписчиков). Превью в UI — [AutonomousAgentUiState.streamingPreview]. */
    private val _partialResponse = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val partialResponse: SharedFlow<String> = _partialResponse.asSharedFlow()

    /** Зеркало [AutonomousAgentUiState.isProcessing]. */
    private val _isProcessingMirror = MutableStateFlow(uiStateHub.value.isProcessing)

    /** Идёт ли сейчас обработка пользовательского сообщения или приветствия. */
    open val isProcessing: StateFlow<Boolean> = _isProcessingMirror.asStateFlow()

    /** Зеркало [AutonomousAgentUiState.agentActivity]. */
    private val _agentActivityMirror = MutableStateFlow(uiStateHub.value.agentActivity)

    /** Что делает агент сейчас: простой, стриминг, вызов инструмента и т.д. */
    open val agentActivity: StateFlow<AgentActivity> = _agentActivityMirror.asStateFlow()

    /** Конечный автомат по стадиям агента (планирование, исполнение…); обновляется из [AgentState] в БД. */
    private val _stateMachine = MutableStateFlow<AgentStateMachine?>(null)

    /** Сборка запроса к LLM с опциональным RAG (поиск, rewrite). */
    private val ragPreparer = RagAwareChatRequestPreparer(
        agentId,
        repository,
        engine,
        ragContextRetriever,
        ragPipelineSettingsRepository,
    )

    /** Режим «просто чат»: без retrieval. */
    private val directInferenceStrategy = DirectChatInferenceStrategy(engine)

    /** Режим с RAG: retrieval и подготовка запроса через [ragPreparer]. */
    private val ragInferenceStrategy = RagAugmentedInferenceStrategy(ragPreparer)

    /** Один шаг стриминга: чанки, буфер JSON для RAG, запись в сообщения. */
    private val streamHandler = LlmStreamingTurnHandler(engine)

    /** Цепочка [TOOL:…]: выполнение, слияние в ответ, следующий раунд LLM. */
    private val toolOrchestrator = ToolInvocationOrchestrator(engine, MAX_TOOL_CHAIN_ITERATIONS)

    /** Не даёт двум операциям одновременно менять сообщения и UI-состояние. */
    private val processingMutex = Mutex()

    /** Корутина подписки на изменения агента в БД. */
    private var stateSyncJob: Job? = null

    /** Стартует первую загрузку и дальше слушает [ChatRepository.observeAgentState]. */
    private val repositorySynchronizer = AgentRepositorySynchronizer(
        agentId,
        repository,
        scope,
        ::updateSnapshot,
    )

    init {
        loadAndSubscribe()
    }

    /**
     * Атомарно обновляет [uiStateHub] и подтягивает [agent], [isProcessing], [agentActivity] из нового снимка.
     */
    private fun patchUi(transform: (AutonomousAgentUiState) -> AutonomousAgentUiState) {
        uiStateHub.update(transform)
        syncMirrorsFromHub()
    }

    /**
     * Вызывать после ручного [uiStateHub.update] в тестах/фейках, чтобы зеркальные [StateFlow] не отставали от хаба.
     */
    protected fun syncMirrorsFromHub() {
        val s = uiStateHub.value
        _agentMirror.value = s.agent
        _isProcessingMirror.value = s.isProcessing
        _agentActivityMirror.value = s.agentActivity
    }

    /** Отменяет предыдущую подписку и заново: [refreshAgent], затем поток обновлений из БД. */
    private fun loadAndSubscribe() {
        stateSyncJob?.cancel()
        stateSyncJob = repositorySynchronizer.start(initialRefresh = { refreshAgent() })
    }

    /**
     * Подтягивает состояние агента из БД (или создаёт запись по умолчанию) и обновляет UI и FSM.
     * Полезно вызвать вручную после внешних изменений данных.
     */
    open suspend fun refreshAgent() {
        val state = repository.getAgentState(agentId) ?: AgentState(
            agentId = agentId,
            name = if (agentId == GENERAL_CHAT_ID) "Общий чат" else "Новый агент",
        ).also { repository.saveAgentState(it) }

        updateSnapshot(state)
    }

    /**
     * Пересобирает локального [Agent] из [state]: профиль, FSM, список сообщений с учётом merge при активной обработке.
     */
    private suspend fun updateSnapshot(state: AgentState) {
        val profile = repository.getProfile(agentId)
        _stateMachine.value = AgentStateMachine(state)

        val messages = resolveMessagesSnapshot(state)

        val newAgent = Agent(
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
            mcpAllowedBindingIds = state.mcpAllowedBindingIds,
        )
        patchUi { it.copy(agent = newAgent) }
    }

    /**
     * Согласует сообщения «в памяти» и из БД, чтобы при стриминге не потерять черновик и не показать устаревшее.
     */
    private suspend fun resolveMessagesSnapshot(state: AgentState): List<ChatMessage> {
        val local = uiStateHub.value.agent?.messages.orEmpty()
        val fromDb = repository.getAgentState(agentId)?.messages.orEmpty()
        return mergeObserveMessages(uiStateHub.value.isProcessing, local, state, fromDb)
    }

    /**
     * Единый снимок перед вызовом LLM/RAG: актуальный агент, стадия FSM и [Agent.ragEnabled] из БД (чтобы не расходилось с UI).
     */
    private suspend fun resolveRuntimeSnapshot(): AgentRuntimeSnapshot? {
        val ag = uiStateHub.value.agent ?: return null
        val fsm = _stateMachine.value ?: return null
        val stage = fsm.getCurrentState().currentStage
        val persistedRag = repository.getAgentState(agentId)?.ragEnabled
        return AgentRuntimeSnapshot(
            ag.copy(ragEnabled = persistedRag ?: ag.ragEnabled),
            stage,
        )
    }

    /**
     * Переводит агента на следующую стадию FSM и сохраняет изменения в репозитории при успехе.
     */
    suspend fun transitionTo(nextStage: AgentStage): Result<AgentState> {
        val fsm = _stateMachine.value ?: return Result.failure(IllegalStateException("Agent not initialized"))
        val result = fsm.transitionTo(nextStage)
        if (result.isSuccess) {
            val currentAgent = uiStateHub.value.agent ?: return result
            syncWithRepository(currentAgent)
        }
        return result
    }

    /**
     * Полный сценарий ответа на сообщение пользователя: запись user-сообщения, стриминг LLM (direct или RAG),
     * при необходимости инструменты и повторные раунды, тайминги на последнем ответе ассистента, обслуживание памяти.
     *
     * Эмитит те же текстовые чанки, что уходят в [partialResponse] (для подписчиков [Flow]).
     */
    fun sendMessage(text: String): Flow<String> = flow {
        val fsm = _stateMachine.filterNotNull().first()
        val timing = PhaseTimingCollector()

        try {
            processingMutex.withLock {
                val currentAgent = uiStateHub.value.agent ?: return@flow
                val msg = createMessage(
                    "user",
                    text,
                    currentAgent.messages.lastOrNull()?.id,
                    fsm.getCurrentState().currentStage,
                )
                patchUi {
                    it.copy(
                        agent = it.agent?.copy(messages = it.agent.messages + msg),
                        isProcessing = true,
                        agentActivity = AgentActivity.Working,
                        streamingPreview = "",
                        phaseHint = null,
                        delivery = AgentTextDelivery.Idle,
                        lastStreamError = null,
                        currentRunTimings = null,
                    )
                }
            }

            uiStateHub.value.agent?.let { syncWithRepository(it) }

            var agentSnapshot = uiStateHub.value.agent ?: return@flow

            val snapForLlm = resolveRuntimeSnapshot() ?: return@flow
            if (snapForLlm.agent.ragEnabled) {
                patchUi { it.copy(phaseHint = PhaseStatusHint.Rag()) }
            }
            patchUi { it.copy(phaseHint = PhaseStatusHint.AwaitingLlm) }

            var responseText = executeStreamingStep(
                this,
                agentSnapshot,
                fsm.getCurrentState().currentStage,
                timing,
            )
            patchUi { it.copy(phaseHint = null) }
            uiStateHub.value.agent?.let { syncWithRepository(it) }

            val batchCalls = engine.parseAllToolCalls(responseText)
            val agentForTools = uiStateHub.value.agent ?: return@flow
            val suppressOnlyBatch =
                batchCalls.isNotEmpty() && batchCalls.all { engine.toolSuppressesLlmFollowUp(agentForTools, it.toolName) }

            val toolCtx = toolInvocationContext(this, fsm, timing)

            if (suppressOnlyBatch) {
                toolOrchestrator.runSuppressOnlyToolSequence(
                    calls = batchCalls.take(MAX_TOOL_CHAIN_ITERATIONS),
                    fsm = fsm,
                    rawModelResponse = responseText,
                    ctx = toolCtx,
                )
                val snap = uiStateHub.value.agent ?: return@flow
                val continuationText = executeStreamingStep(
                    this,
                    snap,
                    fsm.getCurrentState().currentStage,
                    timing,
                )
                uiStateHub.value.agent?.let { syncWithRepository(it) }
                toolOrchestrator.runToolChainLoop(this, fsm, continuationText, toolCtx)
            } else {
                toolOrchestrator.runToolChainLoop(this, fsm, responseText, toolCtx)
            }

            val finalAgent = uiStateHub.value.agent ?: return@flow
            syncWithRepository(finalAgent)

            attachTimingsToLastAssistant(timing.build())

            val updatedMemory = engine.performMaintenance(finalAgent)
            if (updatedMemory != finalAgent.workingMemory) {
                processingMutex.withLock {
                    patchUi { st ->
                        st.copy(agent = st.agent?.copy(workingMemory = updatedMemory))
                    }
                }
                syncWithRepository(uiStateHub.value.agent!!)
            }
        } catch (e: Exception) {
            val err = "Error: ${e.message}"
            _partialResponse.emit(err)
            patchUi { it.copy(lastStreamError = err, delivery = AgentTextDelivery.Error) }
            emit(err)
        } finally {
            patchUi {
                it.copy(
                    isProcessing = false,
                    agentActivity = AgentActivity.Idle,
                    delivery = AgentTextDelivery.Idle,
                    phaseHint = null,
                    streamingPreview = "",
                )
            }
        }
    }

    /**
     * Прикрепляет [timings] к последнему сообщению с ролью assistant и сохраняет копию в [AutonomousAgentUiState.lastCompletedTimings].
     */
    private suspend fun attachTimingsToLastAssistant(timings: AgentPhaseTimings) {
        processingMutex.withLock {
            patchUi { st ->
                val ag = st.agent ?: return@patchUi st
                val msgs = ag.messages
                val last = msgs.lastOrNull { it.role == "assistant" } ?: return@patchUi st
                val newMsgs = msgs.map { m ->
                    if (m.id == last.id) m.copy(phaseTimings = timings) else m
                }
                st.copy(
                    agent = ag.copy(messages = newMsgs),
                    lastCompletedTimings = timings,
                    currentRunTimings = null,
                )
            }
        }
    }

    /**
     * Набор колбэков для [ToolInvocationOrchestrator]: чтение/обновление агента, синк с БД, активность, следующий LLM-шаг.
     */
    private fun toolInvocationContext(
        collector: FlowCollector<String>,
        fsm: AgentStateMachine,
        timing: PhaseTimingCollector,
    ) = ToolInvocationContext(
        processingMutex = processingMutex,
        timing = timing,
        getAgent = { uiStateHub.value.agent },
        updateAgent = { transform ->
            patchUi { st ->
                st.copy(agent = transform(st.agent))
            }
        },
        syncWithRepository = { syncWithRepository(it) },
        setActivity = { patchUi { st -> st.copy(agentActivity = it) } },
        setPhaseHint = { hint -> patchUi { st -> st.copy(phaseHint = hint) } },
        createMessage = { role, content, parentId, stage, snap ->
            createMessage(role, content, parentId, stage, snap)
        },
        executeStreamingStep = { col, agent, stage, replaceLastAssistant ->
            executeStreamingStep(col, agent, stage, timing, replaceLastAssistant)
        },
    )

    /**
     * Однократное приветствие при пустой истории: короткий ответ модели по расширенному системному промпту.
     * Если сообщения уже есть, ничего не делает.
     */
    fun sendWelcomeMessage(): Flow<String> = flow {
        val fsm = _stateMachine.filterNotNull().first()
        try {
            processingMutex.withLock {
                val currentAgent = uiStateHub.value.agent ?: return@flow
                if (currentAgent.messages.isNotEmpty()) return@flow
                patchUi {
                    it.copy(isProcessing = true, agentActivity = AgentActivity.Working)
                }
            }

            val stage = fsm.getCurrentState().currentStage
            val agentSnapshot = uiStateHub.value.agent ?: return@flow
            val prepared = engine.prepareChatRequest(agentSnapshot, stage, isJsonMode = false)
            val welcomePrompt = prepared.systemPrompt + WELCOME_SYSTEM_SUFFIX
            val preparedWelcome = prepared.copy(
                systemPrompt = welcomePrompt,
                snapshot = prepared.snapshot.copy(effectiveSystemPrompt = welcomePrompt),
            )
            executeStreamingStepWithPrepared(
                this,
                agentSnapshot,
                stage,
                preparedWelcome,
                timing = null,
            )
            uiStateHub.value.agent?.let { syncWithRepository(it) }
        } catch (e: Exception) {
            val err = "Error: ${e.message}"
            _partialResponse.emit(err)
            emit(err)
        } finally {
            patchUi {
                it.copy(
                    isProcessing = false,
                    agentActivity = AgentActivity.Idle,
                    delivery = AgentTextDelivery.Idle,
                )
            }
        }
    }

    /**
     * Один раунд вызова модели: выбор стратегии по [Agent.ragEnabled], подготовка запроса и стриминг через [streamHandler].
     *
     * @return Сырой текст ответа модели (для парсинга инструментов и постобработки).
     */
    private suspend fun executeStreamingStep(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        timing: PhaseTimingCollector?,
        replaceLastAssistant: Boolean = false,
    ): String {
        val snap = resolveRuntimeSnapshot() ?: return ""
        val strategy = inferenceStrategyFor(snap.agent, directInferenceStrategy, ragInferenceStrategy)
        val prepared = strategy.prepareLlmRequest(snap, timing)
        return executeStreamingStepWithPrepared(collector, agent, stage, prepared, timing, replaceLastAssistant)
    }

    /**
     * Стриминг уже подготовленного запроса; учитывает режим RAG-JSON (буфер без превью в UI до разбора).
     */
    private suspend fun executeStreamingStepWithPrepared(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage,
        prepared: PreparedLlmRequest,
        timing: PhaseTimingCollector?,
        replaceLastAssistant: Boolean = false,
    ): String {
        val ragJsonMode = prepared.snapshot.isJsonMode && prepared.snapshot.ragAttribution != null
        return streamHandler.executeStreamingStepWithPrepared(
            collector = collector,
            agent = agent,
            stage = stage,
            prepared = prepared,
            replaceLastAssistant = replaceLastAssistant,
            processingMutex = processingMutex,
            timing = timing,
            getAgent = { uiStateHub.value.agent },
            updateAgent = { transform ->
                patchUi { st -> st.copy(agent = transform(st.agent)) }
            },
            emitPartial = { chunk ->
                _partialResponse.emit(chunk)
            },
            setActivity = { patchUi { st -> st.copy(agentActivity = it) } },
            onDeliveryChange = { d -> patchUi { st -> st.copy(delivery = d) } },
            onStreamingChunk = { chunk ->
                if (!ragJsonMode) {
                    patchUi { st -> st.copy(streamingPreview = st.streamingPreview + chunk) }
                }
            },
            onRagJsonParsed = {
                patchUi { st -> st.copy(streamingPreview = "") }
            },
            createMessage = { role, content, parentId, st, snap ->
                createMessage(role, content, parentId, st, snap)
            },
        )
    }

    private companion object {
        /** Максимум итераций «инструмент → ответ модели» подряд, защита от бесконечного цикла. */
        private const val MAX_TOOL_CHAIN_ITERATIONS = 32

        /** Дополнение к системному промпту для первого сообщения в пустом чате. */
        private const val WELCOME_SYSTEM_SUFFIX =
            "\n\n[ИНСТРУКЦИЯ] Пользователь ещё не начал диалог. Кратко поприветствуй его и предложи начать обсуждение задачи. Ответь одним коротким сообщением."
    }

    /** Создаёт [ChatMessage] с новым id, меткой времени и при необходимости привязкой к задаче. */
    private fun createMessage(
        role: String,
        content: String,
        parentId: String?,
        agentStage: AgentStage,
        llmSnapshot: LlmRequestSnapshot? = null,
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
        llmRequestSnapshot = llmSnapshot,
    )

    /** Соответствие стадии FSM задаче в UI (для поля [ChatMessage.taskState]). */
    private fun agentStageToTaskState(stage: AgentStage): TaskState = when (stage) {
        AgentStage.PLANNING -> TaskState.PLANNING
        AgentStage.EXECUTION -> TaskState.EXECUTION
        AgentStage.REVIEW -> TaskState.VERIFICATION
        AgentStage.DONE -> TaskState.DONE
    }

    /**
     * Записывает в БД поля агента и сообщения из текущего FSM-состояния (имя, промпт, история, RAG-флаг и т.д.).
     */
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
            ),
        )
    }

    /** Останавливает подписку на БД и отменяет корутины агента; после вызова объектом пользоваться нельзя. */
    fun dispose() {
        stateSyncJob?.cancel()
        job.cancel()
    }
}
