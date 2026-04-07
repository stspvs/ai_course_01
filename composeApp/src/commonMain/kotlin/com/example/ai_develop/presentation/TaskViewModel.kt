package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val repository: ChatRepository,
    private val localRepository: com.example.ai_develop.data.database.LocalChatRepository,
    private val useCase: ChatStreamingUseCase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    val tasks: StateFlow<List<TaskContext>> = localRepository.getTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    val agents: StateFlow<List<Agent>> = localRepository.getAgents()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Автономный агент для текущей задачи
    private var activeAgent: AutonomousAgent? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSagaContext: StateFlow<TaskContext?> = _selectedTaskId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else tasks.map { list -> list.find { it.taskId == id } }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val taskMessages: StateFlow<List<ChatMessage>> = _selectedTaskId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else localRepository.getMessagesForTask(id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun selectTask(taskId: String?) {
        _selectedTaskId.value = taskId
        if (taskId != null) {
            activeAgent = useCase.getOrCreateAgent(taskId)
        } else {
            activeAgent = null
        }
    }

    fun createTask(title: String) {
        val taskId = Uuid.random().toString()
        viewModelScope.launch {
            val newTask = TaskContext(
                taskId = taskId,
                title = title,
                state = AgentTaskState(TaskState.PLANNING, createDefaultAgent()),
                isPaused = true
            )
            localRepository.saveTask(newTask)
            selectTask(taskId)
        }
    }

    fun deleteTask(task: TaskContext) {
        viewModelScope.launch {
            if (_selectedTaskId.value == task.taskId) {
                selectTask(null)
            }
            localRepository.deleteTask(task)
        }
    }

    fun updateTask(task: TaskContext) {
        viewModelScope.launch {
            localRepository.saveTask(task)
        }
    }

    fun sendUserMessage(taskId: String, text: String) {
        val agent = activeAgent ?: return
        viewModelScope.launch {
            // Сохраняем сообщение пользователя через локальный репозиторий для UI
            localRepository.saveMessage(
                agentId = taskId, 
                message = ChatMessage(message = text, source = SourceType.USER),
                taskId = taskId,
                taskState = activeSagaContext.value?.state?.taskState
            )
            agent.sendMessage(text)
        }
    }

    fun togglePause(taskId: String) {
        val currentTask = activeSagaContext.value ?: return
        updateTask(currentTask.copy(isPaused = !currentTask.isPaused))
    }

    fun resetTask(taskId: String) {
        viewModelScope.launch {
            localRepository.deleteMessagesForTask(taskId)
            val currentTask = activeSagaContext.value ?: return@launch
            updateTask(currentTask.copy(state = currentTask.state.copy(taskState = TaskState.PLANNING), isPaused = true))
        }
    }

    private fun createDefaultAgent() = Agent(
        name = "Default",
        systemPrompt = "You are a helpful assistant.",
        temperature = 0.7,
        provider = LLMProvider.Yandex(),
        stopWord = "",
        maxTokens = 2000
    )
}
