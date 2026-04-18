package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import com.example.ai_develop.presentation.mvi.TaskUiResult
import com.example.ai_develop.presentation.mvi.TaskUiState
import com.example.ai_develop.presentation.mvi.reduceTaskUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface TaskEffect {
    data class ShowError(val message: String) : TaskEffect
}

sealed interface TaskEvent {
    data class SendMessage(val taskId: String, val text: String) : TaskEvent
    data class SelectTask(val taskId: String?) : TaskEvent
    data class CreateTask(val title: String) : TaskEvent
    data class UpdateTask(val task: TaskContext) : TaskEvent
    data class DeleteTask(val task: TaskContext) : TaskEvent
    data class TogglePause(val taskId: String) : TaskEvent
    data class ResetTask(val taskId: String) : TaskEvent
}

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val getTasksUseCase: GetTasksUseCase,
    private val getTaskUseCase: GetTaskUseCase,
    private val createTaskUseCase: CreateTaskUseCase,
    private val updateTaskUseCase: UpdateTaskUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val resetTaskUseCase: ResetTaskUseCase,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val agentChatSessionPort: AgentChatSessionPort,
    private val getAgentsUseCase: GetAgentsUseCase,
    private val agentFactory: DefaultAgentFactory,
    private val taskSagaCoordinator: TaskSagaCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val uiMutex = Mutex()

    private val _effects = MutableSharedFlow<TaskEffect>()
    val effects = _effects.asSharedFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    private val sharing = SharingStarted.WhileSubscribed(5000)

    val streamingDraft: StateFlow<String> = uiState
        .map { it.streamingPreview }
        .stateIn(viewModelScope, sharing, "")

    val tasks: StateFlow<List<TaskContext>> = getTasksUseCase()
        .stateIn(viewModelScope, sharing, emptyList())

    val agents: StateFlow<List<Agent>> = getAgentsUseCase()
        .stateIn(viewModelScope, sharing, emptyList())

    val activeAgent: StateFlow<AutonomousAgent?> = combine(
        _selectedTaskId,
        agentChatSessionPort.agentCacheGeneration,
    ) { id, _ -> id }
        .mapLatest { id ->
            id?.let {
                agentChatSessionPort.ensureToolsLoaded()
                agentChatSessionPort.getOrCreateAgent(it, it)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val activeSagaContext: StateFlow<TaskContext?> = _selectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else getTasksUseCase().map { list -> list.find { it.taskId == id } }
        }
        .stateIn(viewModelScope, sharing, null)

    val taskMessages: StateFlow<List<ChatMessage>> = _selectedTaskId
        .filterNotNull()
        .flatMapLatest { id -> getMessagesUseCase(id) }
        .stateIn(viewModelScope, sharing, emptyList())

    init {
        viewModelScope.launch {
            combine(_selectedTaskId, agentChatSessionPort.agentCacheGeneration) { id, _ -> id }
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf("")
                    } else {
                        flow {
                            agentChatSessionPort.ensureToolsLoaded()
                            emitAll(
                                agentChatSessionPort.getOrCreateAgent(id, id).uiState.map { it.streamingPreview },
                            )
                        }
                    }
                }
                .collect { preview ->
                    applyTaskUiResult(TaskUiResult.StreamingPreview(preview))
                }
        }
    }

    private suspend fun applyTaskUiResult(result: TaskUiResult) {
        uiMutex.withLock {
            _uiState.value = reduceTaskUiState(_uiState.value, result)
        }
    }

    private fun postTaskUiResult(result: TaskUiResult) {
        viewModelScope.launch {
            applyTaskUiResult(result)
        }
    }

    private suspend fun applySendingAndClearError(isSending: Boolean) {
        applyTaskUiResult(TaskUiResult.Error(null))
        applyTaskUiResult(TaskUiResult.SendingChanged(isSending))
    }

    /** Актуальная задача из БД; [tasks] может отставать из‑за асинхронной эмиссии SqlDelight. */
    private suspend fun latestTask(taskId: String): TaskContext? =
        getTaskUseCase(taskId) ?: tasks.value.find { it.taskId == taskId }

    fun onEvent(event: TaskEvent) {
        when (event) {
            is TaskEvent.SendMessage -> sendUserMessage(event.taskId, event.text)
            is TaskEvent.SelectTask -> selectTask(event.taskId)
            is TaskEvent.CreateTask -> createTask(event.title)
            is TaskEvent.UpdateTask -> updateTask(event.task)
            is TaskEvent.DeleteTask -> deleteTask(event.task)
            is TaskEvent.TogglePause -> togglePause(event.taskId)
            is TaskEvent.ResetTask -> resetTask(event.taskId)
        }
    }

    fun selectTask(taskId: String?) {
        val previousId = _selectedTaskId.value
        if (previousId != null && previousId != taskId) {
            viewModelScope.launch {
                try {
                    val previous = latestTask(previousId) ?: return@launch
                    if (previous.isStarted && !previous.isPaused) {
                        val paused = previous.copy(isPaused = true)
                        updateTaskUseCase(paused).getOrThrow()
                        taskSagaCoordinator.applyRuntimeLimitsAfterTaskSaved(paused)
                    }
                } catch (e: Exception) {
                    applyTaskUiResult(TaskUiResult.Error(e))
                }
            }
        }
        _selectedTaskId.value = taskId
    }

    fun createTask(title: String) {
        viewModelScope.launch {
            try {
                val taskId = Uuid.random().toString()
                val newTask = TaskContext(
                    taskId = taskId,
                    title = title,
                    state = AgentTaskState(TaskState.PLANNING, agentFactory.create()),
                    isPaused = false,
                    isStarted = false
                )
                createTaskUseCase(newTask).getOrThrow()
                selectTask(taskId)
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
                _effects.emit(TaskEffect.ShowError(e.message ?: "Unknown error"))
            }
        }
    }

    fun updateTask(task: TaskContext) {
        viewModelScope.launch {
            try {
                updateTaskUseCase(task).getOrThrow()
                taskSagaCoordinator.applyRuntimeLimitsAfterTaskSaved(task)
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
            }
        }
    }

    fun deleteTask(task: TaskContext) {
        viewModelScope.launch {
            try {
                taskSagaCoordinator.evict(task.taskId)
                if (_selectedTaskId.value == task.taskId) {
                    selectTask(null)
                }
                deleteTaskUseCase(task).getOrThrow()
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
                _effects.emit(TaskEffect.ShowError(e.message ?: "Unknown error"))
            }
        }
    }

    fun sendUserMessage(taskId: String, text: String) {
        val cleanText = text.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val task = latestTask(taskId)
            if (task != null &&
                task.isReadyToRun &&
                task.isStarted &&
                !task.isPaused &&
                task.state.taskState == TaskState.PLANNING
            ) {
                applySendingAndClearError(true)
                try {
                    taskSagaCoordinator.getOrCreateSaga(task).handleUserMessage(cleanText)
                } catch (e: Exception) {
                    applyTaskUiResult(TaskUiResult.Error(e))
                    _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send message"))
                } finally {
                    applyTaskUiResult(TaskUiResult.SendingChanged(false))
                }
                return@launch
            }

            applySendingAndClearError(true)
            try {
                agentChatSessionPort.ensureToolsLoaded()
                val agent = agentChatSessionPort.getOrCreateAgent(taskId, taskId)
                agent.sendMessage(cleanText).collect()
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
                _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send message"))
            } finally {
                applyTaskUiResult(TaskUiResult.SendingChanged(false))
            }
        }
    }

    fun confirmPlan(taskId: String) {
        viewModelScope.launch {
            try {
                val task = latestTask(taskId) ?: return@launch
                taskSagaCoordinator.getOrCreateSaga(task).confirmPlan()
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
                _effects.emit(TaskEffect.ShowError(e.message ?: "confirmPlan failed"))
            }
        }
    }

    fun togglePause(taskId: String) {
        viewModelScope.launch {
            try {
                val task = latestTask(taskId) ?: return@launch

                if (!task.isStarted) {
                    updateTaskUseCase(task.copy(isStarted = true)).getOrThrow()
                    val refreshed = latestTask(taskId) ?: return@launch
                    val saga = taskSagaCoordinator.getOrCreateSaga(refreshed)
                    taskSagaCoordinator.applyRuntimeLimitsAfterTaskSaved(refreshed)
                    val messages = getMessagesUseCase(taskId).first()
                    if (messages.isEmpty()) {
                        if (refreshed.isReadyToRun) {
                            applySendingAndClearError(true)
                            try {
                                saga.start()
                            } catch (e: Exception) {
                                applyTaskUiResult(TaskUiResult.Error(e))
                                _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga start failed"))
                            } finally {
                                applyTaskUiResult(TaskUiResult.SendingChanged(false))
                            }
                        } else {
                            applySendingAndClearError(true)
                            try {
                                agentChatSessionPort.ensureToolsLoaded()
                                val agent = agentChatSessionPort.getOrCreateAgent(taskId, taskId)
                                agent.sendWelcomeMessage().collect()
                            } catch (e: Exception) {
                                applyTaskUiResult(TaskUiResult.Error(e))
                                _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send welcome"))
                            } finally {
                                applyTaskUiResult(TaskUiResult.SendingChanged(false))
                            }
                        }
                    } else if (refreshed.isReadyToRun) {
                        applySendingAndClearError(true)
                        try {
                            saga.start()
                        } catch (e: Exception) {
                            applyTaskUiResult(TaskUiResult.Error(e))
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga resume failed"))
                        } finally {
                            applyTaskUiResult(TaskUiResult.SendingChanged(false))
                        }
                    }
                    return@launch
                }

                if (!task.isPaused) {
                    updateTaskUseCase(task.copy(isPaused = true)).getOrThrow()
                    return@launch
                }

                val messagesBeforeUnpause = getMessagesUseCase(taskId).first()
                updateTaskUseCase(task.copy(isPaused = false)).getOrThrow()
                val refreshed = latestTask(taskId) ?: return@launch
                val saga = taskSagaCoordinator.getOrCreateSaga(refreshed)
                taskSagaCoordinator.applyRuntimeLimitsAfterTaskSaved(refreshed)

                if (messagesBeforeUnpause.isEmpty()) {
                    if (refreshed.isReadyToRun) {
                        applySendingAndClearError(true)
                        try {
                            saga.start()
                        } catch (e: Exception) {
                            applyTaskUiResult(TaskUiResult.Error(e))
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga start failed"))
                        } finally {
                            applyTaskUiResult(TaskUiResult.SendingChanged(false))
                        }
                    } else {
                        applySendingAndClearError(true)
                        try {
                            agentChatSessionPort.ensureToolsLoaded()
                            val agent = agentChatSessionPort.getOrCreateAgent(taskId, taskId)
                            agent.sendWelcomeMessage().collect()
                        } catch (e: Exception) {
                            applyTaskUiResult(TaskUiResult.Error(e))
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send welcome"))
                        } finally {
                            applyTaskUiResult(TaskUiResult.SendingChanged(false))
                        }
                    }
                } else if (refreshed.isReadyToRun) {
                    applySendingAndClearError(true)
                    try {
                        saga.start()
                    } catch (e: Exception) {
                        applyTaskUiResult(TaskUiResult.Error(e))
                        _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga resume failed"))
                    } finally {
                        applyTaskUiResult(TaskUiResult.SendingChanged(false))
                    }
                }
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
            }
        }
    }

    fun resetTask(taskId: String) {
        viewModelScope.launch {
            try {
                taskSagaCoordinator.evict(taskId)
                resetTaskUseCase(taskId).getOrThrow()
                val task = latestTask(taskId) ?: return@launch
                updateTaskUseCase(
                    task.copy(
                        state = task.state.copy(taskState = TaskState.PLANNING),
                        isPaused = false,
                        isStarted = false,
                        step = 0,
                        plan = emptyList(),
                        planDone = emptyList(),
                        currentPlanStep = null,
                        runtimeState = TaskRuntimeState.resetProgressPreservingUserSettings(task.runtimeState)
                    )
                ).getOrThrow()
            } catch (e: Exception) {
                applyTaskUiResult(TaskUiResult.Error(e))
            }
        }
    }
}
