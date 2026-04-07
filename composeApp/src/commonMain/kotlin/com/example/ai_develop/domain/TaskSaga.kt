package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

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

    private val scope = providedScope ?: CoroutineScope(dispatcher + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    
    private var isStageRunning = false

    private class APIException(val error: Throwable) : Exception(error.message)

    init {
        scope.launch {
            localRepository.getTasks()
                .map { tasks -> tasks.find { it.taskId == initialContext.taskId } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { updatedContext ->
                    val wasPaused = _context.value.isPaused
                    val oldState = _context.value.state.taskState
                    _context.value = updatedContext
                    
                    val becameUnpaused = wasPaused && !updatedContext.isPaused
                    val stateChangedWhileRunning = !updatedContext.isPaused && oldState != updatedContext.state.taskState
                    
                    if (becameUnpaused || stateChangedWhileRunning) {
                        processCurrentState()
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
            TaskState.VALIDATION -> ValidatorRole()
            TaskState.DONE -> throw IllegalStateException("No role for DONE state")
        }
    }

    fun start() {
        if (!_context.value.isPaused) {
            processCurrentState()
        }
    }

    fun stop() {
        // Only cancel if we created the scope ourselves
        // If providedScope was passed, we shouldn't cancel it as it's managed externally (e.g. ViewModel or runTest)
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
                    isPaused = true
                )
            }
        }
    }

    private suspend fun updateStateInDb(block: (TaskContext) -> TaskContext) {
        val newState = block(_context.value)
        localRepository.saveTask(newState)
    }

    private fun processCurrentState() {
        val currentContext = _context.value
        if (currentContext.isPaused || currentContext.state.taskState == TaskState.DONE) return

        if (!currentContext.isReadyToRun) {
            pause()
            return
        }

        val role = getRoleForState(currentContext.state.taskState)
        val agent = when (currentContext.state.taskState) {
            TaskState.PLANNING -> architect
            TaskState.EXECUTION -> executor
            TaskState.VALIDATION -> validator
            TaskState.DONE -> null
        }
        
        runStage(agent, role)
    }

    private fun runStage(agent: Agent?, role: TaskRole) {
        if (agent == null) {
            scope.launch {
                handleStageError(null, Exception("Агент для этапа ${role.taskState} не назначен"))
            }
            return
        }

        if (isStageRunning) return
        isStageRunning = true

        scope.launch {
            try {
                val currentContext = _context.value
                
                val finalHistory = role.processHistory(agent, memoryManager)
                val systemInstruction = role.getSystemInstruction(currentContext)
                val fullSystemPrompt = role.buildSystemPrompt(agent, systemInstruction, memoryManager)
                
                val contextMessages = mutableListOf<ChatMessage>()
                if (role is ArchitectRole) {
                    memoryManager.getShortTermMemoryMessage(agent)?.let { contextMessages.add(it) }
                }
                contextMessages.addAll(finalHistory)

                var fullResponse = ""

                repository.chatStreaming(
                    messages = contextMessages, 
                    systemPrompt = fullSystemPrompt,
                    maxTokens = agent.maxTokens,
                    temperature = agent.temperature,
                    stopWord = agent.stopWord,
                    isJsonMode = role.isJsonMode(),
                    provider = agent.provider
                ).collect { result ->
                    result.onSuccess { chunk ->
                        fullResponse += chunk
                    }.onFailure { error ->
                        throw APIException(error)
                    }
                }
                
                if (fullResponse.isNotBlank() && !_context.value.isPaused) {
                    val sagaResp = parseSagaResponse(fullResponse)
                    
                    val lastMsgId = agent.messages.lastOrNull()?.id

                    val chatMessage = ChatMessage(
                        message = fullResponse,
                        role = "assistant",
                        source = SourceType.AI,
                        timestamp = System.currentTimeMillis(),
                        taskId = currentContext.taskId,
                        taskState = currentContext.state.taskState,
                        parentId = lastMsgId
                    )
                    localRepository.saveMessage(
                        agentId = agent.id,
                        message = chatMessage,
                        taskId = currentContext.taskId,
                        taskState = currentContext.state.taskState
                    )
                    
                    isStageRunning = false
                    
                    when (val result = role.handleResponse(fullResponse, sagaResp)) {
                        is RoleResult.Success -> transitionToNext(agent.id, result.result)
                        is RoleResult.Failure -> transitionBack(agent.id, result.reason)
                        RoleResult.Partial -> { /* Continue conversation */ }
                    }
                } else {
                    isStageRunning = false
                }
            } catch (e: APIException) {
                isStageRunning = false
                handleStageError(agent, e.error)
            } catch (e: CancellationException) {
                isStageRunning = false
            } catch (e: Exception) {
                isStageRunning = false
                handleStageError(agent, e)
            }
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
            val lastMessage = activeAgent.messages.lastOrNull()

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
            
            if (!_context.value.isPaused) {
                processCurrentState()
            }
        }
    }

    private suspend fun transitionToNext(sourceAgentId: String, result: String) {
        val currentState = _context.value.state.taskState
        val nextState = when (currentState) {
            TaskState.PLANNING -> TaskState.EXECUTION
            TaskState.EXECUTION -> TaskState.VALIDATION
            TaskState.VALIDATION -> TaskState.DONE
            TaskState.DONE -> TaskState.DONE
        }
        
        val currentAgent = when(currentState) {
            TaskState.PLANNING -> architect
            TaskState.EXECUTION -> executor
            TaskState.VALIDATION -> validator
            else -> null
        }

        if (currentState == TaskState.PLANNING && nextState == TaskState.EXECUTION) {
            compressPlanningContext(currentAgent)
        }

        val lastMsgId = currentAgent?.messages?.lastOrNull()?.id

        val notification = ChatMessage(
            message = "--- STAGE SUCCESS ---\nResult: $result",
            role = "system",
            source = SourceType.SYSTEM,
            timestamp = System.currentTimeMillis(),
            taskId = _context.value.taskId,
            taskState = currentState,
            isSystemNotification = true,
            parentId = lastMsgId
        )
        localRepository.saveMessage(
            agentId = sourceAgentId,
            message = notification,
            taskId = _context.value.taskId,
            taskState = currentState
        )

        updateStateInDb { 
            it.copy(
                state = it.state.copy(taskState = nextState),
                step = it.step + 1
            )
        }
    }

    private suspend fun compressPlanningContext(agent: Agent?) {
        val architectAgent = agent ?: return
        if (architectAgent.memoryStrategy is ChatMemoryStrategy.Summarization) {
            val result = repository.summarize(
                messages = architectAgent.messages,
                previousSummary = null,
                instruction = architectAgent.memoryStrategy.summaryPrompt,
                provider = architectAgent.provider
            )
            result.onSuccess { summary ->
                val updatedAgent = architectAgent.copy(
                    memoryStrategy = architectAgent.memoryStrategy.copy(summary = summary)
                )
                localRepository.saveAgentMetadata(updatedAgent)
            }
        }
    }

    private suspend fun transitionBack(sourceAgentId: String, reason: String) {
        val currentState = _context.value.state.taskState
        val prevState = when (currentState) {
            TaskState.EXECUTION -> TaskState.PLANNING
            TaskState.VALIDATION -> TaskState.EXECUTION
            else -> currentState
        }
        
        val currentAgent = when(currentState) {
            TaskState.PLANNING -> architect
            TaskState.EXECUTION -> executor
            TaskState.VALIDATION -> validator
            else -> null
        }
        val lastMsgId = currentAgent?.messages?.lastOrNull()?.id

        val notification = ChatMessage(
            message = "--- STAGE FAILED/REJECTED ---\nReason: $reason",
            role = "system",
            source = SourceType.SYSTEM,
            timestamp = System.currentTimeMillis(),
            taskId = _context.value.taskId,
            taskState = currentState,
            isSystemNotification = true,
            parentId = lastMsgId
        )
        localRepository.saveMessage(
            agentId = sourceAgentId,
            message = notification,
            taskId = _context.value.taskId,
            taskState = currentState
        )
        updateStateInDb { 
            it.copy(state = it.state.copy(taskState = prevState))
        }
    }
    
    private fun parseSagaResponse(text: String): SagaResponse? {
        return try {
            val start = text.indexOf("{")
            val end = text.lastIndexOf("}")
            if (start != -1 && end != -1) {
                val jsonStr = text.substring(start, end + 1)
                json.decodeFromString<SagaResponse>(jsonStr)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
