package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.SourceType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMHandlersTest {
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    @Test
    fun testDeepSeekHandlerBuildRequestBody() {
        val provider = LLMProvider.DeepSeek("deepseek-chat")
        val handler = DeepSeekHandler("key", provider, json)
        val messages = listOf(ChatMessage(message = "Hello", source = SourceType.USER))
        
        val bodyString = handler.buildChatRequestBody(
            messages = messages,
            systemPrompt = "System",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            stream = true
        )
        
        val body = json.parseToJsonElement(bodyString).jsonObject
        assertEquals("deepseek-chat", body["model"]?.jsonPrimitive?.content)
        assertEquals(100, body["max_tokens"]?.jsonPrimitive?.int)
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        
        val apiMessages = body["messages"]?.jsonArray
        assertEquals(2, apiMessages?.size)
        assertEquals("system", apiMessages?.get(0)?.jsonObject?.get("role")?.jsonPrimitive?.content)
        assertEquals("System", apiMessages?.get(0)?.jsonObject?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun testYandexHandlerBuildRequestBody() {
        val provider = LLMProvider.Yandex("yandexgpt")
        val handler = YandexHandler("key", "folderId", provider, json)
        val messages = listOf(ChatMessage(message = "Hello", source = SourceType.USER))
        
        val bodyString = handler.buildChatRequestBody(
            messages = messages,
            systemPrompt = "System",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            stream = true
        )
        
        val body = json.parseToJsonElement(bodyString).jsonObject
        assertTrue(body["modelUri"]?.jsonPrimitive?.content?.contains("yandexgpt") == true)
        
        val options = body["completionOptions"]?.jsonObject
        assertEquals(true, options?.get("stream")?.jsonPrimitive?.boolean)
        assertEquals(100L, options?.get("maxTokens")?.jsonPrimitive?.long)
        
        val yandexMessages = body["messages"]?.jsonArray
        assertEquals(2, yandexMessages?.size)
    }

    @Test
    fun testYandexHandlerParseStreamChunk() {
        val provider = LLMProvider.Yandex("yandexgpt")
        val handler = YandexHandler("key", "folderId", provider, json)
        val context = StreamContext()
        
        val line1 = "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\"H\"}}]}}"
        val result1 = handler.parseStreamChunk(line1, context)
        assertTrue(result1 is StreamChunkResult.Content)
        assertEquals("H", (result1 as StreamChunkResult.Content).delta)
        
        val line2 = "{\"result\":{\"alternatives\":[{\"message\":{\"role\":\"assistant\",\"text\":\"He\"}}]}}"
        val result2 = handler.parseStreamChunk(line2, context)
        assertTrue(result2 is StreamChunkResult.Content)
        assertEquals("e", (result2 as StreamChunkResult.Content).delta)
    }
}
