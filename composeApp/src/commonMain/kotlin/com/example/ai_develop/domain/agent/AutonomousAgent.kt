@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain.agent

import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import com.example.ai_develop.data.RagContextRetriever
import com.example.ai_develop.data.RagPipelineSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
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
 * @param maxToolChainIterations Верхняя граница итераций «инструмент → (следующий tool или LLM)» в [ToolInvocationOrchestrator].
 */
open class AutonomousAgent(
    val agentId: String,
    private val repository: ChatRepository,
    private val engine: AgentEngine,
    externalScope: CoroutineScope,
    private val taskIdForMessagePersistence: String? = null,
    private val ragContextRetriever: RagContextRetriever? = null,
    private val ragPipelineSettingsRepository: RagPipelineSettingsRepository? = null,
    protected val uiStateHub: MutableStateFlow<AutonomousAgentUiState> = MutableStateFlow(
        AutonomousAgentUiState()
    ),
    private val maxToolChainIterations: Int = MAX_TOOL_CHAIN_ITERATIONS,
) {
    /** Дочерний job: при [dispose] отменяет все корутины агента. */
    private val job = SupervisorJob(externalScope.coroutineContext[Job])

    /** Область корутин агента: подписка на БД, отправка сообщений и т.д. */
    private val scope = CoroutineScope(externalScope.coroutineContext.minusKey(Job) + job)

    /**
     * Единственный публичный поток наблюдаемых данных: агент, флаги обработки, активность, превью стрима, подсказки фаз, ошибки, тайминги.
     */
    val uiState: StateFlow<AutonomousAgentUiState> = uiStateHub.asStateFlow()

    /** Стадии: workflow ([WorkflowStageCoordinator]) или чат без FSM ([ChatStageCoordinator]). */
    private val _stageCoordinator = MutableStateFlow<AgentStageCoordinator?>(null)

    /** Сборка запроса к LLM с опциональным RAG (поиск, rewrite). */
    private val ragPreparer = RagAwareChatRequestPreparer(
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
    private val toolOrchestrator = ToolInvocationOrchestrator(engine, maxToolChainIterations)

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

    /** Атомарно обновляет [uiStateHub]. */
    private fun patchUi(transform: (AutonomousAgentUiState) -> AutonomousAgentUiState) {
        uiStateHub.update(transform)
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
            workflowStagesEnabled = when {
                taskIdForMessagePersistence != null -> true
                agentId == GENERAL_CHAT_ID -> false
                else -> false
            },
        ).also { repository.saveAgentState(it) }

        updateSnapshot(state)
    }

    /**
     * Пересобирает локального [Agent] из [state]: профиль, FSM, список сообщений с учётом merge при активной обработке.
     */
    private suspend fun updateSnapshot(state: AgentState) {
        val profile = repository.getProfile(agentId)
        _stageCoordinator.value = if (state.workflowStagesEnabled) {
            WorkflowStageCoordinator(AgentStateMachine(state))
        } else {
            ChatStageCoordinator(state)
        }

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
     * Узкое горлышко перед LLM/RAG: один [ChatRepository.getAgentState], слияние с [uiStateHub]:
     * [Agent.ragEnabled], [AgentState.workflowStagesEnabled] → [AgentRuntimeSnapshot.injectWorkflowStageIntoPrompt],
     * стадия из [AgentStageCoordinator].
     */
    private suspend fun resolveRuntimeSnapshot(): AgentRuntimeSnapshot? {
        val ag = uiStateHub.value.agent ?: return null
        val coord = _stageCoordinator.value ?: return null
        val persisted = repository.getAgentState(agentId)
        val mergedAgent = ag.copy(ragEnabled = persisted?.ragEnabled ?: ag.ragEnabled)
        val injectWorkflow = persisted?.workflowStagesEnabled ?: true
        return AgentRuntimeSnapshot(
            agent = mergedAgent,
            stage = coord.currentStage(),
            injectWorkflowStageIntoPrompt = injectWorkflow,
        )
    }

    /**
     * Переводит агента на следующую стадию FSM и сохраняет изменения в репозитории при успехе.
     */
    suspend fun transitionTo(nextStage: AgentStage): Result<AgentState> {
        val coord = _stageCoordinator.value
            ?: return Result.failure(IllegalStateException("Agent not initialized"))
        val result = coord.transitionTo(nextStage)
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
     * Возвращает [Flow] для запуска сценария и ожидания завершения; превью и итог — в [uiState].
     */
    fun sendMessage(text: String): Flow<String> = flow {
        if (!job.isActive) {
            patchUi {
                it.copy(
                    isProcessing = false,
                    agentActivity = AgentActivity.Idle,
                    delivery = AgentTextDelivery.Idle,
                    phaseHint = null,
                    streamingPreview = "",
                )
            }
            return@flow
        }
        val coord = _stageCoordinator.filterNotNull().first()
        val timing = PhaseTimingCollector()
        val currentStageForTools = { coord.currentStage() }

        try {
            processingMutex.withLock {
                val currentAgent = uiStateHub.value.agent ?: return@flow
                val msg = createMessage(
                    "user",
                    text,
                    currentAgent.messages.lastOrNull()?.id,
                    coord.currentStage(),
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

            val snapForLlm = resolveRuntimeSnapshot() ?: return@flow
            if (snapForLlm.agent.ragEnabled) {
                patchUi { it.copy(phaseHint = PhaseStatusHint.Rag()) }
            }
            patchUi { it.copy(phaseHint = PhaseStatusHint.AwaitingLlm) }

            var responseText = executeStreamingStep(
                this,
                timing,
            )
            patchUi { it.copy(phaseHint = null) }
            uiStateHub.value.agent?.let { syncWithRepository(it) }

            val batchCalls = engine.parseAllToolCalls(responseText)
            val agentForTools = uiStateHub.value.agent ?: return@flow
            val suppressOnlyBatch =
                batchCalls.isNotEmpty() && batchCalls.all {
                    engine.toolSuppressesLlmFollowUp(
                        agentForTools,
                        it.toolName
                    )
                }

            val toolCtx = toolInvocationContext(timing)

            if (suppressOnlyBatch) {
                toolOrchestrator.runSuppressOnlyToolSequence(
                    calls = batchCalls.take(MAX_TOOL_CHAIN_ITERATIONS),
                    currentStage = currentStageForTools,
                    rawModelResponse = responseText,
                    ctx = toolCtx,
                )
                val continuationText = executeStreamingStep(
                    this,
                    timing,
                )
                uiStateHub.value.agent?.let { syncWithRepository(it) }
                toolOrchestrator.runToolChainLoop(this, currentStageForTools, continuationText, toolCtx)
            } else {
                toolOrchestrator.runToolChainLoop(this, currentStageForTools, responseText, toolCtx)
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
        executeStreamingStep = { col, replaceLastAssistant ->
            executeStreamingStep(col, timing, replaceLastAssistant)
        },
    )

    /**
     * Однократное приветствие при пустой истории: короткий ответ модели по расширенному системному промпту.
     * Если сообщения уже есть, ничего не делает.
     */
    fun sendWelcomeMessage(): Flow<String> = flow {
        if (!job.isActive) {
            patchUi {
                it.copy(
                    isProcessing = false,
                    agentActivity = AgentActivity.Idle,
                    delivery = AgentTextDelivery.Idle,
                )
            }
            return@flow
        }
        _stageCoordinator.filterNotNull().first()
        try {
            processingMutex.withLock {
                val currentAgent = uiStateHub.value.agent ?: return@flow
                if (currentAgent.messages.isNotEmpty()) return@flow
                patchUi {
                    it.copy(isProcessing = true, agentActivity = AgentActivity.Working)
                }
            }

            val snap = resolveRuntimeSnapshot() ?: return@flow
            val prepared = engine.prepareChatRequest(
                snap.agent,
                snap.stage,
                isJsonMode = false,
                injectWorkflowStageIntoPrompt = snap.injectWorkflowStageIntoPrompt,
            )
            val welcomePrompt = prepared.systemPrompt + WELCOME_SYSTEM_SUFFIX
            val preparedWelcome = prepared.copy(
                systemPrompt = welcomePrompt,
                snapshot = prepared.snapshot.copy(effectiveSystemPrompt = welcomePrompt),
            )
            executeStreamingStepWithPrepared(
                this,
                snap.agent,
                snap.stage,
                preparedWelcome,
                timing = null,
            )
            uiStateHub.value.agent?.let { syncWithRepository(it) }
        } catch (e: Exception) {
            val err = "Error: ${e.message}"
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
        timing: PhaseTimingCollector?,
        replaceLastAssistant: Boolean = false,
    ): String {
        val snap = resolveRuntimeSnapshot() ?: return ""
        val strategy =
            inferenceStrategyFor(snap.agent, directInferenceStrategy, ragInferenceStrategy)
        val prepared = strategy.prepareLlmRequest(snap, timing)
        return executeStreamingStepWithPrepared(
            collector,
            snap.agent,
            snap.stage,
            prepared,
            timing,
            replaceLastAssistant
        )
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
        val coord = _stageCoordinator.value ?: return
        repository.saveAgentState(
            coord.currentState().copy(
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
