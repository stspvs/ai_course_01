package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExtensionsTest {

    @Test
    fun testMergeWithMessages() {
        val provider = LLMProvider.Yandex()
        val agent1 = Agent(id = "1", name = "A1", systemPrompt = "", temperature = 1.0, provider = provider, stopWord = "", maxTokens = 100,
            messages = listOf(ChatMessage(id = "m1", message = "Local Only", source = SourceType.USER, timestamp = 100)))
        
        val agentDb = Agent(id = "1", name = "A1", systemPrompt = "", temperature = 1.0, provider = provider, stopWord = "", maxTokens = 100,
            messages = listOf(ChatMessage(id = "m2", message = "DB Only", source = SourceType.ASSISTANT, timestamp = 50)))

        val merged = agent1.mergeWith(agentDb)
        
        assertEquals(2, merged.messages.size)
        assertEquals("m2", merged.messages[0].id) // m2 has smaller timestamp
        assertEquals("m1", merged.messages[1].id)
    }

    @Test
    fun testUpdatePointer() {
        val branches = listOf(ChatBranch(id = "b1", name = "B1", lastMessageId = "m1"))
        
        val updated = branches.updatePointer("b1", "m2")
        assertEquals(1, updated.size)
        assertEquals("m2", updated.first().lastMessageId)

        val withNew = branches.updatePointer("b2", "m3")
        assertEquals(2, withNew.size)
        assertTrue(withNew.any { it.id == "b2" && it.lastMessageId == "m3" })
    }

    @Test
    fun testEstimateTokens() {
        assertEquals(1, estimateTokens(""))
        assertEquals(1, estimateTokens("abc"))
        assertEquals(2, estimateTokens("12345678"))
    }
}
