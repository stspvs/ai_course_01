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
                      "result": {
                        "alternatives": [{
                          "message": {
                            "text": "```json\n[\"Факт 1\", \"Факт 2\"]\n```"
                          }
                        }]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val repository = KtorChatRepository(client, "", "", "", "")
        val result = repository.extractFacts(emptyList(), ChatFacts(), LLMProvider.Yandex())

        assertTrue(result.isSuccess)
        assertEquals(listOf("Факт 1", "Факт 2"), result.getOrNull()?.facts)
    }

    @Test
    fun `analyzeWorkingMemory should parse currentTask and progress`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                      "result": {
                        "alternatives": [{
                          "message": {
                            "text": "{\"currentTask\": \"Тестовая задача\", \"progress\": \"50%\"}"
                          }
                        }]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val repository = KtorChatRepository(client, "", "", "", "")
        val result = repository.analyzeWorkingMemory(emptyList(), "instruction", LLMProvider.Yandex())

        assertTrue(result.isSuccess)
        assertEquals("Тестовая задача", result.getOrNull()?.currentTask)
        assertEquals("50%", result.getOrNull()?.progress)
    }
}
