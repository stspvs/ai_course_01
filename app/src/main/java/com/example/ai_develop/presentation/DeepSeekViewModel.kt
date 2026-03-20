package com.example.ai_develop.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.domain.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class DeepSeekViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(DeepSeekStateModel())
    val state: StateFlow<DeepSeekStateModel> = _state.asStateFlow()

    fun sendMessage(message: String) {
        if (message.isBlank()) return

        val userMessage = ChatMessage(message = message, source = SourceType.USER)
        
        // Добавляем сообщение пользователя и включаем загрузку
        _state.update { currentState ->
            currentState.copy(
                messages = currentState.messages + userMessage,
                isLoading = true
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = sendMessageUseCase(message)
            val assistantReply = result.getOrElse { "Ошибка: ${it.message}" }

            val botMessage = ChatMessage(
                message = assistantReply,
                source = SourceType.DEEPSEEK
            )

            // РЕШЕНИЕ: Сначала выключаем индикатор загрузки
            _state.update { it.copy(isLoading = false) }
            
            // Даем небольшую паузу (буквально 1 кадр), чтобы UI успел убрать "думающий" бабл
            delay(50) 

            // Теперь добавляем ответ (или ошибку)
            _state.update { currentState ->
                currentState.copy(messages = currentState.messages + botMessage)
            }
        }
    }
}
