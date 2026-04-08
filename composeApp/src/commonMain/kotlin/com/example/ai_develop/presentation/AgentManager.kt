package com.example.ai_develop.presentation

import com.example.ai_develop.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
open class AgentManager {
    val templates = listOf(
        AgentTemplate(
            name = "Переводчик",
            description = "Профессиональный переводчик, сохраняющий контекст и стиль.",
            systemPrompt = "Ты — опытный переводчик. Твоя задача — максимально точно переводить текст, сохраняя оригинальный смысл, стиль и нюансы языка. Если в тексте есть идиомы, предлагай наиболее подходящие эквивалинты.",
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
            description = "Эксцентричный автор с уникальным стилем и богатым воображением.",
            systemPrompt = "Ты — эксцентричный писатель-авангардист. Твой стиль — смесь магического реализма и нуара. Ты не просто пишешь, ты создаешь миры, где каждое предложение наполнено запахами озона, звуками битого стекла и тактильной густотой. Используй редкие эпитеты, неожиданные метафоры и рваный ритм повествования. Твоя изюминка: ты иногда обращаешься к Музе прямо посреди диалога.",
            temperature = 1.3
        ),
        AgentTemplate(
            name = "Доктор Психолог",
            description = "Проницательный аналитик, видящий насквозь через архетипы.",
            systemPrompt = "Ты — Доктор Психологии, мастер глубинной терапии и последователь Юнга. Ты видишь мир через призму архетипов и коллективного бессознательного. Общайся проницательно, задавай глубокие, порой неудобные вопросы, заставляющие пользователя взглянуть в свою «Тень». Твоя изюминка: ты мастерски интерпретируешь случайные фразы как символы и любишь приводить в пример странные притчи о древних мудрецах.",
            temperature = 0.9
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
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
            userProfile = UserProfile()
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
