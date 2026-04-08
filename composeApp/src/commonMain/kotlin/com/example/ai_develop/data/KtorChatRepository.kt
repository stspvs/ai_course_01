package com.example.ai_develop.data

import com.example.ai_develop.domain.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*

class KtorChatRepository(
    private val httpClient: HttpClient,
    private val deepSeekKey: String,
    private val yandexKey: String,
    private val yandexFolderId: String,
    private val openRouterKey: String
) : ChatRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun getHandler(provider: LLMProvider): LLMHandler {
        return when (provider) {
            is LLMProvider.DeepSeek -> DeepSeekHandler(deepSeekKey, provider, json)
            is LLMProvider.Yandex -> YandexHandler(yandexKey, yandexFolderId, provider, json)
            is LLMProvider.OpenRouter -> OpenRouterHandler(openRouterKey, provider, json)
        }
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        provider: LLMProvider
    ): Flow<Result<String>> = flow {
        try {
            val handler = getHandler(provider)
            val platform = getPlatform()
            val url = handler.buildUrl(platform)
            val bodyString = handler.buildChatRequestBody(
                messages, systemPrompt, maxTokens, temperature, stopWord, isJsonMode, stream = true
            )

            httpClient.preparePost(url) {
                handler.buildHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }.execute { response ->
                if (response.status.isSuccess()) {
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    val context = StreamContext()
                    
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        when (val result = handler.parseStreamChunk(line, context)) {
                            is StreamChunkResult.Content -> emit(Result.success(result.delta))
                            is StreamChunkResult.Done -> break
                            is StreamChunkResult.Ignore -> continue
                        }
                    }
                } else {
                    val errorBody = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                    emit(Result.failure(Exception("HTTP ${response.status}: $errorBody")))
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun extractFacts(
        messages: List<ChatMessage>,
        currentFacts: ChatFacts,
        provider: LLMProvider
    ): Result<ChatFacts> {
        return try {
            val prompt = PromptBuilder.buildFactsExtractionPrompt(currentFacts, messages, json)

            val factMessages = listOf(
                ChatMessage(message = "You are a factual memory assistant. Output ONLY valid JSON array of strings.", role = "system", source = SourceType.SYSTEM),
                ChatMessage(message = prompt, role = "user", source = SourceType.USER)
            )

            val handler = getHandler(provider)
            val platform = getPlatform()
            val url = handler.buildUrl(platform)
            val bodyString = handler.buildChatRequestBody(
                messages = factMessages,
                systemPrompt = "",
                maxTokens = 2000,
                temperature = 0.3,
                stopWord = "",
                isJsonMode = true,
                stream = false
            )

            val response = httpClient.post(url) {
                handler.buildHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                val text = cleanJsonResponse(handler.parseFullResponse(responseText))
                
                val start = text.indexOf("[")
                val end = text.lastIndexOf("]")
                
                if (start != -1 && end != -1 && end > start) {
                    val jsonString = text.substring(start, end + 1)
                    val newFactsList = json.decodeFromString<List<String>>(jsonString)
                    Result.success(ChatFacts(newFactsList))
                } else if (text.contains("{") && text.contains("}")) {
                    // Fail-safe for object response
                    Result.success(currentFacts)
                } else {
                    Result.failure(Exception("No JSON array found in response: $text"))
                }
            } else {
                val errorBody = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                Result.failure(Exception("Facts extraction failed: ${response.status}. Body: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun summarize(
        messages: List<ChatMessage>,
        previousSummary: String?,
        instruction: String,
        provider: LLMProvider
    ): Result<String> {
        return try {
            val prompt = PromptBuilder.buildSummarizationPrompt(previousSummary, messages, instruction)

            val summarizationMessages = listOf(
                ChatMessage(message = "You are a helpful assistant that summarizes conversations.", role = "system", source = SourceType.SYSTEM),
                ChatMessage(message = prompt, role = "user", source = SourceType.USER)
            )

            val handler = getHandler(provider)
            val platform = getPlatform()
            val url = handler.buildUrl(platform)
            val bodyString = handler.buildChatRequestBody(
                messages = summarizationMessages,
                systemPrompt = "",
                maxTokens = 1000,
                temperature = 0.5,
                stopWord = "",
                isJsonMode = false,
                stream = false
            )

            val response = httpClient.post(url) {
                handler.buildHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                val text = handler.parseFullResponse(responseText)
                Result.success(text.trim())
            } else {
                val errorBody = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                Result.failure(Exception("Summarization failed: ${response.status}. Body: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun analyzeTask(
        messages: List<ChatMessage>,
        instruction: String,
        provider: LLMProvider
    ): Result<TaskAnalysisResult> {
        return try {
            val history = messages.joinToString("\n") { "${it.role}: ${it.message}" }
            val fullPrompt = "$instruction\n\nCONVERSATION HISTORY:\n$history"

            val analysisMessages = listOf(
                ChatMessage(message = "You are a task analysis assistant. Output ONLY valid JSON object.", role = "system", source = SourceType.SYSTEM),
                ChatMessage(message = fullPrompt, role = "user", source = SourceType.USER)
            )

            val handler = getHandler(provider)
            val platform = getPlatform()
            val url = handler.buildUrl(platform)
            val bodyString = handler.buildChatRequestBody(
                messages = analysisMessages,
                systemPrompt = "",
                maxTokens = 1500,
                temperature = 0.3,
                stopWord = "",
                isJsonMode = true,
                stream = false
            )

            val response = httpClient.post(url) {
                handler.buildHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                val text = cleanJsonResponse(handler.parseFullResponse(responseText))
                
                val start = text.indexOf("{")
                val end = text.lastIndexOf("}")
                
                if (start != -1 && end != -1 && end > start) {
                    val jsonString = text.substring(start, end + 1)
                    val result = json.decodeFromString<TaskAnalysisResult>(jsonString)
                    Result.success(result)
                } else {
                    Result.failure(Exception("No JSON object found in response: $text"))
                }
            } else {
                val errorBody = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                Result.failure(Exception("Task analysis failed: ${response.status}. Body: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun analyzeWorkingMemory(
        messages: List<ChatMessage>,
        instruction: String,
        provider: LLMProvider
    ): Result<WorkingMemoryAnalysis> {
        return try {
            val history = messages.takeLast(15).joinToString("\n") { "${it.role}: ${it.message}" }
            val fullPrompt = "$instruction\n\nCONVERSATION HISTORY:\n$history"

            val analysisMessages = listOf(
                ChatMessage(message = "You are a memory assistant. Output ONLY valid JSON object.", role = "system", source = SourceType.SYSTEM),
                ChatMessage(message = fullPrompt, role = "user", source = SourceType.USER)
            )

            val handler = getHandler(provider)
            val platform = getPlatform()
            val url = handler.buildUrl(platform)
            val bodyString = handler.buildChatRequestBody(
                messages = analysisMessages,
                systemPrompt = "",
                maxTokens = 1000,
                temperature = 0.3,
                stopWord = "",
                isJsonMode = true,
                stream = false
            )

            val response = httpClient.post(url) {
                handler.buildHeaders().forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                val text = cleanJsonResponse(handler.parseFullResponse(responseText))
                
                val start = text.indexOf("{")
                val end = text.lastIndexOf("}")
                
                if (start != -1 && end != -1 && end > start) {
                    val jsonString = text.substring(start, end + 1)
                    val result = json.decodeFromString<WorkingMemoryAnalysis>(jsonString)
                    Result.success(result)
                } else {
                    Result.failure(Exception("No JSON object found in response: $text"))
                }
            } else {
                val errorBody = response.bodyAsText(fallbackCharset = Charsets.UTF_8)
                Result.failure(Exception("Memory analysis failed: ${response.status}. Body: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanJsonResponse(text: String): String {
        var cleaned = text.trim()
        if (cleaned.startsWith("```")) {
            // Remove ```json or just ```
            cleaned = cleaned.removePrefix("```json").removePrefix("```")
            // Remove trailing ```
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.removeSuffix("```")
            }
        }
        return cleaned.trim()
    }

    override suspend fun saveAgentState(state: AgentState) {}
    override suspend fun deleteAgent(agentId: String) {}
    override suspend fun saveProfile(agentId: String, profile: UserProfile) {}
    override suspend fun saveInvariant(invariant: Invariant) {}
    override suspend fun getAgentState(agentId: String): AgentState? = null
    override suspend fun getProfile(agentId: String): UserProfile? = null
    override suspend fun getInvariants(agentId: String, stage: AgentStage): List<Invariant> = emptyList()
    override fun observeAgentState(agentId: String): Flow<AgentState?> = flow { emit(null) }
}
