@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Автономный агент — это "живой" объект в доменном слое.
 * Он владеет состоянием, машиной состояний и логикой взаимодействия с LLM.
 */
class AutonomousAgent(
    val agentId: String,
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val scope: CoroutineScope
) {
    // Состояние агента (Snapshot для UI)
    private val _agent = MutableStateFlow<Agent?>(null)
    val agent: StateFlow<Agent?> = _agent.asStateFlow()

    // Машина состояний для управления фазами (Planning, Execution...)
    private var stateMachine: AgentStateMachine? = null

    // Поток входящих токенов для текущего ответа
    private val _partialResponse = MutableSharedFlow<String>(replay = 10)
    val partialResponse: SharedFlow<String> = _partialResponse.asSharedFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var stateSyncJob: Job? = null

    init {
        loadAndSubscribe()
    }

    private fun loadAndSubscribe() {
        stateSyncJob?.cancel()
        stateSyncJob = scope.launch {
            // Подписываемся на изменения в БД, если агент обновился извне
            repository.observeAgentState(agentId).collect { state ->
                if (_agent.value == null && state != null) {
                    refreshAgent()
                }
            }
        }
    }

    suspend fun refreshAgent() {
        val state = repository.getAgentState(agentId) ?: return
        val profile = repository.getProfile(agentId)

        // Инициализируем машину состояний текущим состоянием из БД
        stateMachine = AgentStateMachine(state)

        // Воссоздаем объект Agent (Snapshot)
        val loadedAgent = Agent(
            id = state.agentId,
            name = state.name,
            systemPrompt = state.systemPrompt,
            temperature = state.temperature,
            provider = profile?.memoryModelProvider ?: LLMProvider.Yandex(),
            stopWord = state.stopWord,
            maxTokens = state.maxTokens,
            userProfile = profile,
            memoryStrategy = state.memoryStrategy,
            workingMemory = state.workingMemory,
            messages = state.messages
        )

        _agent.value = loadedAgent
    }

    /**
     * Смена этапа через машину состояний с сохранением в БД
     */
    suspend fun transitionTo(nextStage: AgentStage): Result<Unit> {
        val fsm = stateMachine
            ?: return Result.failure(IllegalStateException("State machine not initialized"))

        return fsm.transitionTo(nextStage).map { newState ->
            repository.saveAgentState(newState)
            // Обновляем локальный snapshot
            _agent.update { it?.copy(
                memoryStrategy = newState.memoryStrategy,
                workingMemory = newState.workingMemory
            ) }
        }
    }

    /**
     * Основной метод отправки сообщения.
     * Теперь агент сам решает, как формировать промпт и как обрабатывать ответ.
     */
    suspend fun sendMessage(text: String) {
        val currentAgent = _agent.value ?: return
        val fsm = stateMachine ?: return

        if (_isProcessing.value) return
        _isProcessing.value = true

        // Создаем сообщение пользователя
        val userMessage = ChatMessage(
            id = Uuid.random().toString(),
            role = "user",
            message = text,
            timestamp = System.currentTimeMillis(),
            source = SourceType.USER,
            parentId = currentAgent.messages.lastOrNull()?.id
        )

        // Обновляем состояние (локально)
        val updatedAgent = currentAgent.copy(messages = currentAgent.messages + userMessage)
        _agent.value = updatedAgent

        processLlmResponse(updatedAgent)
    }

    private suspend fun processLlmResponse(currentAgent: Agent) {
        val fsm = stateMachine ?: return
        val currentState = fsm.getCurrentState()

        // 1. Формируем контекст в зависимости от текущей стадии
        val stageContext = "\n[SYSTEM INFO] CURRENT STAGE: ${currentState.currentStage}\n"
        val basePrompt = memoryManager.wrapSystemPrompt(currentAgent)
        val systemPrompt = basePrompt + stageContext

        // 2. Подготовка истории (Memory Strategy)
        val inputMessages = mutableListOf<ChatMessage>()
        memoryManager.getShortTermMemoryMessage(currentAgent)?.let { inputMessages.add(it) }
        
        val history = memoryManager.processMessages(
            currentAgent.messages,
            currentAgent.memoryStrategy,
            currentAgent.currentBranchId,
            currentAgent.branches
        )
        inputMessages.addAll(history)

        val responseBuilder = StringBuilder()

        try {
            repository.chatStreaming(
                messages = inputMessages,
                systemPrompt = systemPrompt,
                maxTokens = currentAgent.maxTokens,
                temperature = currentAgent.temperature,
                stopWord = currentAgent.stopWord,
                isJsonMode = false,
                provider = currentAgent.provider
            ).collect { result ->
                result.onSuccess { chunk ->
                    responseBuilder.append(chunk)
                    _partialResponse.emit(chunk) // Отправляем токен в UI
                }.onFailure { throw it }
            }

            // 3. Финализация ответа
            val aiMessage = ChatMessage(
                id = Uuid.random().toString(),
                role = "assistant",
                message = responseBuilder.toString(),
                timestamp = System.currentTimeMillis(),
                source = SourceType.ASSISTANT,
                parentId = currentAgent.messages.lastOrNull()?.id
            )

            val finalAgent = currentAgent.copy(messages = currentAgent.messages + aiMessage)
            _agent.value = finalAgent

            // 4. Синхронизация и фоновые задачи (Maintenance)
            syncWithRepository(finalAgent)
            performMaintenance(finalAgent)

        } catch (e: Exception) {
            _partialResponse.emit("Error: ${e.message}")
        } finally {
            _isProcessing.value = false
        }
    }

    private suspend fun performMaintenance(agent: Agent) {
        // Логика извлечения фактов и анализа прогресса
        if (agent.workingMemory.isAutoUpdateEnabled &&
            agent.messages.size % agent.workingMemory.updateInterval == 0
        ) {
            repository.extractFacts(
                messages = agent.messages.takeLast(agent.workingMemory.analysisWindowSize),
                currentFacts = agent.workingMemory.extractedFacts,
                provider = agent.provider
            ).onSuccess { newFacts ->
                val updated = agent.copy(
                    workingMemory = agent.workingMemory.copy(extractedFacts = newFacts)
                )
                _agent.value = updated
                syncWithRepository(updated)
            }
        }
    }

    private suspend fun syncWithRepository(agent: Agent) {
        val fsm = stateMachine ?: return
        repository.saveAgentState(
            fsm.getCurrentState().copy(
                name = agent.name,
                systemPrompt = agent.systemPrompt,
                temperature = agent.temperature,
                maxTokens = agent.maxTokens,
                stopWord = agent.stopWord,
                memoryStrategy = agent.memoryStrategy,
                workingMemory = agent.workingMemory,
                messages = agent.messages
            )
        )
    }

    fun dispose() {
        stateSyncJob?.cancel()
    }
}
