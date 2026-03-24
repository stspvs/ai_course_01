package com.example.ai_develop.domain

import kotlinx.serialization.Serializable

@Serializable
sealed class LLMProvider {
    abstract val model: String

    @Serializable
    data class DeepSeek(
        override val model: String = "deepseek-chat"
    ) : LLMProvider()

    @Serializable
    data class Yandex(
        override val model: String = "yandexgpt/latest"
    ) : LLMProvider()

    @Serializable
    data class OpenRouter(
        override val model: String = "google/gemini-2.0-flash-001"
    ) : LLMProvider()
}
