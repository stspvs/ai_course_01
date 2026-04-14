package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class TaskUiState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val isSending: Boolean = false
)

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
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val getAgentsUseCase: GetAgentsUseCase,
    private val agentFactory: DefaultAgentFactory,
    private val taskSagaCoordinator: TaskSagaCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TaskEffect>()
    val effects = _effects.asSharedFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    private val _streamingDraft = MutableStateFlow("")
    val streamingDraft: StateFlow<String> = _streamingDraft.asStateFlow()

    private val sharing = SharingStarted.WhileSubscribed(5000)

    val tasks: StateFlow<List<TaskContext>> = getTasksUseCase()
        .stateIn(viewModelScope, sharing, emptyList())

    val agents: StateFlow<List<Agent>> = getAgentsUseCase()
        .stateIn(viewModelScope, sharing, emptyList())

    val activeAgent: StateFlow<AutonomousAgent?> = combine(
        _selectedTaskId,
        chatStreamingUseCase.agentCacheGeneration,
    ) { id, _ -> id }
        .mapLatest { id ->
            id?.let {
                chatStreamingUseCase.ensureToolsLoaded()
                chatStreamingUseCase.getOrCreateAgent(it, it)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * Снимок выбранной задачи из потока БД, а не из [tasks] (stateIn может отставать на тик после save / смены выбора).
     */
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
        _streamingDraft.value = ""
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
                    _uiState.update { it.copy(error = e) }
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
                _uiState.update { it.copy(error = e) }
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
                _uiState.update { it.copy(error = e) }
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
                _uiState.update { it.copy(error = e) }
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
                _uiState.update { it.copy(isSending = true, error = null) }
                try {
                    taskSagaCoordinator.getOrCreateSaga(task).handleUserMessage(cleanText)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e) }
                    _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send message"))
                } finally {
                    _uiState.update { it.copy(isSending = false) }
                }
                return@launch
            }

            _uiState.update { it.copy(isSending = true, error = null) }
            _streamingDraft.value = ""
            val buffer = StringBuilder()
            var lastUiUpdateMillis = 0L
            try {
                chatStreamingUseCase.ensureToolsLoaded()
                val agent = chatStreamingUseCase.getOrCreateAgent(taskId, taskId)
                agent.sendMessage(cleanText).collect { chunk ->
                    buffer.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdateMillis >= 48) {
                        _streamingDraft.value = buffer.toString()
                        lastUiUpdateMillis = now
                    }
                }
                if (buffer.isNotEmpty()) {
                    _streamingDraft.value = buffer.toString()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e) }
                _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send message"))
            } finally {
                _streamingDraft.value = ""
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun confirmPlan(taskId: String) {
        viewModelScope.launch {
            try {
                val task = latestTask(taskId) ?: return@launch
                taskSagaCoordinator.getOrCreateSaga(task).confirmPlan()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e) }
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
                            _uiState.update { it.copy(isSending = true, error = null) }
                            try {
                                saga.start()
                            } catch (e: Exception) {
                                _uiState.update { it.copy(error = e) }
                                _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga start failed"))
                            } finally {
                                _uiState.update { it.copy(isSending = false) }
                            }
                        } else {
                            _uiState.update { it.copy(isSending = true, error = null) }
                            _streamingDraft.value = ""
                            val buffer = StringBuilder()
                            var lastUiUpdateMillis = 0L
                            try {
                                chatStreamingUseCase.ensureToolsLoaded()
                                val agent = chatStreamingUseCase.getOrCreateAgent(taskId, taskId)
                                agent.sendWelcomeMessage().collect { chunk ->
                                    buffer.append(chunk)
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdateMillis >= 48) {
                                        _streamingDraft.value = buffer.toString()
                                        lastUiUpdateMillis = now
                                    }
                                }
                                if (buffer.isNotEmpty()) {
                                    _streamingDraft.value = buffer.toString()
                                }
                            } catch (e: Exception) {
                                _uiState.update { it.copy(error = e) }
                                _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send welcome"))
                            } finally {
                                _streamingDraft.value = ""
                                _uiState.update { it.copy(isSending = false) }
                            }
                        }
                    } else if (refreshed.isReadyToRun) {
                        _uiState.update { it.copy(isSending = true, error = null) }
                        try {
                            saga.start()
                        } catch (e: Exception) {
                            _uiState.update { it.copy(error = e) }
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga resume failed"))
                        } finally {
                            _uiState.update { it.copy(isSending = false) }
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
                        _uiState.update { it.copy(isSending = true, error = null) }
                        try {
                            saga.start()
                        } catch (e: Exception) {
                            _uiState.update { it.copy(error = e) }
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga start failed"))
                        } finally {
                            _uiState.update { it.copy(isSending = false) }
                        }
                    } else {
                        _uiState.update { it.copy(isSending = true, error = null) }
                        _streamingDraft.value = ""
                        val buffer = StringBuilder()
                        var lastUiUpdateMillis = 0L
                        try {
                            chatStreamingUseCase.ensureToolsLoaded()
                            val agent = chatStreamingUseCase.getOrCreateAgent(taskId, taskId)
                            agent.sendWelcomeMessage().collect { chunk ->
                                buffer.append(chunk)
                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdateMillis >= 48) {
                                    _streamingDraft.value = buffer.toString()
                                    lastUiUpdateMillis = now
                                }
                            }
                            if (buffer.isNotEmpty()) {
                                _streamingDraft.value = buffer.toString()
                            }
                        } catch (e: Exception) {
                            _uiState.update { it.copy(error = e) }
                            _effects.emit(TaskEffect.ShowError(e.message ?: "Failed to send welcome"))
                        } finally {
                            _streamingDraft.value = ""
                            _uiState.update { it.copy(isSending = false) }
                        }
                    }
                } else if (refreshed.isReadyToRun) {
                    _uiState.update { it.copy(isSending = true, error = null) }
                    try {
                        saga.start()
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = e) }
                        _effects.emit(TaskEffect.ShowError(e.message ?: "Task saga resume failed"))
                    } finally {
                        _uiState.update { it.copy(isSending = false) }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e) }
            }
        }
    }

    fun resetTask(taskId: String) {
        viewModelScope.launch {
            try {
                _streamingDraft.value = ""
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
                _uiState.update { it.copy(error = e) }
            }
        }
    }
}
