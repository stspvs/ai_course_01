package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.LLMProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class LLMViewModel @Inject constructor(
    private val chatStreamingUseCase: ChatStreamingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    private var currentJob: Job? = null

    fun updateSystemPrompt(prompt: String) {
        _state.update { it.copy(systemPrompt = prompt) }
    }

    fun updateMaxTokens(maxTokens: Int) {
        _state.update { it.copy(maxTokens = maxTokens) }
    }

    fun updateTemperature(temperature: Double) {
        val maxAllowed = if (_state.value.selectedProvider is LLMProvider.DeepSeek) 2.0 else 1.0
        _state.update { it.copy(temperature = temperature.coerceIn(0.0, maxAllowed)) }
    }

    fun updateStopWord(stopWord: String) {
        _state.update { it.copy(stopWord = stopWord) }
    }

    fun updateJsonMode(isJsonMode: Boolean) {
        _state.update { it.copy(isJsonMode = isJsonMode) }
    }

    fun updateProvider(provider: LLMProvider) {
        _state.update { currentState ->
            val maxAllowed = if (provider is LLMProvider.DeepSeek) 2.0 else 1.0
            val newTemp = if (currentState.temperature > maxAllowed) maxAllowed else currentState.temperature
            currentState.copy(
                selectedProvider = provider,
                temperature = newTemp
            )
        }
    }

    fun updateStreamingEnabled(isEnabled: Boolean) {
        _state.update { it.copy(isStreamingEnabled = isEnabled) }
    }

    fun updateSendFullHistory(sendFull: Boolean) {
        _state.update { it.copy(sendFullHistory = sendFull) }
    }

    fun clearChat() {
        currentJob?.cancel()
        _state.update { it.copy(messages = emptyList(), isLoading = false) }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val userMessage = ChatMessage(message = message, source = SourceType.USER)
        
        _state.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true
            )
        }

        val currentState = _state.value

        currentJob?.cancel()

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            val botMessageId = java.util.UUID.randomUUID().toString()
            var currentContent = ""

            val messagesToSend = if (currentState.sendFullHistory) {
                currentState.messages + userMessage
            } else {
                listOf(userMessage)
            }

            val flow = chatStreamingUseCase(
                messages = messagesToSend,
                systemPrompt = currentState.systemPrompt,
                maxTokens = currentState.maxTokens,
                temperature = currentState.temperature,
                stopWord = currentState.stopWord,
                isJsonMode = currentState.isJsonMode,
                provider = currentState.selectedProvider
            )

            if (currentState.isStreamingEnabled) {
                flow.onStart {
                    _state.update { it.copy(isLoading = false) }
                    val initialBotMessage = ChatMessage(
                        id = botMessageId,
                        message = "",
                        source = SourceType.ASSISTANT
                    )
                    _state.update { it.copy(messages = it.messages + initialBotMessage) }
                }
                .onCompletion {
                    _state.update { it.copy(isLoading = false) }
                }
                .collect { result ->
                    result.onSuccess { chunk ->
                        currentContent += chunk
                        updateBotMessage(botMessageId, currentContent)
                    }.onFailure { error ->
                        val errorMessage = "Ошибка: ${error.message}"
                        updateBotMessage(botMessageId, currentContent + "\n[$errorMessage]")
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
                val finalMessage = if (errorOccurred != null) {
                    fullResponse + "\n[Ошибка: ${errorOccurred.message}]"
                } else {
                    fullResponse
                }
                
                val botMessage = ChatMessage(
                    id = botMessageId,
                    message = finalMessage,
                    source = SourceType.ASSISTANT
                )
                _state.update { it.copy(messages = it.messages + botMessage) }
            }
        }
    }

    private fun updateBotMessage(id: String, newMessage: String) {
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages.map { msg ->
                    if (msg.id == id) msg.copy(message = newMessage) else msg
                }
            )
        }
    }
}
