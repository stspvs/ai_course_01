package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun sendMessage(
        message: String, 
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String>

    fun chatStreaming(
        message: String, 
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>>
}
