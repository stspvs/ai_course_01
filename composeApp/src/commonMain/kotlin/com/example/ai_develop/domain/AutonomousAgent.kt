@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.domain

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
    externalScope: CoroutineScope
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

        _agent.update {
            Agent(
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
        }
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
                val msg = createMessage("user", text, currentAgent.messages.lastOrNull()?.id)
                _agent.update { it?.copy(messages = it.messages + msg) }
                _isProcessing.value = true
            }

            // 2. Берем snapshot для текущей итерации LLM
            var agentSnapshot = _agent.value ?: return@flow
            
            // 3. Первая итерация LLM
            var responseText = executeStreamingStep(this, agentSnapshot, fsm.getCurrentState().currentStage)
            
            // 4. Tool Calling Loop
            var toolResult = engine.processTools(responseText)
            var iterations = 0
            val visitedResults = mutableSetOf<String>()

            while (toolResult != null && iterations < 3 && visitedResults.add(toolResult)) {
                emit("\n[Executing tool...]\n")
                
                agentSnapshot = _agent.value ?: break
                val toolMsg = createMessage("system", "Tool Result: $toolResult", agentSnapshot.messages.lastOrNull()?.id)
                
                processingMutex.withLock {
                    _agent.update { it?.copy(messages = it.messages + toolMsg) }
                }
                
                agentSnapshot = _agent.value ?: break
                responseText = executeStreamingStep(this, agentSnapshot, fsm.getCurrentState().currentStage)
                toolResult = engine.processTools(responseText)
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
        }
    }

    private suspend fun executeStreamingStep(
        collector: FlowCollector<String>,
        agent: Agent,
        stage: AgentStage
    ): String {
        val sb = StringBuilder()
        try {
            engine.streamResponse(agent, stage).collect { chunk ->
                currentCoroutineContext().ensureActive()
                sb.append(chunk)
                _partialResponse.emit(chunk)
                collector.emit(chunk)
            }
        } catch (e: Exception) {
            _partialResponse.emit("Error during streaming: ${e.message}")
            throw e
        }
        
        // Обновляем сообщения агента под локом для сохранения консистентности
        processingMutex.withLock {
            val parentId = _agent.value?.messages?.lastOrNull()?.id
            val aiMsg = createMessage("assistant", sb.toString(), parentId)
            _agent.update { it?.copy(messages = it.messages + aiMsg) }
        }
        
        return sb.toString()
    }

    private fun createMessage(role: String, content: String, parentId: String?) = ChatMessage(
        id = Uuid.random().toString(),
        role = role,
        message = content,
        timestamp = System.currentTimeMillis(),
        source = if (role == "user") SourceType.USER else SourceType.AI,
        parentId = parentId
    )

    private suspend fun syncWithRepository(agent: Agent) {
        val fsm = _stateMachine.value ?: return
        repository.saveAgentState(
            fsm.getCurrentState().copy(
                name = agent.name,
                systemPrompt = agent.systemPrompt,
                temperature = agent.temperature,
                maxTokens = agent.maxTokens,
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
