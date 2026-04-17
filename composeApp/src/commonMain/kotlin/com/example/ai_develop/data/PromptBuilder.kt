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
        dialogueGoal: String?,
        clarifications: List<String>,
        fixedTermsAndConstraints: List<String>,
        messages: List<ChatMessage>
    ): String {
        val clarText =
            clarifications.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: "(пусто)"
        val termsText =
            fixedTermsAndConstraints.takeIf { it.isNotEmpty() }?.joinToString(" | ") ?: "(пусто)"
        return """
            Проанализируй переписку и обнови ПАМЯТЬ ЗАДАЧИ (task state).
            
            Текущая задача в памяти: ${currentTask ?: "Нет"}
            Текущий прогресс: ${progress ?: "не задан"}
            Цель диалога (если была): ${dialogueGoal ?: "Нет"}
            Уже зафиксированные уточнения: $clarText
            Уже зафиксированные термины и ограничения: $termsText
            
            История переписки:
            ${messages.takeLast(15).joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Инструкции:
            1. Сформулируй цель диалога (поле dialogueGoal) — что пользователь хочет получить в итоге.
            2. Поле currentTask — короткая метка текущей работы; progress — оценка прогресса (на русском).
            3. clarifications — список того, что пользователь УЖЕ уточнил или о чём договорились.
            4. fixedTermsAndConstraints — зафиксированные термины, определения, технические ограничения и рамки.
            5. Всё на РУССКОМ. Верни ТОЛЬКО JSON-объект с полями:
               currentTask, progress, dialogueGoal (строки или null),
               clarifications (массив строк), fixedTermsAndConstraints (массив строк).
            
            Пример: {"currentTask":"Рефакторинг модуля","progress":"40%","dialogueGoal":"Чистый Kotlin без лишних зависимостей","clarifications":["Нужна только JVM"],"fixedTermsAndConstraints":["Kotlin 2.0","без Retrofit"]}
        """.trimIndent()
    }
}
