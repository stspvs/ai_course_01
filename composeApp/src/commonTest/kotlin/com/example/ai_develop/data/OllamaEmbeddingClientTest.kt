package com.example.ai_develop.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaEmbeddingClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `embed parses embeddings array from Ollama`() = runTest {
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/api/embed"))
            respond(
                content = """
                    {
                      "model": "nomic-embed-text",
                      "embeddings": [[0.25, -0.5, 1.0]]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        val sut = OllamaEmbeddingClient(client, baseUrl = "http://127.0.0.1:11434")
        val out = sut.embed("nomic-embed-text", "hello")
        assertEquals(3, out.size)
        assertTrue(abs(EmbeddingNormalization.l2Norm(out) - 1f) < 1e-4f)
    }

    @Test
    fun `embed parses legacy single embedding field`() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"embedding": [0.1, 0.2]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        val sut = OllamaEmbeddingClient(client, baseUrl = "http://localhost:11434")
        val out = sut.embed("m", "x")
        assertEquals(2, out.size)
        assertTrue(abs(EmbeddingNormalization.l2Norm(out) - 1f) < 1e-4f)
    }
}
