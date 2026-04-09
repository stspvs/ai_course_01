package com.example.ai_develop.domain

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMemoryStrategyClearTest {

    @Test
    fun stickyFacts_clearsFacts() {
        val s = ChatMemoryStrategy.StickyFacts(10, ChatFacts(listOf("a")))
        val c = s.clearConversationData() as ChatMemoryStrategy.StickyFacts
        assertTrue(c.facts.facts.isEmpty())
    }

    @Test
    fun summarization_clearsSummary() {
        val s = ChatMemoryStrategy.Summarization(10, summary = "old")
        val c = s.clearConversationData() as ChatMemoryStrategy.Summarization
        assertNull(c.summary)
    }

    @Test
    fun taskOriented_clearsGoal() {
        val s = ChatMemoryStrategy.TaskOriented(10, currentGoal = "g")
        val c = s.clearConversationData() as ChatMemoryStrategy.TaskOriented
        assertNull(c.currentGoal)
    }
}
