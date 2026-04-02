package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PromptBuilder {
    fun buildFactsExtractionPrompt(
        currentFacts: ChatFacts,
        newMessages: List<ChatMessage>,
        json: Json
    ): String {
        return """
            Проанализируй диалог и обнови список ключевых фактов. 
            Отслеживай: цели, ограничения, предпочтения, принятые решения, информацию о пользователе.
            
            Текущие факты: 
            ${if (currentFacts.facts.isEmpty()) "Фактов пока нет." else json.encodeToString(currentFacts.facts)}
            
            Новые сообщения для анализа:
            ${newMessages.joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Инструкции:
            1. Просмотри текущие факты и новые сообщения.
            2. Если обнаружен новый факт, добавь его в список.
            3. Если текущий факт обновлен или исправлен, измени его в списке.
            4. НЕ удаляй текущие факты, если они явно не противоречат новым данным или не устарели.
            5. Верни ПОЛНЫЙ список всех фактов (старых и новых) НА РУССКОМ ЯЗЫКЕ.
            6. Ответ ДОЛЖЕН быть только валидным JSON-массивом строк.
            
            Пример вывода: ["Имя пользователя — Иван", "Цель — выучить Kotlin", "Основной язык — русский"]
        """.trimIndent()
    }

    fun buildSummarizationPrompt(
        previousSummary: String?,
        messages: List<ChatMessage>,
        instruction: String
    ): String {
        return """
            $instruction
            
            ${previousSummary?.let { "Предыдущее резюме: $it\n" } ?: ""}
            
            Сообщения для суммаризации:
            ${messages.joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Верни ТОЛЬКО текст нового резюме НА РУССКОМ ЯЗЫКЕ.
        """.trimIndent()
    }

    fun buildWorkingMemoryPrompt(
        currentTask: String?,
        progress: String?,
        messages: List<ChatMessage>
    ): String {
        return """
            Проанализируй переписку и извлеки ТЕКУЩУЮ ЗАДАЧУ и ПРОГРЕСС.
            
            Текущая задача в памяти: ${currentTask ?: "Нет"}
            Текущий прогресс: ${progress ?: "0%"}
            
            История переписки:
            ${messages.takeLast(15).joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Инструкции:
            1. Определи, над чем сейчас работают пользователь и ассистент.
            2. Оцени прогресс выполнения этой задачи (например, "Начато", "В процессе (50%)", "Почти готово").
            3. Ответ должен быть НА РУССКОМ ЯЗЫКЕ.
            4. Верни ТОЛЬКО JSON-объект с полями "currentTask" и "progress".
            
            Пример: {"currentTask": "Написание функции на Kotlin", "progress": "60%"}
        """.trimIndent()
    }
}
