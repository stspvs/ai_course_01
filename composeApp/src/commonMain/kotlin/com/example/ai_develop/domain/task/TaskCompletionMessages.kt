package com.example.ai_develop.domain.task

import com.example.ai_develop.domain.agent.*
import com.example.ai_develop.domain.chat.*
import com.example.ai_develop.domain.rag.*
import com.example.ai_develop.domain.llm.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Тексты системных сообщений при завершении задачи (ленты чата).
 * Логика вынесена из [TaskSaga] для стабильного форматирования и тестов.
 */
object TaskCompletionMessages {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Успешный финал: маркер успеха, строка «задача завершена», при наличии — JSON [ExecutionResult] последнего шага.
     */
    fun taskSuccessWithExecutorResult(lastExecution: ExecutionResult?): String =
        buildString {
            appendLine("--- TASK SUCCESS ---")
            appendLine()
            appendLine("▶ Задача завершена")
            if (lastExecution != null) {
                appendLine()
                appendLine("Результат исполнителя (последний шаг):")
                appendLine(json.encodeToString(ExecutionResult.serializer(), lastExecution))
            }
        }
}
