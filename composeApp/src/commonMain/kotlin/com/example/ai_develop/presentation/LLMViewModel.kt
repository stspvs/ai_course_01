package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.util.currentTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LLMViewModel(
    private val chatStreamingUseCase: ChatStreamingUseCase,
    private val repository: DatabaseChatRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val agentManager = AgentManager()
    private var currentJob: Job? = null

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    init {
        observeAgents()
    }

    private fun observeAgents() {
        viewModelScope.launch {
            repository.getAgents().collect { dbAgents ->
                _state.update { currentState ->
                    if (dbAgents.isEmpty()) {
                        val defaultAgent = currentState.agents.first()
                        viewModelScope.launch { repository.saveAgent(defaultAgent) }
                        currentState
                    } else {
                        // Обновляем только метаданные агентов, не трогая сообщения в памяти
                        val updatedAgents = dbAgents.map { dbAgent ->
                            val existingAgent = currentState.agents.find { it.id == dbAgent.id }
                            if (existingAgent != null) {
                                dbAgent.copy(messages = existingAgent.messages)
                            } else {
                                dbAgent
                            }
                        }
                        currentState.copy(agents = updatedAgents)
                    }
                }
            }
        }
        
        // Отдельно следим за сообщениями выбранного агента
        viewModelScope.launch {
            _state.map { it.selectedAgentId }.distinctUntilChanged().collectLatest { id ->
                if (id != null) {
                    repository.getAgentWithMessages(id).collect { fullAgent ->
                        if (fullAgent != null) {
                            _state.update { currentState ->
                                val updatedAgents = currentState.agents.map {
                                    if (it.id == id) fullAgent else it
                                }
                                currentState.copy(agents = updatedAgents)
                            }
                        }
                    }
                }
            }
        }
    }

    fun createAgent() {
        val currentProvider = _state.value.selectedAgent?.provider ?: LLMProvider.Yandex()
        val newAgent = agentManager.createDefaultAgent(currentProvider)
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
        _state.update { it.copy(selectedAgentId = newAgent.id) }
    }

    fun updateAgent(
        agentId: String,
        name: String,
        systemPrompt: String,
        temperature: Double,
        provider: LLMProvider,
        stopWord: String,
        maxTokens: Int
    ) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = agentManager.updateAgent(agent, name, systemPrompt, temperature, provider, stopWord, maxTokens)
        viewModelScope.launch { repository.saveAgentMetadata(updatedAgent) }
    }

    fun duplicateAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return
        
        val agentToDuplicate = _state.value.agents.find { it.id == agentId }
        if (agentToDuplicate != null) {
            val duplicatedAgent = agentToDuplicate.copy(
                id = Uuid.random().toString(),
                name = "${agentToDuplicate.name} (копия)",
                messages = emptyList(), 
                totalTokensUsed = 0
            )
            viewModelScope.launch { repository.saveAgent(duplicatedAgent) }
            _state.update { it.copy(selectedAgentId = duplicatedAgent.id) }
        }
    }

    fun deleteAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return
        
        viewModelScope.launch {
            repository.deleteAgent(agentId)
            _state.update { currentState ->
                val newSelectedId = if (currentState.selectedAgentId == agentId) GENERAL_CHAT_ID else currentState.selectedAgentId
                currentState.copy(selectedAgentId = newSelectedId)
            }
        }
    }

    fun selectAgent(agentId: String?) {
        currentJob?.cancel()
        val newId = agentId ?: GENERAL_CHAT_ID
        _state.update { it.copy(selectedAgentId = newId, isLoading = false) }
    }

    fun updateStreamingEnabled(isEnabled: Boolean) {
        _state.update { it.copy(isStreamingEnabled = isEnabled) }
    }

    fun updateSendFullHistory(sendFull: Boolean) {
        _state.update { it.copy(sendFullHistory = sendFull) }
    }

    fun clearChat() {
        currentJob?.cancel()
        val agent = _state.value.selectedAgent ?: return
        viewModelScope.launch {
            repository.saveAgent(agent.copy(messages = emptyList(), totalTokensUsed = 0))
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val tokenCount = estimateTokens(message)
        val timestamp = currentTimeMillis()
        val userMessage = ChatMessage(
            message = message, 
            source = SourceType.USER, 
            tokenCount = tokenCount,
            timestamp = timestamp
        )
        val currentAgent = _state.value.selectedAgent ?: return
        val agentId = currentAgent.id

        // 1. Локальное обновление для мгновенного отклика
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(
                    messages = agent.messages + userMessage,
                    totalTokensUsed = agent.totalTokensUsed + tokenCount
                ) else agent
            }
            state.copy(agents = updatedAgents, isLoading = true)
        }

        // 2. Сохранение в БД
        viewModelScope.launch {
            repository.saveMessage(agentId, userMessage)
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            var currentContent = ""

            // Получаем актуальный список сообщений из состояния (включая только что добавленное)
            val history = _state.value.selectedAgent?.messages ?: listOf(userMessage)

            val flow = chatStreamingUseCase(
                messages = if (_state.value.sendFullHistory) history else listOf(userMessage),
                systemPrompt = currentAgent.systemPrompt,
                maxTokens = currentAgent.maxTokens,
                temperature = currentAgent.temperature,
                stopWord = currentAgent.stopWord,
                isJsonMode = _state.value.isJsonMode,
                provider = currentAgent.provider
            )

            if (_state.value.isStreamingEnabled) {
                flow.onStart {
                    _state.update { it.copy(isLoading = false) }
                    val initialBotMessage = ChatMessage(
                        id = botMessageId, 
                        message = "", 
                        source = SourceType.ASSISTANT,
                        timestamp = currentTimeMillis()
                    )
                    addBotMessageLocally(agentId, initialBotMessage)
                }
                .onCompletion {
                    _state.update { it.copy(isLoading = false) }
                    val finalTokens = estimateTokens(currentContent)
                    val finalBotMessage = ChatMessage(
                        id = botMessageId,
                        message = currentContent,
                        source = SourceType.ASSISTANT,
                        tokenCount = finalTokens,
                        timestamp = currentTimeMillis()
                    )
                    repository.saveMessage(agentId, finalBotMessage)
                }
                .collect { result ->
                    result.onSuccess { chunk ->
                        currentContent += chunk
                        updateBotMessageLocally(agentId, botMessageId, currentContent)
                    }.onFailure { error ->
                        val errorMessage = "Ошибка: ${error.message}"
                        updateBotMessageLocally(agentId, botMessageId, currentContent + "\n[$errorMessage]")
                    }
                }
            } else {
                var fullResponse = ""
                var errorOccurred: Throwable? = null
                
                flow.collect { result ->
                    result.onSuccess { chunk -> fullResponse += chunk }.onFailure { error -> errorOccurred = error }
                }
                
                _state.update { it.copy(isLoading = false) }
                val finalMessageText = if (errorOccurred != null) fullResponse + "\n[Ошибка: ${errorOccurred.message}]" else fullResponse
                val finalTokens = estimateTokens(finalMessageText)
                val botMessage = ChatMessage(
                    id = botMessageId, 
                    message = finalMessageText, 
                    source = SourceType.ASSISTANT, 
                    tokenCount = finalTokens,
                    timestamp = currentTimeMillis()
                )
                
                repository.saveMessage(agentId, botMessage)
            }
        }
    }

    private fun addBotMessageLocally(agentId: String, message: ChatMessage) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(messages = agent.messages + message) else agent
            }
            state.copy(agents = updatedAgents)
        }
    }

    private fun updateBotMessageLocally(agentId: String, messageId: String, newMessage: String) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    val updatedMessages = agent.messages.map { msg ->
                        if (msg.id == messageId) msg.copy(message = newMessage) else msg
                    }
                    agent.copy(messages = updatedMessages)
                } else agent
            }
            state.copy(agents = updatedAgents)
        }
    }
}
