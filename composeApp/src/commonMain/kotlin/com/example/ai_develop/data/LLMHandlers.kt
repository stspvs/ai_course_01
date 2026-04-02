package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatMessage
import com.example.ai_develop.domain.LLMProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed class StreamChunkResult {
    data class Content(val delta: String) : StreamChunkResult()
    object Done : StreamChunkResult()
    object Ignore : StreamChunkResult()
}

internal class StreamContext(var lastFullText: String = "")

internal interface LLMHandler {
    fun buildUrl(platform: Platform): String
    fun buildHeaders(): Map<String, String>
    fun buildChatRequestBody(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        stream: Boolean
    ): String

    fun parseStreamChunk(line: String, context: StreamContext): StreamChunkResult
    fun parseFullResponse(responseText: String): String
}

internal class DeepSeekHandler(
    private val apiKey: String,
    private val provider: LLMProvider.DeepSeek,
    private val json: Json
) : LLMHandler {
    override fun buildUrl(platform: Platform) = "https://api.deepseek.com/v1/chat/completions"

    override fun buildHeaders() = mapOf("Authorization" to "Bearer $apiKey")

    override fun buildChatRequestBody(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        stream: Boolean
    ): String {
        val apiMessages = mutableListOf<Message>()
        if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
        messages.forEach { msg -> apiMessages.add(Message(role = msg.role, content = msg.content)) }
        
        val validatedTemp = temperature.coerceIn(0.0, 2.0)

        return json.encodeToString(ChatRequest(
            model = provider.model,
            messages = apiMessages,
            maxTokens = maxTokens,
            temperature = validatedTemp,
            stream = stream,
            responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
            stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
        ))
    }

    override fun parseStreamChunk(line: String, context: StreamContext): StreamChunkResult {
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("data:")) {
            val data = trimmedLine.substringAfter("data:").trim()
            if (data == "[DONE]") return StreamChunkResult.Done
            
            return try {
                val chunk = json.decodeFromString<ChatStreamResponse>(data)
                val delta = chunk.choices?.firstOrNull()?.delta
                val content = delta?.content ?: delta?.reasoningContent ?: delta?.reasoning
                if (content != null) StreamChunkResult.Content(content) else StreamChunkResult.Ignore
            } catch (e: Exception) {
                StreamChunkResult.Ignore
            }
        }
        return StreamChunkResult.Ignore
    }

    override fun parseFullResponse(responseText: String): String {
        return if (responseText.trim().startsWith("data:")) {
            parseSseResponse(responseText)
        } else {
            val resp = json.decodeFromString<ChatResponse>(responseText)
            resp.choices.firstOrNull()?.message?.content ?: ""
        }
    }

    private fun parseSseResponse(responseText: String): String {
        val contentBuilder = StringBuilder()
        responseText.split("\n").forEach { line ->
            val result = parseStreamChunk(line, StreamContext())
            if (result is StreamChunkResult.Content) {
                contentBuilder.append(result.delta)
            }
        }
        return contentBuilder.toString()
    }
}

