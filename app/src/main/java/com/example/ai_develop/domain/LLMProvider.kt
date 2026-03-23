package com.example.ai_develop.domain

sealed class LLMProvider {
    abstract val model: String

    data class DeepSeek(
        override val model: String = "deepseek-chat"
    ) : LLMProvider()

    data class Yandex(
        override val model: String = "yandexgpt/latest"
    ) : LLMProvider()
}
