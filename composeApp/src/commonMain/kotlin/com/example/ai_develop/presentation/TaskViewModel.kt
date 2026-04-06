package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val chatRepository: ChatRepository,
    private val localRepository: LocalChatRepository,
    private val memoryManager: ChatMemoryManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskContext>>(emptyList())
    val tasks: StateFlow<List<TaskContext>> = _tasks.asStateFlow()

    private val _selectedTaskId = MutableStateFlow<String?>(null)
    val selectedTaskId: StateFlow<String?> = _selectedTaskId.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private var activeSaga: TaskSaga? = null
    val activeSagaContext = _selectedTaskId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else localRepository.getTasks().map { tasks -> tasks.find { it.taskId == id } }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val taskMessages = _selectedTaskId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else localRepository.getMessagesForTask(id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            localRepository.getTasks().collect { _tasks.value = it }
        }
        viewModelScope.launch {
            localRepository.getAgents().collect { _agents.value = it }
        }
    }

    fun createTask(title: String) {
        viewModelScope.launch {
            val agents = localRepository.getAgents().first()
            val architect = agents.firstOrNull() 
            val newTask = TaskContext(
                taskId = Uuid.random().toString(),
                title = title,
                state = AgentTaskState(TaskState.PLANNING, architect ?: createDummyAgent()),
                architectAgentId = architect?.id,
                isPaused = true // Новые задачи создаем на паузе
            )
            localRepository.saveTask(newTask)
            selectTask(newTask.taskId)
        }
    }

    private fun createDummyAgent() = Agent(name = "Architect", systemPrompt = "", temperature = 0.7, provider = LLMProvider.DeepSeek(), stopWord = "", maxTokens = 2000)

    fun selectTask(taskId: String?) {
        _selectedTaskId.value = taskId
        viewModelScope.launch {
            if (taskId != null) {
                initSaga(taskId)
            } else {
                activeSaga?.stop()
                activeSaga = null
            }
        }
    }

    private suspend fun initSaga(taskId: String) {
        val agents = localRepository.getAgents().first()
        val task = localRepository.getTasks().first().find { it.taskId == taskId } ?: return
        val arch = agents.find { it.id == task.architectAgentId }
        val exec = agents.find { it.id == task.executorAgentId }
        val valr = agents.find { it.id == task.validatorAgentId }
        
        activeSaga?.stop()
        activeSaga = TaskSaga(chatRepository, localRepository, arch, exec, valr, task, memoryManager, ioDispatcher)
        activeSaga?.start()
    }

    fun deleteTask(task: TaskContext) {
        viewModelScope.launch {
            localRepository.deleteTask(task)
            localRepository.deleteMessagesForTask(task.taskId)
            if (_selectedTaskId.value == task.taskId) {
                _selectedTaskId.value = null
                activeSaga?.stop()
                activeSaga = null
            }
        }
    }

    fun updateTask(task: TaskContext) {
        viewModelScope.launch {
            localRepository.saveTask(task)
            // Если обновляемая задача выбрана, переинициализируем сагу, чтобы подхватить новых агентов
            if (_selectedTaskId.value == task.taskId) {
                val isRunning = activeSaga?.context?.value?.isPaused == false
                initSaga(task.taskId)
                if (isRunning) activeSaga?.resume()
            }
        }
    }

    fun togglePause(taskId: String) {
        viewModelScope.launch {
            // Убеждаемся, что сага инициализирована
            if (activeSaga == null || activeSaga?.context?.value?.taskId != taskId) {
                initSaga(taskId)
            }
            
            val task = localRepository.getTasks().first().find { it.taskId == taskId } ?: return@launch
            if (task.isPaused) {
                activeSaga?.resume()
            } else {
                activeSaga?.pause()
            }
        }
    }

    fun resetTask(taskId: String) {
        activeSaga?.reset()
    }

    fun sendUserMessage(taskId: String, message: String) {
        viewModelScope.launch {
            if (activeSaga == null || activeSaga?.context?.value?.taskId != taskId) {
                initSaga(taskId)
            }
            activeSaga?.handleUserMessage(message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeSaga?.stop()
    }
}
