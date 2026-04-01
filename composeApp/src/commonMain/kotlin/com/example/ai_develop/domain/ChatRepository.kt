package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>>

    suspend fun extractFacts(
        messages: List<ChatMessage>,
        currentFacts: ChatFacts,
        provider: LLMProvider
    ): Result<ChatFacts>

    suspend fun summarize(
        messages: List<ChatMessage>,
        previousSummary: String?,
        instruction: String,
        provider: LLMProvider
    ): Result<String>
}
