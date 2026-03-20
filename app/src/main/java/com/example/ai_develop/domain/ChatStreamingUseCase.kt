package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ChatStreamingUseCase @Inject constructor(
    private val repository: ChatRepository
) {
    operator fun invoke(message: String, systemPrompt: String): Flow<Result<String>> {
        if (message.isBlank()) {
            return flowOf(Result.failure(IllegalArgumentException("Message cannot be empty")))
        }
        return repository.chatStreaming(message, systemPrompt)
    }
}
