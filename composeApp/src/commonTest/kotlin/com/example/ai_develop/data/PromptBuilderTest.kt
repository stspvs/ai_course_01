package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.SourceType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class PromptBuilderTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun testBuildFactsExtractionPrompt() {
        val currentFacts = ChatFacts(mapOf("name" to "John"))
        val messages = listOf(ChatMessage("I like Kotlin", SourceType.USER))
        
        val prompt = PromptBuilder.buildFactsExtractionPrompt(currentFacts, messages, json)
        
        assertTrue(prompt.contains("\"name\":\"John\""))
        assertTrue(prompt.contains("user: I like Kotlin"))
        assertTrue(prompt.contains("Instructions:"))
    }
    
    @Test
    fun testBuildFactsExtractionPromptEmpty() {
        val currentFacts = ChatFacts(emptyMap())
        val messages = listOf(ChatMessage("Hello", SourceType.USER))
        
        val prompt = PromptBuilder.buildFactsExtractionPrompt(currentFacts, messages, json)
        
        assertTrue(prompt.contains("No facts yet."))
        assertTrue(prompt.contains("user: Hello"))
    }
}
