package com.example.ai_develop.data

import com.example.ai_develop.domain.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
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
            val url: String
            val headers = mutableMapOf<String, String>()
            val bodyString: String
            val platform = getPlatform()

            when (provider) {
                is LLMProvider.DeepSeek -> {
                    url = "https://api.deepseek.com/v1/chat/completions"
                    headers["Authorization"] = "Bearer $deepSeekKey"
                    val apiMessages = mutableListOf<Message>()
                    if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
                    messages.forEach { msg -> apiMessages.add(Message(role = msg.source.role, content = msg.message)) }
                    bodyString = json.encodeToString(ChatRequest(
                        model = provider.model,
                        messages = apiMessages,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        stream = true,
                        responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
                        stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
                    ))
                }
                is LLMProvider.Yandex -> {
                    val baseUrl = if (platform.isWeb) "/yandex-api" else "https://llm.api.cloud.yandex.net"
                    url = "$baseUrl/foundationModels/v1/completion"
                    
                    headers["Authorization"] = "Api-Key $yandexKey"
                    headers["x-folder-id"] = yandexFolderId
                    val yandexMessages = mutableListOf<YandexMessage>()
                    if (systemPrompt.isNotBlank()) yandexMessages.add(YandexMessage(role = "system", text = systemPrompt))
                    messages.forEach { msg -> yandexMessages.add(YandexMessage(role = msg.source.role, text = msg.message)) }
                    bodyString = json.encodeToString(YandexChatRequest(
                        modelUri = "gpt://$yandexFolderId/${provider.model}",
                        completionOptions = YandexCompletionOptions(
                            stream = true,
                            temperature = temperature,
                            maxTokens = maxTokens
                        ),
                        messages = yandexMessages
                    ))
                }
                is LLMProvider.OpenRouter -> {
                    url = "https://openrouter.ai/api/v1/chat/completions"
                    headers["Authorization"] = "Bearer $openRouterKey"
                    headers["HTTP-Referer"] = "https://github.com/sts-dev/ai_develop"
                    headers["X-Title"] = "AI Develop KMP"
                    val apiMessages = mutableListOf<Message>()
                    if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
                    messages.forEach { msg -> apiMessages.add(Message(role = msg.source.role, content = msg.message)) }
                    bodyString = json.encodeToString(ChatRequest(
                        model = provider.model,
                        messages = apiMessages,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        stream = true,
                        responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
                        stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
                    ))
                }
            }

            httpClient.preparePost(url) {
                headers.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyString)
            }.execute { response ->
                if (response.status.isSuccess()) {
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    var lastFullText = ""
                    
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue

                        try {
                            if (provider is LLMProvider.Yandex) {
                                val chunk = try {
                                    json.decodeFromString<YandexStreamResponse>(trimmedLine)
                                } catch (e: Exception) {
                                    val resp = json.decodeFromString<YandexResponse>(trimmedLine)
                                    YandexStreamResponse(result = resp.result ?: YandexResult())
                                }

                                val currentFullText = chunk.result.alternatives?.firstOrNull()?.let { 
                                    it.message?.text ?: it.text 
                                } ?: ""
                                
                                if (currentFullText.isNotEmpty()) {
                                    if (currentFullText.length > lastFullText.length) {
                                        val delta = currentFullText.substring(lastFullText.length)
                                        lastFullText = currentFullText
                                        emit(Result.success(delta))
                                    } else if (lastFullText.isEmpty()) {
                                         lastFullText = currentFullText
                                         emit(Result.success(currentFullText))
                                    }
                                }
                            } else {
                                if (trimmedLine.startsWith("data:")) {
                                    val data = trimmedLine.substringAfter("data:").trim()
                                    if (data == "[DONE]") break
                                    
                                    val chunk = json.decodeFromString<ChatStreamResponse>(data)
                                    val delta = chunk.choices?.firstOrNull()?.delta
                                    
                                    val content = delta?.content 
                                        ?: delta?.reasoningContent 
                                        ?: delta?.reasoning
                                        
                                    if (content != null) {
                                        emit(Result.success(content))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                } else {
                    val errorBody = response.bodyAsText()
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
            val prompt = """
                Analyze the dialogue and update the key-value facts. 
                Keep track of: goals, constraints, preferences, decisions, user names/info.
                
                Current facts: 
                ${if (currentFacts.facts.isEmpty()) "No facts yet." else json.encodeToString(currentFacts.facts)}
                
                New messages for analysis:
                ${messages.joinToString("\n") { "${it.source.role}: ${it.message}" }}
                
                Instructions:
                1. Review current facts and new messages.
                2. If a new fact is discovered, add it.
                3. If a current fact is updated or corrected, modify it.
                4. Do NOT delete any current facts unless they are explicitly contradicted or became obsolete.
                5. Return the FINAL COMPLETE set of all facts (old and new).
                6. Output MUST be a valid JSON object where keys and values are strings.
                
                Example output: {"user_name": "Ivan", "goal": "learn kotlin", "language": "Russian"}
            """.trimIndent()

            val apiMessages = listOf(
                Message(role = "system", content = "You are a factual memory assistant. Output ONLY valid JSON."),
                Message(role = "user", content = prompt)
            )

            val response: HttpResponse = when (provider) {
                is LLMProvider.Yandex -> {
                    val baseUrl = if (getPlatform().isWeb) "/yandex-api" else "https://llm.api.cloud.yandex.net"
                    httpClient.post("$baseUrl/foundationModels/v1/completion") {
                        header("Authorization", "Api-Key $yandexKey")
                        header("x-folder-id", yandexFolderId)
                        contentType(ContentType.Application.Json)
                        val request = YandexChatRequest(
                            modelUri = "gpt://$yandexFolderId/${provider.model}",
                            completionOptions = YandexCompletionOptions(stream = false),
                            messages = apiMessages.map { YandexMessage(role = it.role, text = it.content) }
                        )
                        setBody(json.encodeToString(request))
                    }
                }
                else -> {
                    val targetUrl = if (provider is LLMProvider.DeepSeek) "https://api.deepseek.com/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions"
                    val key = if (provider is LLMProvider.DeepSeek) deepSeekKey else openRouterKey
                    httpClient.post(targetUrl) {
                        header("Authorization", "Bearer $key")
                        contentType(ContentType.Application.Json)
                        val request = ChatRequest(
                            model = provider.model,
                            messages = apiMessages,
                            stream = false,
                            responseFormat = ResponseFormat("json_object")
                        )
                        setBody(json.encodeToString(request))
                    }
                }
            }

            if (response.status.isSuccess()) {
                val responseText = response.bodyAsText()
                val text = if (provider is LLMProvider.Yandex) {
                    val resp = json.decodeFromString<YandexResponse>(responseText)
                    resp.result?.alternatives?.firstOrNull()?.message?.text ?: ""
                } else {
                    // Check if it's a stream despite stream=false
                    if (responseText.trim().startsWith("data:")) {
                        val contentBuilder = StringBuilder()
                        responseText.split("\n").forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("data:")) {
                                val data = trimmed.substringAfter("data:").trim()
                                if (data != "[DONE]" && data.isNotEmpty()) {
                                    try {
                                        val chunk = json.decodeFromString<ChatStreamResponse>(data)
                                        val content = chunk.choices?.firstOrNull()?.delta?.content 
                                            ?: chunk.choices?.firstOrNull()?.message?.content
                                        content?.let { contentBuilder.append(it) }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                        contentBuilder.toString()
                    } else {
                        val resp = json.decodeFromString<ChatResponse>(responseText)
                        resp.choices.firstOrNull()?.message?.content ?: ""
                    }
                }
                
                // More robust JSON extraction
                val startIndex = text.indexOf("{")
                val endIndex = text.lastIndexOf("}")
                
                if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                    val jsonString = text.substring(startIndex, endIndex + 1)
                    val newFactsMap = json.decodeFromString<Map<String, String>>(jsonString)
                    Result.success(ChatFacts(newFactsMap))
                } else {
                    Result.failure(Exception("No JSON object found in response: $text"))
                }
            } else {
                val errorBody = response.bodyAsText()
                Result.failure(Exception("Facts extraction failed: ${response.status}. Body: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
