package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.ChatStreamingUseCase
import com.example.ai_develop.domain.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val sendMessageUseCase: SendMessageUseCase,
    private val chatStreamingUseCase: ChatStreamingUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(LLMStateModel())
    val state: StateFlow<LLMStateModel> = _state.asStateFlow()

    fun updateSystemPrompt(prompt: String) {
        _state.update { it.copy(systemPrompt = prompt) }
    }

    fun updateMaxTokens(maxTokens: Int) {
        _state.update { it.copy(maxTokens = maxTokens) }
    }

    fun updateStopWord(stopWord: String) {
        _state.update { it.copy(stopWord = stopWord) }
    }

    fun updateJsonMode(isJsonMode: Boolean) {
        _state.update { it.copy(isJsonMode = isJsonMode) }
    }

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val userMessage = ChatMessage(message = message, source = SourceType.USER)
        
        // Сначала добавляем сообщение пользователя в UI стейт
        _state.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isLoading = true
            )
        }

        // Берем актуальный стейт ПОСЛЕ добавления пользовательского сообщения
        val currentState = _state.value

        viewModelScope.launch(Dispatchers.IO) {
            val botMessageId = java.util.UUID.randomUUID().toString()
            var currentContent = ""

            chatStreamingUseCase(
                messages = currentState.messages,
                systemPrompt = currentState.systemPrompt,
                maxTokens = currentState.maxTokens,
                stopWord = currentState.stopWord,
                isJsonMode = currentState.isJsonMode
            )
                .onStart {
                    _state.update { it.copy(isLoading = false) }
                    val initialBotMessage = ChatMessage(
                        id = botMessageId,
                        message = "",
                        source = SourceType.DEEPSEEK
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
