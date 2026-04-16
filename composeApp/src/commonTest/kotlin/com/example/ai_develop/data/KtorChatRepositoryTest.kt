package com.example.ai_develop.data

import com.example.ai_develop.domain.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KtorChatRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val provider = LLMProvider.DeepSeek("deepseek-chat")
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    private fun createMockClient(
        response: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        contentType: ContentType = ContentType.Application.Json
    ): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(response),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType.toString())
            )
        }
        return HttpClient(mockEngine)
    }

    private fun createRepository(httpClient: HttpClient): KtorChatRepository {
        return KtorChatRepository(
            httpClient = httpClient,
            deepSeekKey = "test_key",
            yandexKey = "test_key",
            yandexFolderId = "test_id",
            openRouterKey = "test_key",
            ollamaBaseUrl = "http://127.0.0.1:11434"
        )
    }

    // --- 1. Unit Tests ---

    @Test
    fun chatStreaming_success() = runTest(testDispatcher) {
        // Given
        val chunks = listOf("Hello", " world", "!")
        val sseData = chunks.joinToString("\n") { 
            "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}" 
        } + "\ndata: [DONE]"
        
        val client = createMockClient(sseData, contentType = ContentType.Text.EventStream)
        val repository = createRepository(client)

        // When
        val flow = repository.chatStreaming(
            messages = emptyList(),
            systemPrompt = "Prompt",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = provider
        )
        val results = flow.toList()

        // Then
        assertEquals(3, results.size)
        assertEquals("Hello", results[0].getOrNull())
        assertEquals(" world", results[1].getOrNull())
        assertEquals("!", results[2].getOrNull())
        assertTrue(results.all { it.isSuccess })
    }

    @Test
    fun chatStreaming_httpError() = runTest(testDispatcher) {
        // Given
        val client = createMockClient("Internal Server Error", HttpStatusCode.InternalServerError)
        val repository = createRepository(client)

        // When
        val results = repository.chatStreaming(
            messages = emptyList(),
            systemPrompt = "Prompt",
            maxTokens = 100,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = provider
        ).toList()

        // Then
        assertTrue(results.any { it.isFailure })
        val exception = results.first { it.isFailure }.exceptionOrNull()
        assertTrue(exception?.message?.contains("500") == true)
    }

    @Test
    fun extractFacts_success() = runTest(testDispatcher) {
        // Given
        val facts = listOf("fact1", "fact2")
        val content = json.encodeToString(facts)
        val mockResponse = """
            {"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}
        """.trimIndent()
        
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.extractFacts(emptyList(), ChatFacts(), provider)

        // Then
        assertTrue(result.isSuccess, "Error: ${result.exceptionOrNull()}")
        assertEquals(facts, result.getOrNull()?.facts)
    }

    @Test
    fun extractFacts_invalidJson() = runTest(testDispatcher) {
        // Given
        val mockResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Not a JSON array\"}}]}"
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.extractFacts(emptyList(), ChatFacts(), provider)

        // Then
        assertTrue(result.isFailure)
    }

    @Test
    fun summarize_success() = runTest(testDispatcher) {
        // Given
        val mockResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Summary text\"}}]}"
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.summarize(emptyList(), null, "Instruct", provider)

        // Then
        assertEquals("Summary text", result.getOrNull())
    }

    @Test
    fun analyzeTask_success() = runTest(testDispatcher) {
        // Given
        val expected = TaskAnalysisResult(AgentStage.PLANNING, AgentPlan(listOf(AgentStep("1", "Step 1"))))
        val content = json.encodeToString(expected)
        val mockResponse = """
            {"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}
        """.trimIndent()
        
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.analyzeTask(emptyList(), "Instruct", provider)

        // Then
        assertTrue(result.isSuccess, "Error: ${result.exceptionOrNull()}")
        assertEquals(AgentStage.PLANNING, result.getOrNull()?.stage)
    }

    @Test
    fun analyzeWorkingMemory_success() = runTest(testDispatcher) {
        // Given
        val expected = WorkingMemoryAnalysis(currentTask = "Test Task", progress = "In progress")
        val content = json.encodeToString(expected)
        val mockResponse = """
            {"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}
        """.trimIndent()
        
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.analyzeWorkingMemory(emptyList(), "Instruct", provider)

        // Then
        assertTrue(result.isSuccess, "Error: ${result.exceptionOrNull()}")
        assertEquals("Test Task", result.getOrNull()?.currentTask)
    }

    // --- 2. Stress Tests ---

    @Test
    fun chatStreaming_stress() = runTest(testDispatcher) {
        // Given
        val sseData = "data: {\"choices\":[{\"delta\":{\"content\":\"chunk\"}}]}\ndata: [DONE]"
        val client = createMockClient(sseData, contentType = ContentType.Text.EventStream)
        val repository = createRepository(client)

        // When
        coroutineScope {
            val jobs = List(50) {
                async {
                    repository.chatStreaming(
                        messages = emptyList(),
                        systemPrompt = "Prompt",
                        maxTokens = 10,
                        temperature = 0.7,
                        stopWord = "",
                        isJsonMode = false,
                        provider = provider
                    ).toList()
                }
            }
            val allResults = jobs.awaitAll()
            
            // Then
            assertEquals(50, allResults.size)
            allResults.forEach { results ->
                assertTrue(results.any { it.isSuccess })
            }
        }
    }

    @Test
    fun extractFacts_stress() = runTest(testDispatcher) {
        val facts = listOf("fact")
        val content = json.encodeToString(facts)
        val mockResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":${Json.encodeToString(content)}}}]}"
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        coroutineScope {
            val jobs = List(100) {
                async { repository.extractFacts(emptyList(), ChatFacts(), provider) }
            }
            val results = jobs.awaitAll()
            assertEquals(100, results.size)
            assertTrue(results.all { it.isSuccess })
        }
    }

    // --- 3. Concurrency & Cancellation ---

    @Test
    fun chatStreaming_cancellation() = runTest(testDispatcher) {
        // Given
        val sseData = "data: {\"choices\":[{\"delta\":{\"content\":\"Slow...\"}}]}"
        val mockEngine = MockEngine {
            delay(1000) 
            respond(sseData, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val repository = createRepository(HttpClient(mockEngine))

        // When
        val job = launch {
            repository.chatStreaming(
                messages = emptyList(),
                systemPrompt = "Prompt",
                maxTokens = 10,
                temperature = 0.7,
                stopWord = "",
                isJsonMode = false,
                provider = provider
            ).collect { }
        }
        
        advanceUntilIdle() // Ensure it starts
        job.cancelAndJoin()

        // Then
        assertTrue(job.isCancelled)
    }

    // --- 4. Edge Cases ---

    @Test
    fun chatStreaming_timeout() = runTest(testDispatcher) {
        // Given
        val mockEngine = MockEngine { _ ->
            throw Exception("Timeout")
        }
        val repository = createRepository(HttpClient(mockEngine))

        // When
        val results = repository.chatStreaming(
            messages = emptyList(),
            systemPrompt = "Prompt",
            maxTokens = 10,
            temperature = 0.7,
            stopWord = "",
            isJsonMode = false,
            provider = provider
        ).toList()

        // Then
        assertTrue(results.any { it.isFailure })
        assertEquals("Timeout", results.first { it.isFailure }.exceptionOrNull()?.message)
    }

    @Test
    fun rewriteQueryForRag_ollama_success() = runTest(testDispatcher) {
        val mockResponse = """{"choices":[{"message":{"role":"assistant","content":"  поисковая строка  "}}]}"""
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        val result = repository.rewriteQueryForRag(
            userQuery = "что такое RAG",
            provider = LLMProvider.Ollama("qwen2.5:1.5b"),
        )

        assertTrue(result.isSuccess, "Error: ${result.exceptionOrNull()}")
        assertEquals("поисковая строка", result.getOrNull())
    }

    @Test
    fun rewriteQueryForRag_ollama_requestBodyContainsLanguagePrompt() = runTest(testDispatcher) {
        val mockResponse = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/v1/chat/completions"))
            val bodyJson = (request.body as TextContent).text
            val root = json.parseToJsonElement(bodyJson).jsonObject
            val messages = root["messages"]!!.jsonArray
            val systemContent = messages[0].jsonObject["content"]!!.jsonPrimitive.content
            assertEquals(
                ragQueryRewriteSystemPrompt,
                systemContent,
                "System message must be exactly ragQueryRewriteSystemPrompt (language rules)",
            )
            val userContent = messages[1].jsonObject["content"]!!.jsonPrimitive.content
            assertEquals("что такое RAG", userContent)
            respond(
                content = ByteReadChannel(mockResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(mockEngine)
        val repository = createRepository(client)

        val result = repository.rewriteQueryForRag(
            userQuery = "что такое RAG",
            provider = LLMProvider.Ollama("qwen2.5:1.5b"),
        )

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun largeData_test() = runTest(testDispatcher) {
        // Given
        val largeMessages = List(1000) { ChatMessage(message = "Message $it", role = "user", source = SourceType.USER) }
        val mockResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}"
        val client = createMockClient(mockResponse)
        val repository = createRepository(client)

        // When
        val result = repository.summarize(largeMessages, null, "Summarize", provider)

        // Then
        assertTrue(result.isSuccess)
    }
}
