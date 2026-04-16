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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OllamaRagRerankClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun scoreRelevance_blankModel_returnsNullWithoutHttp() = runTest {
        val mockEngine = MockEngine {
            error("should not call network")
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertNull(sut.scoreRelevance("  ", "q", "chunk"))
    }

    @Test
    fun scoreRelevance_numericResponse_normalizedToZeroOne() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":"7"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertEquals(0.7f, sut.scoreRelevance("m", "q", "c")!!, 1e-5f)
    }

    @Test
    fun scoreRelevance_extractsFirstNumberFromNoise() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":"Score: 3.5 / 10"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertEquals(0.35f, sut.scoreRelevance("m", "q", "c")!!, 1e-5f)
    }

    @Test
    fun scoreRelevance_emptyResponse_returnsNull() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":""}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertNull(sut.scoreRelevance("m", "q", "c"))
    }

    @Test
    fun scoreRelevance_httpFailure_returnsNull() = runTest {
        val mockEngine = MockEngine {
            throw RuntimeException("boom")
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertNull(sut.scoreRelevance("m", "q", "c"))
    }

    @Test
    fun scoreRelevance_clampsAboveTen() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"response":"25"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaRagRerankClient(http, json, baseUrl = "http://127.0.0.1:11434")
        assertEquals(1f, sut.scoreRelevance("m", "q", "c")!!, 1e-5f)
    }
}
