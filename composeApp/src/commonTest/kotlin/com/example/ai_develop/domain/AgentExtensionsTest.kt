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
    fun testMergeWithComplexBranches() {
        val provider = LLMProvider.Yandex()
        val m1 = ChatMessage(id = "m1", message = "M1", source = SourceType.USER, timestamp = 10)
        val m2 = ChatMessage(id = "m2", parentId = "m1", message = "M2", source = SourceType.ASSISTANT, timestamp = 20)
        val m3 = ChatMessage(id = "m3", parentId = "m2", message = "M3 Local", source = SourceType.USER, timestamp = 30)
        
        val agentLocal = Agent(
            id = "1", name = "A1", systemPrompt = "", temperature = 1.0, provider = provider, stopWord = "", maxTokens = 100,
            messages = listOf(m1, m2, m3),
            branches = listOf(ChatBranch(id = "b1", name = "B1", lastMessageId = "m3")),
            currentBranchId = "b1"
        )

        val m3Db = ChatMessage(id = "m3_db", parentId = "m2", message = "M3 DB", source = SourceType.USER, timestamp = 25)
        val agentDb = Agent(
            id = "1", name = "A1", systemPrompt = "", temperature = 1.0, provider = provider, stopWord = "", maxTokens = 100,
            messages = listOf(m1, m2, m3Db),
            branches = listOf(ChatBranch(id = "b1", name = "B1", lastMessageId = "m3_db")),
            currentBranchId = "b1"
        )

        val merged = agentLocal.mergeWith(agentDb)

        // Branch b1 should prefer the local one if it exists in the merged messages
        assertEquals("m3", merged.branches.find { it.id == "b1" }?.lastMessageId)
        assertEquals(4, merged.messages.size)
        assertTrue(merged.messages.any { it.id == "m3" })
        assertTrue(merged.messages.any { it.id == "m3_db" })
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
