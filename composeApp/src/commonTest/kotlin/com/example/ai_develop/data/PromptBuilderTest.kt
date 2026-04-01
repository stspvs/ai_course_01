package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.SourceType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class PromptBuilderTest {

    @Test
    fun `buildFactsExtractionPrompt should contain current facts and new messages`() {
        val currentFacts = ChatFacts(mapOf("name" to "Alice"))
        val messages = listOf(ChatMessage(message = "I like pizza", source = SourceType.USER))
        val json = Json { isLenient = true }
        
        val prompt = PromptBuilder.buildFactsExtractionPrompt(currentFacts, messages, json)
        
        assertTrue(prompt.contains("Alice"))
        assertTrue(prompt.contains("I like pizza"))
        assertTrue(prompt.contains("user"))
    }
}