internal class YandexHandler(
    private val apiKey: String,
    private val folderId: String,
    private val provider: LLMProvider.Yandex,
    private val json: Json
) : LLMHandler {
    override fun buildUrl(platform: Platform): String {
        val baseUrl = if (platform.isWeb) "/yandex-api" else "https://llm.api.cloud.yandex.net"
        return "$baseUrl/foundationModels/v1/completion"
    }

    override fun buildHeaders() = mapOf(
        "Authorization" to "Api-Key $apiKey",
        "x-folder-id" to folderId
    )

    override fun buildChatRequestBody(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        stream: Boolean
    ): String {
        val yandexMessages = mutableListOf<YandexMessage>()
        if (systemPrompt.isNotBlank()) {
            yandexMessages.add(YandexMessage(role = "system", text = systemPrompt))
        }
        
        messages.forEach { msg ->
            if (msg.content.isNotBlank()) {
                yandexMessages.add(YandexMessage(role = msg.role, text = msg.content))
            }
        }
        
        if (yandexMessages.isEmpty()) {
            yandexMessages.add(YandexMessage(role = "user", text = "."))
        }

        val validatedTemp = temperature.coerceIn(0.0, 1.0)
        
        val modelName = provider.model.lowercase()
        val modelPath = if (modelName.contains("/") || modelName.startsWith("ds://") || modelName.startsWith("cls://")) {
            modelName
        } else {
            "$modelName/latest"
        }

        return json.encodeToString(YandexChatRequest(
            modelUri = "gpt://$folderId/$modelPath",
            completionOptions = YandexCompletionOptions(
                stream = stream,
                temperature = validatedTemp,
                maxTokens = maxTokens.toLong()
            ),
            messages = yandexMessages
        ))
    }

    override fun parseStreamChunk(line: String, context: StreamContext): StreamChunkResult {
        var trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) return StreamChunkResult.Ignore
        
        // Handle SSE data prefix if present
        if (trimmedLine.startsWith("data:")) {
            trimmedLine = trimmedLine.substringAfter("data:").trim()
        }
        if (trimmedLine == "[DONE]") return StreamChunkResult.Done

        return try {
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
                if (currentFullText.length > context.lastFullText.length) {
                    val delta = currentFullText.substring(context.lastFullText.length)
                    context.lastFullText = currentFullText
                    StreamChunkResult.Content(delta)
                } else if (context.lastFullText.isEmpty()) {
                     context.lastFullText = currentFullText
                     StreamChunkResult.Content(currentFullText)
                } else {
                    StreamChunkResult.Ignore
                }
            } else {
                StreamChunkResult.Ignore
            }
        } catch (e: Exception) {
            StreamChunkResult.Ignore
        }
    }

    override fun parseFullResponse(responseText: String): String {
        val resp = json.decodeFromString<YandexResponse>(responseText)
        return resp.result?.alternatives?.firstOrNull()?.message?.text ?: ""
    }
}

internal class OpenRouterHandler(
    private val apiKey: String,
    private val provider: LLMProvider.OpenRouter,
    private val json: Json
) : LLMHandler {
    override fun buildUrl(platform: Platform) = "https://openrouter.ai/api/v1/chat/completions"

    override fun buildHeaders() = mapOf(
        "Authorization" to "Bearer $apiKey",
        "HTTP-Referer" to "https://github.com/sts-dev/ai_develop",
        "X-Title" to "AI Develop KMP"
    )

    override fun buildChatRequestBody(
        messages: List<ChatMessage>,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Double,
        stopWord: String,
        isJsonMode: Boolean,
        stream: Boolean
    ): String {
        val apiMessages = mutableListOf<Message>()
        if (systemPrompt.isNotBlank()) apiMessages.add(Message(role = "system", content = systemPrompt))
        messages.forEach { msg -> apiMessages.add(Message(role = msg.role, content = msg.content)) }
        
        val maxTemp = if (provider.model.contains("deepseek", ignoreCase = true)) 2.0 else 1.0
        val validatedTemp = temperature.coerceIn(0.0, maxTemp)

        return json.encodeToString(ChatRequest(
            model = provider.model,
            messages = apiMessages,
            maxTokens = maxTokens,
            temperature = validatedTemp,
            stream = stream,
            responseFormat = if (isJsonMode) ResponseFormat("json_object") else null,
            stop = if (stopWord.isNotBlank()) listOf(stopWord) else null
        ))
    }

    override fun parseStreamChunk(line: String, context: StreamContext): StreamChunkResult {
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("data:")) {
            val data = trimmedLine.substringAfter("data:").trim()
            if (data == "[DONE]") return StreamChunkResult.Done
            
            return try {
                val chunk = json.decodeFromString<ChatStreamResponse>(data)
                val delta = chunk.choices?.firstOrNull()?.delta
                val content = delta?.content ?: delta?.reasoningContent ?: delta?.reasoning
                if (content != null) StreamChunkResult.Content(content) else StreamChunkResult.Ignore
            } catch (e: Exception) {
                StreamChunkResult.Ignore
            }
        }
        return StreamChunkResult.Ignore
    }

    override fun parseFullResponse(responseText: String): String {
        return if (responseText.trim().startsWith("data:")) {
            parseSseResponse(responseText)
        } else {
            val resp = json.decodeFromString<ChatResponse>(responseText)
            resp.choices.firstOrNull()?.message?.content ?: ""
        }
    }

    private fun parseSseResponse(responseText: String): String {
        val contentBuilder = StringBuilder()
        responseText.split("\n").forEach { line ->
            val result = parseStreamChunk(line, StreamContext())
            if (result is StreamChunkResult.Content) {
                contentBuilder.append(result.delta)
            }
        }
        return contentBuilder.toString()
    }
}
