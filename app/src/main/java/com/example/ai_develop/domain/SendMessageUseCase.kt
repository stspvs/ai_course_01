package com.example.ai_develop.domain

import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val repository: ChatRepository
) {
    suspend operator fun invoke(message: String, systemPrompt: String): Result<String> {
        if (message.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty"))
        }
        return repository.sendMessage(message, systemPrompt)
    }
}
