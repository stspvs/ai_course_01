package com.example.ai_develop.presentation

import com.example.ai_develop.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AgentManager {
    val templates = listOf(
        AgentTemplate(
            name = "Переводчик",
            description = "Профессиональный переводчик, сохраняющий контекст и стиль.",
            systemPrompt = "Ты — опытный переводчик. Твоя задача — максимально точно переводить текст, сохраняя оригинальный смысл, стиль и нюансы языка. Если в тексте есть идиомы, предлагай наиболее подходящие эквиваленты.",
            temperature = 0.3
        ),
        AgentTemplate(
            name = "Android разработчик",
            description = "Эксперт в Kotlin, Compose и архитектуре Android.",
            systemPrompt = "Ты — Senior Android Developer. Ты отлично разбираешься в Kotlin, Jetpack Compose, Coroutines и современных архитектурных паттернах (MVVM, MVI). Твои ответы должны содержать чистый, идиоматичный код и лучшие практики разработки.",
            temperature = 0.5
        ),
        AgentTemplate(
            name = "Репетитор английского",
            description = "Помогает учить язык, исправляет ошибки и объясняет правила.",
            systemPrompt = "Ты — дружелюбный репетитор английского языка. Твоя цель — помогать пользователю практиковать язык. Исправляй ошибки в его сообщениях, объясняй грамматику и предлагай новые слова для изучения. Общайся преимущественно на английском, но давай объяснения на русском, если это необходимо.",
            temperature = 0.8
        ),
        AgentTemplate(
            name = "Креативный писатель",
            description = "Генерирует идеи, пишет рассказы и стихи.",
            systemPrompt = "Ты — талантливый писатель и поэт. Ты мастерски владеешь словом, создаешь яркие образы и захватывающие сюжеты. Будь креативным, используй богатый словарный запас и необычные метафоры.",
            temperature = 1.2
        ),
        AgentTemplate(
            name = "Психолог-консультант",
            description = "Эмпатичный слушатель, помогает разобраться в чувствах.",
            systemPrompt = "Ты — эмпатичный и поддерживающий психолог. Твоя задача — выслушать пользователя, проявить понимание и помочь ему разобраться в своих эмоциях. Не давай прямых советов, если тебя об этом не просят, используй техники активного слушания.",
            temperature = 0.7
        )
    )

    fun createDefaultAgent(provider: LLMProvider): Agent {
        return Agent(
            name = "Новый агент",
            systemPrompt = "You are a helpful assistant.",
            temperature = 1.0,
            provider = provider,
            stopWord = "",
            maxTokens = 2000,
            messages = emptyList(),
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10)
        )
    }

    fun updateAgent(
        agent: Agent,
        name: String,
        systemPrompt: String,
        temperature: Double,
        provider: LLMProvider,
        stopWord: String,
        maxTokens: Int,
        memoryStrategy: ChatMemoryStrategy
    ): Agent {
        return agent.copy(
            name = name,
            systemPrompt = systemPrompt,
            temperature = temperature,
            provider = provider,
            stopWord = stopWord,
            maxTokens = maxTokens,
            memoryStrategy = memoryStrategy
        )
    }

    fun duplicateAgent(agent: Agent): Agent {
        return agent.copy(
            id = Uuid.random().toString(),
            name = "${agent.name} (Copy)",
            messages = emptyList(),
            totalTokensUsed = 0,
            branches = emptyList(),
            currentBranchId = null
        )
    }

    fun clearChat(agent: Agent): Agent {
        return agent.copy(
            messages = emptyList(),
            branches = emptyList(),
            currentBranchId = null,
            totalTokensUsed = 0
        )
    }
}
