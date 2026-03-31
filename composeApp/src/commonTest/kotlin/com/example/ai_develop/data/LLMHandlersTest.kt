package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.SourceType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMHandlersTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun testDeepSeekHandlerBuildRequestBody() {
        val provider = LLMProvider.DeepSeek("deepseek-chat")
        val handler = DeepSeekHandler("key", provider, json)
        val messages = listOf(ChatMessage(message = "Hello", source = SourceType.USER))
        
        val body = handler.buildChatRequestBody(
            messages = messages,
            systemPrompt = "System",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "stop",
            isJsonMode = true,
            stream = false
        )
        
        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
        assertTrue(body.contains("\"role\":\"system\",\"content\":\"System\""))
        assertTrue(body.contains("\"role\":\"user\",\"content\":\"Hello\""))
        assertTrue(body.contains("\"max_tokens\":100"))
        assertTrue(body.contains("\"type\":\"json_object\""))
    }

    @Test
    fun testDeepSeekHandlerParseStreamChunk() {
        val provider = LLMProvider.DeepSeek("deepseek-chat")
        val handler = DeepSeekHandler("key", provider, json)
        val context = StreamContext()
        
        val line = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        val result = handler.parseStreamChunk(line, context)
        
        assertTrue(result is StreamChunkResult.Content)
        assertEquals("Hello", (result as StreamChunkResult.Content).delta)
        
        val doneLine = "data: [DONE]"
        val doneResult = handler.parseStreamChunk(doneLine, context)
        assertTrue(doneResult is StreamChunkResult.Done)
    }

    @Test
    fun testYandexHandlerBuildRequestBody() {
        val provider = LLMProvider.Yandex("yandexgpt")
        val handler = YandexHandler("key", "folderId", provider, json)
        val messages = listOf(ChatMessage(message = "Hello", source = SourceType.USER))
        
        val body = handler.buildChatRequestBody(
            messages = messages,
            systemPrompt = "System",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            stream = true
        )
        
        assertTrue(body.contains("\"modelUri\":\"gpt://folderId/yandexgpt\""))
        assertTrue(body.contains("\"role\":\"system\",\"text\":\"System\""))
        assertTrue(body.contains("\"role\":\"user\",\"text\":\"Hello\""))
        assertTrue(body.contains("\"maxTokens\":100"))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun testYandexHandlerParseStreamChunk() {
        val provider = LLMProvider.Yandex("yandexgpt")
        val handler = YandexHandler("key", "folderId", provider, json)
        val context = StreamContext()
        
        // Yandex returns full text in each chunk
        val line1 = "{\"result\":{\"alternatives\":[{\"message\":{\"text\":\"H\"}}]}}"
        val result1 = handler.parseStreamChunk(line1, context)
        assertTrue(result1 is StreamChunkResult.Content)
        assertEquals("H", (result1 as StreamChunkResult.Content).delta)
        
        val line2 = "{\"result\":{\"alternatives\":[{\"message\":{\"text\":\"He\"}}]}}"
        val result2 = handler.parseStreamChunk(line2, context)
        assertTrue(result2 is StreamChunkResult.Content)
        assertEquals("e", (result2 as StreamChunkResult.Content).delta)
    }

    @Test
    fun testOpenRouterHandlerBuildHeaders() {
        val provider = LLMProvider.OpenRouter("google/gemini-pro")
        val handler = OpenRouterHandler("key", provider, json)
        val headers = handler.buildHeaders()
        
        assertEquals("Bearer key", headers["Authorization"])
        assertTrue(headers.containsKey("HTTP-Referer"))
    }
}
