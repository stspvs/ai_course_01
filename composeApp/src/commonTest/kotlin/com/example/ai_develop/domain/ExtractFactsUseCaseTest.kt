package com.example.ai_develop.domain

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractFactsUseCaseTest {

    private class MockRepository : ChatRepository {
        var lastMessages: List<ChatMessage> = emptyList()
        var lastFacts: ChatFacts? = null
        
        override fun chatStreaming(messages: List<ChatMessage>, systemPrompt: String, maxTokens: Int, temperature: Double, stopWord: String, isJsonMode: Boolean, provider: LLMProvider) = emptyFlow<Result<String>>()
        
        override suspend fun extractFacts(messages: List<ChatMessage>, currentFacts: ChatFacts, provider: LLMProvider): Result<ChatFacts> {
            lastMessages = messages
            lastFacts = currentFacts
            return Result.success(ChatFacts(mapOf("extracted" to "true")))
        }

        override suspend fun summarize(
            messages: List<ChatMessage>,
            previousSummary: String?,
            instruction: String,
            provider: LLMProvider
        ): Result<String> = Result.success("summary")
    }

    @Test
    fun testInvoke() = runTest {
        val repo = MockRepository()
        val useCase = ExtractFactsUseCase(repo)
        val messages = listOf(
            ChatMessage(id = "1", message = "M1", source = SourceType.USER),
            ChatMessage(id = "2", message = "M2", source = SourceType.USER),
            ChatMessage(id = "3", message = "M3", source = SourceType.USER)
        )
        val currentFacts = ChatFacts(mapOf("old" to "fact"))
        val provider = LLMProvider.DeepSeek()
        
        val result = useCase(messages, currentFacts, provider, windowSize = 2)
        
        assertTrue(result.isSuccess)
        assertEquals("true", result.getOrNull()?.facts?.get("extracted"))
        assertEquals(2, repo.lastMessages.size)
        assertEquals("M2", repo.lastMessages[0].message)
        assertEquals("M3", repo.lastMessages[1].message)
        assertEquals(currentFacts, repo.lastFacts)
    }
}
