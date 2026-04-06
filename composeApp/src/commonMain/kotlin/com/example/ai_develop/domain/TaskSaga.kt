package com.example.ai_develop.domain

import com.example.ai_develop.data.database.LocalChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class TaskSaga(
    private val repository: ChatRepository,
    private val localRepository: LocalChatRepository,
    private val architect: Agent?,
    private val executor: Agent?,
    private val validator: Agent?,
    initialContext: TaskContext,
    private val memoryManager: ChatMemoryManager
) {
    private val _context = MutableStateFlow(initialContext)
    val context: StateFlow<TaskContext> = _context.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    
    private var isStageRunning = false

    @Serializable
    data class SagaResponse(
        val status: String,
        val result: String
    )

    fun start() {
        if (_context.value.isPaused) return
        processCurrentState()
    }

    fun stop() {
        scope.cancel()
    }

    fun pause() {
        if (_context.value.isPaused) return
        _context.value = _context.value.copy(isPaused = true)
        updatePersistentState()
    }

    fun resume() {
        if (!_context.value.isPaused) return
        _context.value = _context.value.copy(isPaused = false)
        updatePersistentState()
        processCurrentState()
    }

    fun reset() {
        scope.launch {
            localRepository.deleteMessagesForTask(_context.value.taskId)
            _context.value = _context.value.copy(
                state = _context.value.state.copy(taskState = TaskState.PLANNING),
                step = 0,
                plan = emptyList(),
                planDone = emptyList(),
                currentPlanStep = null,
                isPaused = true
            )
            updatePersistentState()
        }
    }

    private fun processCurrentState() {
        val currentContext = _context.value
        if (currentContext.isPaused || currentContext.state.taskState == TaskState.DONE) return

        when (currentContext.state.taskState) {
            TaskState.PLANNING -> runStage(architect, "PLANNING")
            TaskState.EXECUTION -> runStage(executor, "EXECUTION")
            TaskState.VALIDATION -> runStage(validator, "VALIDATION")
            TaskState.DONE -> {}
        }
    }

    private fun runStage(agent: Agent?, stageName: String) {
        if (agent == null) {
            val currentStage = _context.value.state.taskState
            pause()
            scope.launch {
                localRepository.saveMessage(
                    agentId = "system",
                    message = ChatMessage(
                        message = "⚠️ Остановка: Агент для этапа $stageName не назначен. Пожалуйста, выберите агента в настройках задачи.",
                        role = "system",
                        source = SourceType.SYSTEM,
                        timestamp = System.currentTimeMillis(),
                        taskId = _context.value.taskId,
                        taskState = currentStage,
                        isSystemNotification = true
                    ),
                    taskId = _context.value.taskId,
                    taskState = currentStage
                )
            }
            return
        }

        if (isStageRunning) return
        isStageRunning = true

        scope.launch {
            try {
                val currentContext = _context.value
                val allMessages = localRepository.getMessagesForTask(currentContext.taskId).first()
                
                val sagaFilteredMessages = filterHistoryForStage(allMessages, currentContext.state.taskState)
                val isPlanning = currentContext.state.taskState == TaskState.PLANNING

                val finalHistory = if (isPlanning) {
                    memoryManager.processMessages(sagaFilteredMessages, agent.memoryStrategy)
                } else {
                    sagaFilteredMessages
                }
                
                val systemInstruction = "\n\nIMPORTANT: You are currently in the $stageName stage of the task: '${currentContext.title}'.\n" +
                        "Your goal is to complete this stage and return a JSON response.\n" +
                        "JSON format: {\"status\": \"SUCCESS\"/\"FAILED\", \"result\": \"description\"}"

                val fullSystemPrompt = if (isPlanning) {
                    memoryManager.wrapSystemPrompt(agent) + systemInstruction
                } else {
                    agent.systemPrompt + systemInstruction
                }
                
                val contextMessages = mutableListOf<ChatMessage>()
                if (isPlanning) {
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
                    isJsonMode = true,
                    provider = agent.provider
                ).collect { result ->
                    result.onSuccess { chunk ->
                        fullResponse += chunk
                    }.onFailure { error ->
                        pause()
                        localRepository.saveMessage(
                            agentId = "system",
                            message = ChatMessage(
                                message = "❌ Ошибка API (${agent.name}): ${error.message ?: "Неизвестная ошибка"}",
                                role = "system",
                                source = SourceType.SYSTEM,
                                timestamp = System.currentTimeMillis(),
                                taskId = currentContext.taskId,
                                taskState = currentContext.state.taskState,
                                isSystemNotification = true
                            ),
                            taskId = currentContext.taskId,
                            taskState = currentContext.state.taskState
                        )
                    }
                }
                
                if (fullResponse.isNotBlank() && !_context.value.isPaused) {
                    val chatMessage = ChatMessage(
                        message = fullResponse,
                        role = "assistant",
                        source = SourceType.AI,
                        timestamp = System.currentTimeMillis(),
                        taskId = currentContext.taskId,
                        taskState = currentContext.state.taskState
                    )
                    localRepository.saveMessage(
                        agentId = agent.id,
                        message = chatMessage,
                        taskId = currentContext.taskId,
                        taskState = currentContext.state.taskState
                    )
                    
                    val sagaResp = parseSagaResponse(fullResponse)
                    if (sagaResp != null) {
                        if (sagaResp.status == "SUCCESS") {
                            transitionToNext(agent.id, sagaResp.result)
                        } else {
                            transitionBack(agent.id, sagaResp.result)
                        }
                    } else {
                        transitionBack(agent.id, "Invalid JSON response from agent.")
                    }
                }
            } catch (e: Exception) {
                pause()
            } finally {
                isStageRunning = false
            }
        }
    }

    private fun filterHistoryForStage(messages: List<ChatMessage>, currentStage: TaskState): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        result.addAll(messages.filter { it.source == SourceType.USER })
        val stagesToConsider = listOf(TaskState.PLANNING, TaskState.EXECUTION, TaskState.VALIDATION)
        stagesToConsider.forEach { stage ->
            val lastMsgFromStage = messages
                .filter { it.taskState == stage && it.source != SourceType.USER }
                .lastOrNull()
            if (lastMsgFromStage != null) {
                result.add(lastMsgFromStage)
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.timestamp }
    }

    fun handleUserMessage(message: String) {
        if (_context.value.state.taskState != TaskState.PLANNING) return
        scope.launch {
            val chatMessage = ChatMessage(
                message = message,
                role = "user",
                source = SourceType.USER,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = TaskState.PLANNING
            )
            localRepository.saveMessage(
                agentId = architect?.id ?: "unknown",
                message = chatMessage,
                taskId = _context.value.taskId,
                taskState = TaskState.PLANNING
            )
            if (!_context.value.isPaused) {
                processCurrentState()
            }
        }
    }

    private fun transitionToNext(sourceAgentId: String, result: String) {
        val currentState = _context.value.state.taskState
        val nextState = when (currentState) {
            TaskState.PLANNING -> TaskState.EXECUTION
            TaskState.EXECUTION -> TaskState.VALIDATION
            TaskState.VALIDATION -> TaskState.DONE
            TaskState.DONE -> TaskState.DONE
        }
        
        scope.launch {
            if (currentState == TaskState.PLANNING && nextState == TaskState.EXECUTION) {
                compressPlanningContext(sourceAgentId)
            }

            val notification = ChatMessage(
                message = "--- STAGE SUCCESS ---\nResult: $result",
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = currentState,
                isSystemNotification = true
            )
            localRepository.saveMessage(
                agentId = sourceAgentId,
                message = notification,
                taskId = _context.value.taskId,
                taskState = currentState
            )

            _context.value = _context.value.copy(
                state = _context.value.state.copy(taskState = nextState),
                step = _context.value.step + 1
            )
            updatePersistentState()
            processCurrentState()
        }
    }

    private suspend fun compressPlanningContext(agentId: String) {
        val agent = architect ?: return
        if (agent.memoryStrategy is ChatMemoryStrategy.Summarization) {
            val messages = localRepository.getMessagesForTask(_context.value.taskId).first()
            val result = repository.summarize(
                messages = messages,
                previousSummary = null,
                instruction = agent.memoryStrategy.summaryPrompt,
                provider = agent.provider
            )
            result.onSuccess { summary ->
                val updatedAgent = agent.copy(
                    memoryStrategy = agent.memoryStrategy.copy(summary = summary)
                )
                localRepository.saveAgentMetadata(updatedAgent)
            }
        }
    }

    private fun transitionBack(sourceAgentId: String, reason: String) {
        val currentState = _context.value.state.taskState
        val prevState = when (currentState) {
            TaskState.EXECUTION -> TaskState.PLANNING
            TaskState.VALIDATION -> TaskState.EXECUTION
            else -> currentState
        }
        scope.launch {
            val notification = ChatMessage(
                message = "--- STAGE FAILED/REJECTED ---\nReason: $reason",
                role = "system",
                source = SourceType.SYSTEM,
                timestamp = System.currentTimeMillis(),
                taskId = _context.value.taskId,
                taskState = currentState,
                isSystemNotification = true
            )
            localRepository.saveMessage(
                agentId = sourceAgentId,
                message = notification,
                taskId = _context.value.taskId,
                taskState = currentState
            )
            _context.value = _context.value.copy(
                state = _context.value.state.copy(taskState = prevState)
            )
            updatePersistentState()
            processCurrentState()
        }
    }

    private fun updatePersistentState() {
        val stateToSave = _context.value
        scope.launch {
            localRepository.saveTask(stateToSave)
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
