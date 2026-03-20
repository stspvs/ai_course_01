package com.example.ai_develop.domain

import com.example.ai_develop.presentation.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun sendMessage(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String>

    fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>>
}
