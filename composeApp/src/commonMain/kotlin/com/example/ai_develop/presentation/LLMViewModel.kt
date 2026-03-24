package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.LLMProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LLMViewModel(
    private val chatStreamingUseCase: ChatStreamingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private val agentManager = AgentManager()
    private var currentJob: Job? = null

    val agentTemplates: List<AgentTemplate> = agentManager.templates

    // --- Управление агентами ---

    fun createAgent() {
        val currentProvider = _state.value.selectedAgent?.provider ?: LLMProvider.Yandex()
        val newAgent = agentManager.createDefaultAgent(currentProvider)
        _state.update { it.copy(
            agents = it.agents + newAgent,
            selectedAgentId = newAgent.id 
        ) }
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
        _state.update { currentState ->
            val updatedAgents = currentState.agents.map { agent ->
                if (agent.id == agentId) {
                    agentManager.updateAgent(agent, name, systemPrompt, temperature, provider, stopWord, maxTokens)
                } else agent
            }
            currentState.copy(agents = updatedAgents)
        }
    }

    fun duplicateAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return // Не дублируем общий чат (или можно разрешить, но обычно не нужно)
        
        _state.update { currentState ->
            val agentToDuplicate = currentState.agents.find { it.id == agentId }
            if (agentToDuplicate != null) {
                val duplicatedAgent = agentToDuplicate.copy(
                    id = Uuid.random().toString(),
                    name = "${agentToDuplicate.name} (копия)",
                    messages = emptyList(), 
                    totalTokensUsed = 0
                )
                currentState.copy(
                    agents = currentState.agents + duplicatedAgent,
                    selectedAgentId = duplicatedAgent.id
                )
            } else {
                currentState
            }
        }
    }

    fun deleteAgent(agentId: String) {
        if (agentId == GENERAL_CHAT_ID) return // Запрет удаления общего чата
        
        _state.update { currentState ->
            val updatedAgents = currentState.agents.filter { it.id != agentId }
            val newSelectedId = if (currentState.selectedAgentId == agentId) GENERAL_CHAT_ID else currentState.selectedAgentId
            currentState.copy(agents = updatedAgents, selectedAgentId = newSelectedId)
        }
    }

    fun selectAgent(agentId: String?) {
        currentJob?.cancel()
        _state.update { it.copy(selectedAgentId = agentId ?: GENERAL_CHAT_ID, isLoading = false) }
    }

    fun updateStreamingEnabled(isEnabled: Boolean) {
        _state.update { it.copy(isStreamingEnabled = isEnabled) }
    }

    fun updateSendFullHistory(sendFull: Boolean) {
        _state.update { it.copy(sendFullHistory = sendFull) }
    }

    fun clearChat() {
        currentJob?.cancel()
        val agentId = _state.value.selectedAgentId
        if (agentId != null) {
            _state.update { currentState ->
                val updatedAgents = currentState.agents.map { agent ->
                    if (agent.id == agentId) agent.copy(messages = emptyList(), totalTokensUsed = 0) else agent
                }
                currentState.copy(agents = updatedAgents, isLoading = false)
            }
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val tokenCount = estimateTokens(message)
        val userMessage = ChatMessage(message = message, source = SourceType.USER, tokenCount = tokenCount)
        val agentId = _state.value.selectedAgentId ?: GENERAL_CHAT_ID

        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    agent.copy(
                        messages = agent.messages + userMessage,
                        totalTokensUsed = agent.totalTokensUsed + tokenCount
                    )
                } else agent
            }
            state.copy(agents = updatedAgents, isLoading = true)
        }

        val currentState = _state.value
        val currentAgent = currentState.agents.find { it.id == agentId } ?: return

        currentJob?.cancel()

        currentJob = viewModelScope.launch {
            val botMessageId = Uuid.random().toString()
            var currentContent = ""

            val systemPrompt = currentAgent.systemPrompt
            val temperature = currentAgent.temperature
            val maxTokens = currentAgent.maxTokens
            val provider = currentAgent.provider
            val stopWord = currentAgent.stopWord
            
            val messagesInContext = currentAgent.messages

            val messagesToSend = if (currentState.sendFullHistory) {
                messagesInContext
            } else {
                listOf(userMessage)
            }

            val flow = chatStreamingUseCase(
                messages = messagesToSend,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                stopWord = stopWord,
                isJsonMode = currentState.isJsonMode,
                provider = provider
            )

            if (currentState.isStreamingEnabled) {
                flow.onStart {
                    _state.update { it.copy(isLoading = false) }
                    val initialBotMessage = ChatMessage(
                        id = botMessageId,
                        message = "",
                        source = SourceType.ASSISTANT
                    )
                    addBotMessage(agentId, initialBotMessage)
                }
                .onCompletion {
                    _state.update { it.copy(isLoading = false) }
                    val finalTokens = estimateTokens(currentContent)
                    updateBotTokens(agentId, botMessageId, finalTokens)
                }
                .collect { result ->
                    result.onSuccess { chunk ->
                        currentContent += chunk
                        updateBotMessage(agentId, botMessageId, currentContent)
                    }.onFailure { error ->
                        val errorMessage = "Ошибка: ${error.message}"
                        updateBotMessage(agentId, botMessageId, currentContent + "\n[$errorMessage]")
                    }
                }
            } else {
                var fullResponse = ""
                var errorOccurred: Throwable? = null
                
                flow.collect { result ->
                    result.onSuccess { chunk ->
                        fullResponse += chunk
                    }.onFailure { error ->
                        errorOccurred = error
                    }
                }
                
                _state.update { it.copy(isLoading = false) }
                val finalMessageText = if (errorOccurred != null) {
                    fullResponse + "\n[Ошибка: ${errorOccurred.message}]"
                } else {
                    fullResponse
                }
                
                val finalTokens = estimateTokens(finalMessageText)
                val botMessage = ChatMessage(
                    id = botMessageId,
                    message = finalMessageText,
                    source = SourceType.ASSISTANT,
                    tokenCount = finalTokens
                )
                addBotMessage(agentId, botMessage)
                updateTotalTokens(agentId, finalTokens)
            }
        }
    }

    private fun addBotMessage(agentId: String, message: ChatMessage) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(messages = agent.messages + message) else agent
            }
            state.copy(agents = updatedAgents)
        }
    }

    private fun updateBotMessage(agentId: String, messageId: String, newMessage: String) {
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

    private fun updateBotTokens(agentId: String, messageId: String, tokens: Int) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) {
                    val updatedMessages = agent.messages.map { msg ->
                        if (msg.id == messageId) msg.copy(tokenCount = tokens) else msg
                    }
                    agent.copy(messages = updatedMessages, totalTokensUsed = agent.totalTokensUsed + tokens)
                } else agent
            }
            state.copy(agents = updatedAgents)
        }
    }

    private fun updateTotalTokens(agentId: String, tokens: Int) {
        _state.update { state ->
            val updatedAgents = state.agents.map { agent ->
                if (agent.id == agentId) agent.copy(totalTokensUsed = agent.totalTokensUsed + tokens) else agent
            }
            state.copy(agents = updatedAgents)
        }
    }
}
