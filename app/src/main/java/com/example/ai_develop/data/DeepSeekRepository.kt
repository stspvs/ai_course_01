package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekRepository @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI
) : ChatRepository {

    override suspend fun sendMessage(message: String, systemPrompt: String): Result<String> {
        return deepSeekClient.sendMessage(message, systemPrompt)
    }

    override fun chatStreaming(message: String, systemPrompt: String): Flow<Result<String>> {
        return deepSeekClient.chatStreaming(message, systemPrompt)
    }
}
