package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.AgentEntity
import com.example.ai_develop.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MappersTest {

    @Test
    fun testAgentEntityToDomain() {
        val entity = AgentEntity(
            id = "1",
            name = "Test Agent",
            systemPrompt = "System",
            temperature = 0.5,
            provider = LLMProvider.Yandex(),
            stopWord = "stop",
            maxTokens = 100,
            totalTokensUsed = 10,
            memoryStrategy = ChatMemoryStrategy.SlidingWindow(10),
            branches = emptyList(),
            currentBranchId = null
        )
        
        val messages = listOf(ChatMessage(message = "Hello", source = SourceType.USER))
        val domain = entity.toDomain(messages)
        
        assertEquals(entity.id, domain.id)
        assertEquals(entity.name, domain.name)
        assertEquals(messages, domain.messages)
        assertEquals(entity.memoryStrategy, domain.memoryStrategy)
    }

    @Test
    fun testChatMessageToEntity() {
        val message = ChatMessage(
            message = "Hello",
            source = SourceType.USER,
            tokenCount = 5
        )
        val agentId = "agent_123"
        
        val entity = message.toEntity(agentId)
        
        assertEquals(message.id, entity.id)
        assertEquals(agentId, entity.agentId)
        assertEquals(message.message, entity.message)
        assertEquals(message.source, entity.source)
        assertEquals(message.tokenCount, entity.tokenCount)
    }
}
