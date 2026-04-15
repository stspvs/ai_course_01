package com.example.ai_develop.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed class LLMProvider {
    abstract val model: String
    abstract val name: String

    @Serializable
    @SerialName("deepseek")
    data class DeepSeek(
        override val model: String = "deepseek-chat"
    ) : LLMProvider() {
        override val name: String = "DeepSeek"
    }

    @Serializable
    @SerialName("yandex")
    data class Yandex(
        override val model: String = "yandexgpt/latest"
    ) : LLMProvider() {
        override val name: String = "Yandex"
    }

    @Serializable
    @SerialName("openrouter")
    data class OpenRouter(
        override val model: String = "google/gemini-2.0-flash-001"
    ) : LLMProvider() {
        override val name: String = "OpenRouter"
    }

    @Serializable
    @SerialName("ollama")
    data class Ollama(
        override val model: String = "deepseek-r1:8b"
    ) : LLMProvider() {
        override val name: String = "Ollama"
    }
}
