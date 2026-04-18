package com.example.ai_develop.presentation

import com.example.ai_develop.domain.chat.ChatFacts
import com.example.ai_develop.domain.chat.ChatMemoryStrategy
import com.example.ai_develop.domain.llm.LLMProvider
import com.example.ai_develop.domain.chat.WorkingMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentManagerTest {
    private val manager = AgentManager()

    @Test
    fun testCreateDefaultAgent() {
        val provider = LLMProvider.Yandex("model-1")
        val agent = manager.createDefaultAgent(provider)
        
        assertEquals("Новый агент", agent.name)
        assertEquals(provider, agent.provider)
        assertTrue(agent.memoryStrategy is ChatMemoryStrategy.SlidingWindow)
        assertEquals(10, agent.memoryStrategy.windowSize)
    }

    @Test
    fun testUpdateAgent() {
        val provider1 = LLMProvider.Yandex("m1")
        val provider2 = LLMProvider.DeepSeek("m2")
        val agent = manager.createDefaultAgent(provider1)
        
        val updated = manager.updateAgent(
            agent = agent,
            name = "Updated",
            systemPrompt = "New Prompt",
            temperature = 0.5,
            provider = provider2,
            stopWord = "stop",
            maxTokens = 500,
            memoryStrategy = ChatMemoryStrategy.Summarization(20)
        )
        
        assertEquals("Updated", updated.name)
        assertEquals("New Prompt", updated.systemPrompt)
        assertEquals(0.5, updated.temperature)
        assertEquals(provider2, updated.provider)
        assertEquals("stop", updated.stopWord)
        assertEquals(500, updated.maxTokens)
        assertTrue(updated.memoryStrategy is ChatMemoryStrategy.Summarization)
        assertEquals(20, updated.memoryStrategy.windowSize)
    }

    @Test
    fun testDuplicateAgent() {
        val agent = manager.createDefaultAgent(LLMProvider.Yandex())
        val duplicate = manager.duplicateAgent(agent)
        
        assertNotEquals(agent.id, duplicate.id)
        assertTrue(duplicate.name.contains("(Copy)"))
        assertEquals(0, duplicate.totalTokensUsed)
        assertTrue(duplicate.messages.isEmpty())
    }

    @Test
    fun testClearChat() {
        // Since we can't easily add messages to Agent here without its constructor (which is internal/complex)
        // We'll just check if it returns an agent with empty lists.
        val agent = manager.createDefaultAgent(LLMProvider.Yandex())
        val cleared = manager.clearChat(agent)
        
        assertTrue(cleared.messages.isEmpty())
        assertTrue(cleared.branches.isEmpty())
        assertEquals(0, cleared.totalTokensUsed)
    }

    @Test
    fun testClearChat_resetsWorkingAndTemporaryMemory() {
        val base = manager.createDefaultAgent(LLMProvider.Yandex())
        val agent = base.copy(
            workingMemory = WorkingMemory(currentTask = "T", progress = "P", extractedFacts = ChatFacts(listOf("f"))),
            memoryStrategy = ChatMemoryStrategy.Summarization(10, summary = "old summary")
        )
        val cleared = manager.clearChat(agent)
        assertNull(cleared.workingMemory.currentTask)
        assertNull(cleared.workingMemory.progress)
        assertTrue(cleared.workingMemory.extractedFacts.facts.isEmpty())
        val strat = cleared.memoryStrategy
        assertTrue(strat is ChatMemoryStrategy.Summarization)
        assertEquals(null, (strat as ChatMemoryStrategy.Summarization).summary)
        assertEquals(10, strat.windowSize)
    }
}
