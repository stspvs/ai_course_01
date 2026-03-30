package com.example.ai_develop.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class LLMProvider {
    abstract val model: String

    @Serializable
    @SerialName("deepseek")
    data class DeepSeek(
        override val model: String = "deepseek-chat"
    ) : LLMProvider()

    @Serializable
    @SerialName("yandex")
    data class Yandex(
        override val model: String = "yandexgpt/latest"
    ) : LLMProvider()

    @Serializable
    @SerialName("openrouter")
    data class OpenRouter(
        override val model: String = "google/gemini-2.0-flash-001"
    ) : LLMProvider()
}
