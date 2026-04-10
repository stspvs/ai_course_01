package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

class TaskCompletionMessagesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun jsonLineFromCompletionMessage(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .last { it.startsWith("{") && it.endsWith("}") }

    private fun decodeEmbeddedExecution(text: String): ExecutionResult =
        json.decodeFromString(ExecutionResult.serializer(), jsonLineFromCompletionMessage(text))

    @Test
    fun taskSuccess_nullExecution_hasBannerOnly() {
        val s = TaskCompletionMessages.taskSuccessWithExecutorResult(null)
        assertContains(s, "--- TASK SUCCESS ---")
        assertContains(s, "▶ Задача завершена")
        assertFalse(s.contains("Результат исполнителя"))
    }

    @Test
    fun taskSuccess_withExecution_embedsRoundTripJson() {
        val exec = ExecutionResult(
            success = true,
            output = "deliverable",
            errors = listOf("e1")
        )
        val s = TaskCompletionMessages.taskSuccessWithExecutorResult(exec)
        assertContains(s, "Результат исполнителя (последний шаг):")
        val decoded = decodeEmbeddedExecution(s)
        assertEquals(exec, decoded)
    }

    @Test
    fun taskSuccess_unicodeAndNewlinesInOutput_preservedInJson() {
        val exec = ExecutionResult(
            success = true,
            output = "строка\nс переносом и «кавычками» \uD83D\uDE00",
            errors = null
        )
        val s = TaskCompletionMessages.taskSuccessWithExecutorResult(exec)
        val decoded = decodeEmbeddedExecution(s)
        assertEquals(exec.output, decoded.output)
    }

    @Test
    fun taskSuccess_largeOutput_stress() {
        val big = "x".repeat(120_000)
        val exec = ExecutionResult(success = true, output = big, errors = null)
        val s = TaskCompletionMessages.taskSuccessWithExecutorResult(exec)
        val decoded = decodeEmbeddedExecution(s)
        assertEquals(120_000, decoded.output.length)
        assertNotNull(decoded)
    }

    @Test
    fun taskSuccess_emptyOutput_stillSerializes() {
        val exec = ExecutionResult(success = true, output = "", errors = emptyList())
        val s = TaskCompletionMessages.taskSuccessWithExecutorResult(exec)
        val decoded = decodeEmbeddedExecution(s)
        assertEquals("", decoded.output)
        assertEquals(emptyList(), decoded.errors)
    }
}
