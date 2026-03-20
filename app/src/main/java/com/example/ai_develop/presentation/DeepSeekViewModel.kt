package com.example.ai_develop.presentation

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai_develop.data.DeepSeekClientAPI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class DeepSeekViewModel @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI
) : ViewModel() {

    private val _chatMessages = mutableStateListOf<ChatMessage>()
    val chatMessages: List<ChatMessage> = _chatMessages

    private val _state: MutableSharedFlow<DeepSeekStateModel> = MutableSharedFlow()
    private val state: SharedFlow<DeepSeekStateModel> = _state.asSharedFlow()

    fun sendMessage(message: String) {
        _chatMessages.add(
            ChatMessage(
                message = message,
                source = SourceType.USER
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            val result = deepSeekClient.sendMessage(message)
            val assistantReply = result.getOrElse { "Ошибка: ${it.message}" }
            _chatMessages.add(
                ChatMessage(
                    message = assistantReply,
                    source = SourceType.DEEPSEEK
                )
            )
        }
    }
}
