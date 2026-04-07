@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * Автономный агент с интегрированной машиной состояний (AgentStateMachine).
 */
class AutonomousAgent(
    val agentId: String,
    private val repository: ChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val scope: CoroutineScope
) {
    private val _agent = MutableStateFlow<Agent?>(null)
    val agent: StateFlow<Agent?> = _agent.asStateFlow()

    private var stateMachine: AgentStateMachine? = null

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var stateSyncJob: Job? = null

    init {
        loadAndSubscribe()
    }

    private fun loadAndSubscribe() {
        stateSyncJob?.cancel()
        stateSyncJob = scope.launch {
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

        val loadedAgent = Agent(
            id = state.agentId,
            name = state.name,
            systemPrompt = state.systemPrompt,
            temperature = state.temperature,
            provider = profile?.memoryModelProvider ?: LLMProvider.Yandex(),
            stopWord = state.stopWord,
            maxTokens = state.maxTokens,
            userProfile = profile,
            memoryStrategy = state.memoryStrategy
        )

        _agent.value = loadedAgent
    }

    /**
     * Пример использования машины состояний для смены этапа
     */
    suspend fun transitionTo(nextStage: AgentStage): Result<Unit> {
        val fsm = stateMachine
            ?: return Result.failure(IllegalStateException("State machine not initialized"))

        return fsm.transitionTo(nextStage).map { newState ->
            // Обновляем состояние в БД
            repository.saveAgentState(newState)
            // И локально (если нужно для UI)
            val current = _agent.value
            if (current != null) {
                // Мы можем расширить модель Agent, чтобы она тоже содержала stage для UI
            }
        }
    }

    suspend fun sendMessage(text: String) {
        val currentAgent = _agent.value ?: return
        val fsm = stateMachine ?: return

        if (_isProcessing.value) return
        _isProcessing.value = true

        // 1. Проверяем инварианты перед обработкой (если есть)
        val invariants = repository.getInvariants(agentId, fsm.getCurrentState().currentStage)
        // Здесь можно добавить логику проверки invariants через LLM или правила

        val userMessage = ChatMessage(
            id = Uuid.random().toString(),
            role = "user",
            message = text,
            timestamp = 0L,
            source = SourceType.USER,
            parentId = currentAgent.messages.lastOrNull()?.id
        )

        val updatedAgent = currentAgent.copy(messages = currentAgent.messages + userMessage)
        _agent.value = updatedAgent

        processLlmResponse(updatedAgent)
    }

    private suspend fun processLlmResponse(currentAgent: Agent) {
        val fsm = stateMachine ?: return

        // Модифицируем системный промпт в зависимости от ТЕКУЩЕЙ ФАЗЫ (Stage)
        val stageContext = "\nCURRENT STAGE: ${fsm.getCurrentState().currentStage}\n"
        val basePrompt = memoryManager.wrapSystemPrompt(currentAgent)
        val systemPrompt = basePrompt + stageContext

        val shortTermMemory = memoryManager.getShortTermMemoryMessage(currentAgent)
        val inputMessages = mutableListOf<ChatMessage>()
        shortTermMemory?.let { inputMessages.add(it) }

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
                }.onFailure { throw it }
            }

            val aiMessage = ChatMessage(
                id = Uuid.random().toString(),
                role = "assistant",
                message = responseBuilder.toString(),
                timestamp = 0L,
                source = SourceType.AI,
                parentId = currentAgent.messages.lastOrNull()?.id
            )

            val finalAgent = currentAgent.copy(messages = currentAgent.messages + aiMessage)
            _agent.value = finalAgent

            syncWithRepository(finalAgent)
            performMaintenance(finalAgent)

        } catch (e: Exception) {
            // Error handling
        } finally {
            _isProcessing.value = false
        }
    }

    private suspend fun performMaintenance(agent: Agent) {
        val fsm = stateMachine ?: return

        // После выполнения шага в EXECUTION, мы можем автоматически проверить,
        // нужно ли переходить в REVIEW
        if (fsm.getCurrentState().currentStage == AgentStage.EXECUTION) {
            // Анализируем: выполнен ли текущий шаг плана?
            // Здесь может быть вызов repository.analyzeTask
        }

        // Извлечение фактов
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

        // Сжатие... (логика остается такой же)
    }

    private suspend fun syncWithRepository(agent: Agent) {
        val fsm = stateMachine ?: return

        // Синхронизируем состояние машины с базой данных
        repository.saveAgentState(
            fsm.getCurrentState().copy(
                name = agent.name,
                systemPrompt = agent.systemPrompt,
                temperature = agent.temperature,
                maxTokens = agent.maxTokens,
                stopWord = agent.stopWord,
                memoryStrategy = agent.memoryStrategy
            )
        )
    }

    fun dispose() {
        stateSyncJob?.cancel()
    }
}
