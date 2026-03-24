package com.example.ai_develop.domain

import com.example.ai_develop.presentation.ChatMessage
import kotlinx.coroutines.flow.Flow

class ChatStreamingUseCase(
    private val repository: ChatRepository
) {
    operator fun invoke(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>> {
        return repository.chatStreaming(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            stopWord = stopWord,
            isJsonMode = isJsonMode,
            provider = provider
        )
    }
}
