package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatRepository
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.presentation.ChatMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DeepSeekRepository @Inject constructor(
    private val deepSeekClient: DeepSeekClientAPI,
    private val yandexClient: YandexClientAPI,
    private val openRouterClient: OpenRouterClientAPI
) : ChatRepository {

    override fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>> {
        return when (provider) {
            is LLMProvider.DeepSeek -> {
                deepSeekClient.chatStreaming(
                    chatHistory = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    stopWord = stopWord,
                    isJsonMode = isJsonMode,
                    model = provider.model
                )
            }
            is LLMProvider.Yandex -> {
                yandexClient.chatStreaming(
                    chatHistory = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    model = provider.model
                )
            }
            is LLMProvider.OpenRouter -> {
                openRouterClient.chatStreaming(
                    chatHistory = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    stopWord = stopWord,
                    isJsonMode = isJsonMode,
                    model = provider.model
                )
            }
        }
    }
}
