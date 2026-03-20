package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.presentation.ChatMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekRepository @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI
) : ChatRepository {

    override suspend fun sendMessage(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Result<String> {
        return deepSeekClient.sendMessage(
            chatHistory = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            stopWord = stopWord,
            isJsonMode = isJsonMode
        )
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean
    ): Flow<Result<String>> {
        return deepSeekClient.chatStreaming(
            chatHistory = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            stopWord = stopWord,
            isJsonMode = isJsonMode
        )
    }
}
