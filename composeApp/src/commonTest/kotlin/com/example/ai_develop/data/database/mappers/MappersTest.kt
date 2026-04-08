package com.example.ai_develop.data.database.mappers

import com.example.ai_develop.data.database.*
import com.example.ai_develop.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MappersTest {

    @Test
    fun testAgentMapper() {
        val entity = AgentEntity(
            id = "test_id",
            name = "Test Agent",
            systemPrompt = "Prompt",
            temperature = 0.7,
            provider = LLMProvider.DEEPSEEK,
            stopWord = "",
            maxTokens = 1000,
            totalTokensUsed = 0,
            memoryStrategy = ChatMemoryStrategy.FULL,
            branches = emptyList(),
            currentBranchId = null,
            userProfile = null,
            workingMemory = WorkingMemory()
        )
        
        val domain = entity.toDomain(emptyList())
        assertEquals(entity.id, domain.id)
        assertEquals(entity.name, domain.name)
        
        val backToEntity = domain.toEntity()
        assertEquals(entity.id, backToEntity.id)
    }
}
