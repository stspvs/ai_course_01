package com.example.ai_develop.data

import com.example.ai_develop.domain.chat.ChatFacts
import com.example.ai_develop.domain.chat.ChatMessage
import com.example.ai_develop.domain.chat.SourceType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class PromptBuilderTest {

    private val json = Json { isLenient = true }

    @Test
    fun `buildFactsExtractionPrompt should contain current facts and new messages in Russian`() {
        val currentFacts = ChatFacts(listOf("имя: Алиса"))
        val messages = listOf(ChatMessage(message = "Я люблю пиццу", role = "user", source = SourceType.USER))
        
        val prompt = PromptBuilder.buildFactsExtractionPrompt(currentFacts, messages, json)
        
        assertTrue(prompt.contains("Алиса"))
        assertTrue(prompt.contains("Я люблю пиццу"))
        assertTrue(prompt.contains("user"))
        assertTrue(prompt.contains("Проанализируй диалог"))
        assertTrue(prompt.contains("JSON"))
    }

    @Test
    fun `buildSummarizationPrompt should contain instruction and messages`() {
        val messages = listOf(ChatMessage(message = "Важное сообщение", role = "assistant", source = SourceType.ASSISTANT))
        val prompt = PromptBuilder.buildSummarizationPrompt("Старое резюме", messages, "Сделай кратко")
        
        assertTrue(prompt.contains("Сделай кратко"))
        assertTrue(prompt.contains("Старое резюме"))
        assertTrue(prompt.contains("Важное сообщение"))
        assertTrue(prompt.contains("НА РУССКОМ ЯЗЫКЕ"))
    }

    @Test
    fun `buildWorkingMemoryPrompt should contain task and progress`() {
        val messages = listOf(ChatMessage(message = "Работаем над кодом", role = "user", source = SourceType.USER))
        val prompt = PromptBuilder.buildWorkingMemoryPrompt(
            currentTask = "Пишем тесты",
            progress = "20%",
            dialogueGoal = "Покрытие модуля",
            clarifications = listOf("JUnit 5"),
            fixedTermsAndConstraints = listOf("Kotlin"),
            messages = messages,
        )

        assertTrue(prompt.contains("Пишем тесты"))
        assertTrue(prompt.contains("20%"))
        assertTrue(prompt.contains("Работаем над кодом"))
        assertTrue(prompt.contains("ПАМЯТЬ ЗАДАЧИ"))
        assertTrue(prompt.contains("Покрытие модуля"))
        assertTrue(prompt.contains("JUnit 5"))
    }
}
