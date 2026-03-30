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
        
        // Сначала обновляем локальный стейт, чтобы UI сразу увидел нового агента
        _state.update { it.copy(
            agents = it.agents + newAgent,
            selectedAgentId = newAgent.id 
        ) }
        
        viewModelScope.launch { repository.saveAgentMetadata(newAgent) }
    }

    fun updateAgent(
        agentId: String,
        name: String,
        systemPrompt: String,
        temperature: Double,
        provider: LLMProvider,
        stopWord: String,
        maxTokens: Int,
        keepLastMessagesCount: Int,
        summaryPrompt: String,
        summaryDepth: SummaryDepth
    ) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val updatedAgent = agentManager.updateAgent(
            agent, name, systemPrompt, temperature, provider, stopWord, maxTokens, keepLastMessagesCount, summaryPrompt, summaryDepth
        )
        
        // Локальное обновление для отзывчивости
        _state.update { currentState ->
            val newAgents = currentState.agents.map { if (it.id == agentId) updatedAgent else it }
            currentState.copy(agents = newAgents)
        }
        
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
                totalTokensUsed = 0,
                summary = null
            )
            
            _state.update { it.copy(
                agents = it.agents + duplicatedAgent,
                selectedAgentId = duplicatedAgent.id
            ) }
            
            viewModelScope.launch { repository.saveAgent(duplicatedAgent) }
        }
    }

    fun deleteAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return
        
        viewModelScope.launch {
            repository.deleteAgent(agentId)
            _state.update { currentState ->
                val newSelectedId = if (currentState.selectedAgentId == agentId) GENERAL_CHAT_ID else currentState.selectedAgentId
                currentState.copy(
                    agents = currentState.agents.filter { it.id != agentId },
                    selectedAgentId = newSelectedId
                )
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
            repository.saveAgent(agent.copy(messages = emptyList(), totalTokensUsed = 0, summary = null))
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

        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(
                    messages = agent.messages + userMessage,
                    totalTokensUsed = agent.totalTokensUsed + tokenCount
                ) else agent
            }
            state.copy(agents = updatedAgents, isLoading = true)
        }

        viewModelScope.launch {
            repository.saveMessage(agentId, userMessage)
            checkAndSummarize(agentId)
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            var currentContent = ""

            val agentAfterSummary = _state.value.agents.find { it.id == agentId } ?: currentAgent
            val history = prepareHistoryWithSummary(agentAfterSummary, userMessage)

            val flow = chatStreamingUseCase(
                messages = history,
                systemPrompt = agentAfterSummary.systemPrompt,
                maxTokens = agentAfterSummary.maxTokens,
                temperature = agentAfterSummary.temperature,
                stopWord = agentAfterSummary.stopWord,
                isJsonMode = _state.value.isJsonMode,
                provider = agentAfterSummary.provider
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
                    val finalTokens = estimateTokens(currentContent)
                    val finalBotMessage = ChatMessage(
                        id = botMessageId,
                        message = currentContent,
                        source = SourceType.ASSISTANT,
                        tokenCount = finalTokens,
                        timestamp = currentTimeMillis()
                    )
                    _state.update { state ->
                        val updatedAgents = state.agents.map { agent ->
                            if (agent.id == agentId) {
                                val updatedMessages = agent.messages.map { msg ->
                                    if (msg.id == botMessageId) finalBotMessage else msg
                                }
                                agent.copy(
                                    messages = updatedMessages,
                                    totalTokensUsed = agent.totalTokensUsed + finalTokens
                                )
                            } else agent
                        }
                        state.copy(agents = updatedAgents, isLoading = false)
                    }
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
                
                val finalMessageText = if (errorOccurred != null) fullResponse + "\n[Ошибка: ${errorOccurred.message}]" else fullResponse
                val finalTokens = estimateTokens(finalMessageText)
                val botMessage = ChatMessage(
                    id = botMessageId, 
                    message = finalMessageText, 
                    source = SourceType.ASSISTANT, 
                    tokenCount = finalTokens,
                    timestamp = currentTimeMillis()
                )
                
                _state.update { state ->
                    val updatedAgents = state.agents.map { agent ->
                        if (agent.id == agentId) agent.copy(
                            messages = agent.messages + botMessage,
                            totalTokensUsed = agent.totalTokensUsed + finalTokens
                        ) else agent
                    }
                    state.copy(agents = updatedAgents, isLoading = false)
                }
                repository.saveMessage(agentId, botMessage)
            }
        }
    }

    private fun prepareHistoryWithSummary(agent: Agent, lastUserMessage: ChatMessage): List<ChatMessage> {
        if (!_state.value.sendFullHistory) return listOf(lastUserMessage)

        val messages = agent.messages.filter { !it.isSystemNotification }
        val keepCount = agent.keepLastMessagesCount
        
        if (messages.size <= keepCount) return messages

        val lastMessages = messages.takeLast(keepCount)
        val summary = agent.summary ?: return messages

        val summaryMessage = ChatMessage(
            message = "Контекст предыдущей части беседы: $summary",
            source = SourceType.SYSTEM,
            timestamp = 0L
        )

        return listOf(summaryMessage) + lastMessages
    }

    private suspend fun checkAndSummarize(agentId: String) {
        val agent = _state.value.agents.find { it.id == agentId } ?: return
        val messages = agent.messages.filter { !it.isSystemNotification }
        val keepCount = agent.keepLastMessagesCount

        if (messages.size > keepCount + 3) {
            val toSummarize = messages.dropLast(keepCount)
            val chatText = toSummarize.joinToString("\n") { "${it.source.role}: ${it.message}" }
            
            val depthInstruction = when(agent.summaryDepth) {
                SummaryDepth.LOW -> "Будь максимально кратким, выдели только самую суть в 1-2 предложениях."
                SummaryDepth.MEDIUM -> "Сделай краткий обзор основных тем и решений."
                SummaryDepth.HIGH -> "Подробно опиши ход обсуждения, все важные детали и контекст."
            }
            
            val fullSummaryPrompt = "${agent.summaryPrompt}\n\nИнструкция по глубине: $depthInstruction\n\nДиалог для суммаризации:\n$chatText"
            
            val summaryFlow = chatStreamingUseCase(
                messages = listOf(ChatMessage(message = fullSummaryPrompt, source = SourceType.USER)),
                systemPrompt = "You are a helpful assistant specializing in conversation summarization.",
                maxTokens = 1000,
                temperature = 0.3,
                stopWord = "",
                isJsonMode = false,
                provider = agent.provider
            )

            var summaryResult = ""
            summaryFlow.collect { result ->
                result.onSuccess { chunk -> summaryResult += chunk }
            }

            if (summaryResult.isNotBlank()) {
                val updatedAgent = agent.copy(summary = summaryResult)
                
                // Сначала в стейт
                _state.update { currentState ->
                    val newAgents = currentState.agents.map { if (it.id == agentId) updatedAgent else it }
                    currentState.copy(agents = newAgents)
                }
                
                repository.saveAgentMetadata(updatedAgent)
                
                val notification = ChatMessage(
                    message = "Контекст диалога был сжат для оптимизации (глубина: ${agent.summaryDepth.description})",
                    source = SourceType.SYSTEM,
                    timestamp = currentTimeMillis(),
                    isSystemNotification = true
                )
                repository.saveMessage(agentId, notification)
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
                        if (msg.id == messageId) msg.copy(
                            message = newMessage,
                            tokenCount = estimateTokens(newMessage)
                        ) else msg
                    }
                    agent.copy(messages = updatedMessages)
                } else agent
            }
            state.copy(agents = updatedAgents)
        }
    }
}
