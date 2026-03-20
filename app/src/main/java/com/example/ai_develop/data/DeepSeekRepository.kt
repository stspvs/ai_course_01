package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekRepository @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI
) : ChatRepository {

    override suspend fun sendMessage(
        message: String,
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String> {
        return deepSeekClient.sendMessage(
            userMessage = message,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            stopWord = stopWord,
            isJsonMode = isJsonMode
        )
    }

    override fun chatStreaming(
        message: String,
        systemPrompt: String,
        maxTokens: Int,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>> {
        return deepSeekClient.chatStreaming(
            userMessage = message,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            stopWord = stopWord,
            isJsonMode = isJsonMode
        )
    }
}
