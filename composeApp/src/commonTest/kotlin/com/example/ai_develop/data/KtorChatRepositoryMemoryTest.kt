package com.example.ai_develop.data

import com.example.ai_develop.domain.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtorChatRepositoryMemoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `extractFacts should parse JSON even with markdown blocks`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "```json\n[\"Факт 1\", \"Факт 2\"]\n```"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine)

        val repository = KtorChatRepository(client, "key", "key", "id", "key", "http://127.0.0.1:11434")
        val result = repository.extractFacts(emptyList(), ChatFacts(), LLMProvider.DeepSeek())

        assertTrue(result.isSuccess, "Result should be success: ${result.exceptionOrNull()?.message}")
        assertEquals(listOf("Факт 1", "Факт 2"), result.getOrNull()?.facts)
    }

    @Test
    fun `analyzeWorkingMemory should parse currentTask and progress`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "{\"currentTask\": \"Тестовая задача\", \"progress\": \"50%\"}"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine)

        val repository = KtorChatRepository(client, "key", "key", "id", "key", "http://127.0.0.1:11434")
        val result = repository.analyzeWorkingMemory(emptyList(), "instruction", LLMProvider.DeepSeek())

        assertTrue(result.isSuccess, "Result should be success: ${result.exceptionOrNull()?.message}")
        assertEquals("Тестовая задача", result.getOrNull()?.currentTask)
        assertEquals("50%", result.getOrNull()?.progress)
    }
}
