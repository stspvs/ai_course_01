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
import kotlin.test.assertTrue

class OllamaModelsClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listModelNames_parsesNamesAndDeduplicates() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """
                    {"models":[
                      {"name":"a:latest","model":null},
                      {"name":"","model":"b"},
                      {"name":"a:latest","model":null}
                    ]}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaModelsClient(http, json, baseUrl = "http://127.0.0.1:11434")
        val r = sut.listModelNames().getOrThrow()
        assertEquals(listOf("a:latest", "b"), r)
    }

    @Test
    fun listModelNames_emptyModels_ok() = runTest {
        val mockEngine = MockEngine {
            respond(
                content = """{"models":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaModelsClient(http, json, baseUrl = "http://localhost:11434")
        assertTrue(sut.listModelNames().getOrThrow().isEmpty())
    }

    @Test
    fun listModelNames_trimsBaseUrlSlash() = runTest {
        var capturedPath = ""
        val mockEngine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = """{"models":[{"name":"x"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(json) }
        }
        val sut = OllamaModelsClient(http, json, baseUrl = "http://127.0.0.1:11434/")
        sut.listModelNames().getOrThrow()
        assertTrue(capturedPath.endsWith("/api/tags"))
    }
}
