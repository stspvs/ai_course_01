package com.example.ai_develop.domain

import com.example.ai_develop.presentation.ChatMessage
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String> {
        if (messages.isEmpty() || messages.last().message.isBlank()) {
            return Result.failure(IllegalArgumentException("Last message cannot be empty"))
        }
        return repository.sendMessage(messages, systemPrompt, maxTokens, stopWord, isJsonMode)
    }
}
