package com.example.ai_develop.domain

import com.example.ai_develop.presentation.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ChatStreamingUseCase @Inject constructor(
    private val repository: ChatRepository
) {
    operator fun invoke(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>> {
        if (messages.isEmpty() || messages.last().message.isBlank()) {
            return flowOf(Result.failure(IllegalArgumentException("Last message cannot be empty")))
        }
        return repository.chatStreaming(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            stopWord = stopWord,
            isJsonMode = isJsonMode
        )
    }
}
