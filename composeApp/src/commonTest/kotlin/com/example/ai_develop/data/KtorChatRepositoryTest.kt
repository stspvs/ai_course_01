package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LLMProvider
import com.example.ai_develop.domain.SourceType
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorChatRepositoryTest {

    @Test
    fun testChatStreamingSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\ndata: [DONE]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val repository = KtorChatRepository(
            httpClient = httpClient,
            deepSeekKey = "key",
            yandexKey = "key",
            yandexFolderId = "id",
            openRouterKey = "key"
        )

        val results = repository.chatStreaming(
            messages = listOf(ChatMessage(message = "Hi", source = SourceType.USER)),
            systemPrompt = "",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = LLMProvider.DeepSeek("deepseek-chat")
        ).toList()

        assertTrue(results.any { it.isSuccess && it.getOrNull() == "Hello" })
    }

    @Test
    fun testChatStreamingError() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("Internal Server Error"),
                status = HttpStatusCode.InternalServerError
            )
        }
        val httpClient = HttpClient(mockEngine)
        val repository = KtorChatRepository(
            httpClient = httpClient,
            deepSeekKey = "key",
            yandexKey = "key",
            yandexFolderId = "id",
            openRouterKey = "key"
        )

        val results = repository.chatStreaming(
            messages = listOf(ChatMessage(message = "Hi", source = SourceType.USER)),
            systemPrompt = "",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = LLMProvider.DeepSeek("deepseek-chat")
        ).toList()

        assertTrue(results.any { it.isFailure })
        val error = results.first { it.isFailure }.exceptionOrNull()
        assertTrue(error?.message?.contains("500") == true)
    }

    @Test
    fun testExtractFactsSuccess() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"name\\\": \\\"Alice\\\"}\"}}]}"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val repository = KtorChatRepository(
            httpClient = httpClient,
            deepSeekKey = "key",
            yandexKey = "key",
            yandexFolderId = "id",
            openRouterKey = "key"
        )

        val result = repository.extractFacts(
            messages = listOf(ChatMessage(message = "My name is Alice", source = SourceType.USER)),
            currentFacts = ChatFacts(emptyMap()),
            provider = LLMProvider.DeepSeek("deepseek-chat")
        )

        assertTrue(result.isSuccess, "Result should be success. Error: ${result.exceptionOrNull()?.message}")
        assertEquals("Alice", result.getOrNull()?.facts?.get("name"))
    }
}
