package com.example.ai_develop.domain.llm

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Модель Ollama по умолчанию (новые агенты, переключение провайдера, RAG). */
const val OllamaDefaultModelName = "qwen2.5:1.5b"

/**
 * Имена моделей в выпадающем списке UI для провайдера Ollama (редактор агента, RAG и т.д.).
 */
val OllamaUiModelNames: List<String> = listOf(
    OllamaDefaultModelName,
    "deepseek-r1:8b",
    "qwen2.5:3b",
    "qwen2.5:7b",
    "llama3.2:3b",
    "gpt-oss:20b",
)

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
        override val model: String = OllamaDefaultModelName
    ) : LLMProvider() {
        override val name: String = "Ollama"
    }
}
