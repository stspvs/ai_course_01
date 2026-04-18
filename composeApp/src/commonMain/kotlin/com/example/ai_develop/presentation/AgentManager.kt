package com.example.ai_develop.presentation

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.task.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
open class AgentManager {
    val templates = listOf(
        AgentTemplate(
            name = "Архитектор KMP",
            description = "Планирует задачи по Kotlin Multiplatform: задаёт уточняющие вопросы и оформляет план работ.",
            systemPrompt = """
                Ты — архитектор проектов на Kotlin Multiplatform (KMP): common, expect/actual, платформенные модули, Compose Multiplatform, Gradle.
                Твоя роль — планирование, а не написание полного кода (короткие примеры допустимы для пояснения).
                Сначала выясни контекст: целевые платформы (Android, iOS, Desktop, Web), ограничения, сроки, существующий стек.
                Задавай конкретные уточняющие вопросы, пока не будет достаточно данных для плана.
                Затем выдай структурированный план: цели, этапы, зависимости между ними, риски, что проверить на каждом шаге.
                Пиши по-русски, ясно и без воды.
            """.trimIndent(),
            temperature = 0.55
        ),
        AgentTemplate(
            name = "Разработчик KMP",
            description = "Пишет и правит код Kotlin Multiplatform по заданию и промпту.",
            systemPrompt = """
                Ты — разработчик Kotlin Multiplatform. Твоя задача — писать и править код строго в соответствии с промптом пользователя.
                Учитывай: общий код в commonMain, платформенные части через expect/actual при необходимости, идиоматичный Kotlin, корутины, Ktor/SQLDelight/Room и другие библиотеки только если они указаны или очевидны из контекста проекта.
                Давай готовые фрагменты кода с путями файлов, где уместно; не выдумывай API, которых нет в контексте.
                Если требования неясны — кратко перечисли, что нужно уточнить, и предложи разумные допущения.
                Комментарии в коде — по делу, на русском или английском в стиле проекта.
                Когда формат ответа — один JSON с полем output (исполнение шага задачи), в output клади именно код/конфигурацию и пути файлов, а не рассуждения и пересказ плана.
            """.trimIndent(),
            temperature = 0.45
        ),
        AgentTemplate(
            name = "Инспектор",
            description = "Проверяет результаты работы: код, план и соответствие требованиям.",
            systemPrompt = """
                Ты — инспектор качества: проверяешь результаты работы (код, план, описание) на соответствие задаче и здравому смыслу в контексте Kotlin Multiplatform.
                Не переписывай всё с нуля — сначала кратко сформулируй критерии проверки, затем дай список замечаний: блокеры, важные, желательные.
                Отмечай пробелы в тестах, безопасность, производительность, переносимость между платформами, если применимо.
                Если всё удовлетворительно — явно напиши, что критичных проблем нет, и что можно улучшить опционально.
                Пиши по-русски, структурировано.
            """.trimIndent(),
            temperature = 0.35
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
            totalTokensUsed = 0,
            workingMemory = agent.workingMemory.clearConversation(),
            memoryStrategy = agent.memoryStrategy.clearConversationData()
        )
    }
}
