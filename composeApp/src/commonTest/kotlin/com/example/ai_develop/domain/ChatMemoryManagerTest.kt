package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMemoryManagerTest {
    private val manager = ChatMemoryManager()

    @Test
    fun testDisplayHistoryFallsBackWhenParentIdsMissingLikeAfterDbLoad() {
        val messages = listOf(
            ChatMessage(id = "a", message = "first", timestamp = 100L, source = SourceType.USER),
            ChatMessage(id = "b", message = "second", timestamp = 200L, source = SourceType.ASSISTANT),
            ChatMessage(id = "c", message = "third", timestamp = 300L, source = SourceType.USER)
        )
        val display = manager.getDisplayHistory(messages, null, emptyList())
        assertEquals(3, display.size)
        assertEquals(listOf("first", "second", "third"), display.map { it.message })
        assertEquals(1, manager.getBranchHistory(messages, null, emptyList()).size)
    }

    @Test
    fun testBrokenParentChainFallsBackToChronologicalWindow() {
        val messages = listOf(
            ChatMessage(id = "1", message = "M1", timestamp = 100L, source = SourceType.USER),
            ChatMessage(id = "2", message = "M2", timestamp = 200L, source = SourceType.ASSISTANT),
            ChatMessage(id = "3", parentId = "2", message = "M3", timestamp = 300L, source = SourceType.USER),
            ChatMessage(id = "4", parentId = "3", message = "M4", timestamp = 400L, source = SourceType.ASSISTANT)
        )
        val strategy = ChatMemoryStrategy.SlidingWindow(windowSize = 10)
        val processed = manager.processMessages(messages, strategy, null, emptyList())
        assertEquals(4, processed.size)
        assertEquals(listOf("M1", "M2", "M3", "M4"), processed.map { it.message })
    }

    @Test
    fun testSlidingWindowProcessing() {
        val messages = listOf(
            ChatMessage(id = "1", message = "M1", source = SourceType.USER),
            ChatMessage(id = "2", parentId = "1", message = "M2", source = SourceType.ASSISTANT),
            ChatMessage(id = "3", parentId = "2", message = "M3", source = SourceType.USER),
            ChatMessage(id = "4", parentId = "3", message = "M4", source = SourceType.ASSISTANT)
        )
        val strategy = ChatMemoryStrategy.SlidingWindow(windowSize = 2)
        
        val history = manager.getBranchHistory(messages, null, emptyList())
        assertEquals(4, history.size)
        
        val processed = manager.processMessages(messages, strategy)
        assertEquals(2, processed.size)
        assertEquals("M3", processed[0].message)
        assertEquals("M4", processed[1].message)
    }

    @Test
    fun testBranchHistory() {
        val messages = listOf(
            ChatMessage(id = "root", message = "Root", source = SourceType.USER),
            ChatMessage(id = "b1_1", parentId = "root", message = "B1-1", source = SourceType.ASSISTANT, branchId = "branch1"),
            ChatMessage(id = "b2_1", parentId = "root", message = "B2-1", source = SourceType.ASSISTANT, branchId = "branch2")
        )
        val branches = listOf(
            ChatBranch(id = "branch1", name = "B1", lastMessageId = "b1_1"),
            ChatBranch(id = "branch2", name = "B2", lastMessageId = "b2_1")
        )

        val history1 = manager.getBranchHistory(messages, "branch1", branches)
        assertEquals(2, history1.size)
        assertEquals("Root", history1[0].message)
        assertEquals("B1-1", history1[1].message)

        val history2 = manager.getBranchHistory(messages, "branch2", branches)
        assertEquals(2, history2.size)
        assertEquals("Root", history2[0].message)
        assertEquals("B2-1", history2[1].message)
    }

    @Test
    fun testWrapSystemPromptWithPersonalization() {
        val base = "You are an AI."
        val profile = UserProfile(
            preferences = "Answer shortly",
            constraints = "No emojis"
        )
        val agent = Agent(
            name = "Test", 
            systemPrompt = base, 
            userProfile = profile,
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )
        
        val wrapped = manager.wrapSystemPrompt(agent)
        assertTrue(wrapped.contains(base))
        assertTrue(wrapped.contains("USER PERSONALIZATION"))
        assertTrue(wrapped.contains("Answer shortly"))
        assertTrue(wrapped.contains("No emojis"))
        
        // Short-term memory should NOT be in system prompt anymore
        assertTrue(!wrapped.contains("TEMPORARY MEMORY"))
    }

    @Test
    fun testWrapSystemPromptWithWorkingMemory() {
        val base = "You are an AI."
        val wm = WorkingMemory(
            currentTask = "Solve math",
            progress = "Started"
        )
        val agent = Agent(
            name = "Test", 
            systemPrompt = base, 
            workingMemory = wm,
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )
        
        val wrapped = manager.wrapSystemPrompt(agent)
        assertTrue(wrapped.contains("WORKING MEMORY"))
        assertTrue(wrapped.contains("Solve math"))
        assertTrue(wrapped.contains("Started"))
    }

    @Test
    fun testWrapSystemPromptOmitsWorkingMemoryWhenDisabled() {
        val base = "You are an AI."
        val wm = WorkingMemory(
            currentTask = "Solve math",
            progress = "Started"
        )
        val agent = Agent(
            name = "Test",
            systemPrompt = base,
            workingMemory = wm,
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )

        val wrapped = manager.wrapSystemPrompt(agent, includeAgentWorkingMemoryInSystem = false)
        assertTrue(wrapped.contains(base))
        assertTrue(!wrapped.contains("WORKING MEMORY"))
        assertTrue(!wrapped.contains("Solve math"))
    }

    @Test
    fun testShortTermMemoryAsMessage() {
        val summary = "User wants to buy a car."
        val agent = Agent(
            name = "Test",
            systemPrompt = "AI",
            memoryStrategy = ChatMemoryStrategy.Summarization(windowSize = 10, summary = summary),
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )

        val stmMessage = manager.getShortTermMemoryMessage(agent)
        assertNotNull(stmMessage)
        assertEquals("system", stmMessage.role)
        assertTrue(stmMessage.message.contains(summary))
        assertTrue(stmMessage.message.contains("TEMPORARY MEMORY"))
        assertTrue(stmMessage.isSystemNotification)
    }

    @Test
    fun testNoShortTermMemoryMessageWhenEmpty() {
        val agent = Agent(
            name = "Test",
            systemPrompt = "AI",
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
            temperature = 0.7,
            provider = LLMProvider.Yandex(),
            stopWord = "",
            maxTokens = 100
        )

        val stmMessage = manager.getShortTermMemoryMessage(agent)
        assertNull(stmMessage)
    }
}
