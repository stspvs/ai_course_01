package com.example.ai_develop.domain

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun sendMessage(message: String, systemPrompt: String): Result<String>
    fun chatStreaming(message: String, systemPrompt: String): Flow<Result<String>>
}
