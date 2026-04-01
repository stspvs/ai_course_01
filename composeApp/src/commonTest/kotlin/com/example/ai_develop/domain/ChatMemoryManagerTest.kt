package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMemoryManagerTest {
    private val manager = ChatMemoryManager()

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
    fun testBranchHistoryFallbackByBranchId() {
        val messages = listOf(
            ChatMessage(id = "1", message = "Msg 1", source = SourceType.USER, branchId = "main_branch"),
            ChatMessage(id = "2", parentId = "1", message = "Msg 2", source = SourceType.ASSISTANT, branchId = "main_branch")
        )
        // No explicit branch pointer in agentBranches
        val history = manager.getBranchHistory(messages, null, emptyList())
        assertEquals(2, history.size)
        assertEquals("2", history.last().id)
    }

    @Test
    fun testBranchHistoryCycleProtection() {
        val messages = listOf(
            ChatMessage(id = "1", parentId = "2", message = "Cycle 1", source = SourceType.USER),
            ChatMessage(id = "2", parentId = "1", message = "Cycle 2", source = SourceType.ASSISTANT)
        )
        val branches = listOf(ChatBranch(id = "main_branch", name = "Main", lastMessageId = "2"))
        
        val history = manager.getBranchHistory(messages, null, branches)
        // Should not hang and should contain unique messages
        assertTrue(history.size <= 2)
    }

    @Test
    fun testWrapSystemPromptWithFacts() {
        val base = "You are an AI."
        val facts = ChatFacts(mapOf("user_name" to "Stas"))
        val strategy = ChatMemoryStrategy.StickyFacts(windowSize = 10, facts = facts)
        
        val wrapped = manager.wrapSystemPrompt(base, strategy)
        assertTrue(wrapped.contains(base))
        assertTrue(wrapped.contains("user_name: Stas"))
    }
}
