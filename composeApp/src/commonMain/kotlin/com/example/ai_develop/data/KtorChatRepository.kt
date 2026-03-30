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
            val body: Any
            val platform = getPlatform()

            when (provider) {
                is LLMProvider.DeepSeek -> {
                    url = "https://api.deepseek.com/v1/chat/completions"
                    headers["Authorization"] = "Bearer $deepSeekKey"
                    val apiMessages = mutableListOf<Message>()
                    if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
                    messages.forEach { msg -> apiMessages.add(Message(role = msg.source.role, content = msg.message)) }
                    body = ChatRequest(
                        model = provider.model,
                        messages = apiMessages,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        stream = true,
                        responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
                        stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
                    )
                }
                is LLMProvider.Yandex -> {
                    val baseUrl = if (platform.isWeb) "/yandex-api" else "https://llm.api.cloud.yandex.net"
                    url = "$baseUrl/foundationModels/v1/completion"
                    
                    headers["Authorization"] = "Api-Key $yandexKey"
                    headers["x-folder-id"] = yandexFolderId
                    val yandexMessages = mutableListOf<YandexMessage>()
                    if (systemPrompt.isNotBlank()) yandexMessages.add(YandexMessage(role = "system", text = systemPrompt))
                    messages.forEach { msg -> yandexMessages.add(YandexMessage(role = msg.source.role, text = msg.message)) }
                    body = YandexChatRequest(
                        modelUri = "gpt://$yandexFolderId/${provider.model}",
                        completionOptions = YandexCompletionOptions(
                            stream = true,
                            temperature = temperature,
                            maxTokens = maxTokens
                        ),
                        messages = yandexMessages
                    )
                }
                is LLMProvider.OpenRouter -> {
                    url = "https://openrouter.ai/api/v1/chat/completions"
                    headers["Authorization"] = "Bearer $openRouterKey"
                    headers["HTTP-Referer"] = "https://github.com/sts-dev/ai_develop"
                    headers["X-Title"] = "AI Develop KMP"
                    val apiMessages = mutableListOf<Message>()
                    if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
                    messages.forEach { msg -> apiMessages.add(Message(role = msg.source.role, content = msg.message)) }
                    body = ChatRequest(
                        model = provider.model,
                        messages = apiMessages,
                        maxTokens = maxTokens,
                        temperature = temperature,
                        stream = true,
                        responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
                        stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
                    )
                }
            }

            httpClient.preparePost(url) {
                headers.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(body)
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
                Keep track of: goals, constraints, preferences, decisions.
                Current facts: ${json.encodeToString(currentFacts.facts)}
                New messages: ${messages.joinToString("\n") { "${it.source.role}: ${it.message}" }}
                Return ONLY a JSON object with all current and new facts.
            """.trimIndent()

            val apiMessages = listOf(
                Message(role = "system", content = "You are a factual memory assistant. Output ONLY JSON."),
                Message(role = "user", content = prompt)
            )

            val response: HttpResponse = when (provider) {
                is LLMProvider.Yandex -> {
                    val baseUrl = if (getPlatform().isWeb) "/yandex-api" else "https://llm.api.cloud.yandex.net"
                    httpClient.post("$baseUrl/foundationModels/v1/completion") {
                        header("Authorization", "Api-Key $yandexKey")
                        header("x-folder-id", yandexFolderId)
                        setBody(YandexChatRequest(
                            modelUri = "gpt://$yandexFolderId/${provider.model}",
                            completionOptions = YandexCompletionOptions(stream = false),
                            messages = apiMessages.map { YandexMessage(role = it.role, text = it.content) }
                        ))
                    }
                }
                else -> {
                    val targetUrl = if (provider is LLMProvider.DeepSeek) "https://api.deepseek.com/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions"
                    val key = if (provider is LLMProvider.DeepSeek) deepSeekKey else openRouterKey
                    httpClient.post(targetUrl) {
                        header("Authorization", "Bearer $key")
                        setBody(ChatRequest(
                            model = provider.model,
                            messages = apiMessages,
                            responseFormat = ResponseFormat("json_object")
                        ))
                    }
                }
            }

            if (response.status.isSuccess()) {
                val text = if (provider is LLMProvider.Yandex) {
                    val resp = response.body<YandexResponse>()
                    resp.result?.alternatives?.firstOrNull()?.message?.text ?: ""
                } else {
                    val resp = response.body<ChatResponse>()
                    resp.choices.firstOrNull()?.message?.content ?: ""
                }
                
                val jsonString = text.substringAfter("{").substringBeforeLast("}") + "}"
                val newFactsMap = json.decodeFromString<Map<String, String>>(jsonString)
                Result.success(ChatFacts(newFactsMap))
            } else {
                Result.failure(Exception("Facts extraction failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
