package com.example.ai_develop.domain

interface ChatRepository {
    suspend fun sendMessage(message: String): Result<String>
}
