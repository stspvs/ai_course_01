package com.example.ai_develop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    @SerialName("model")
    val model: String = "deepseek-chat",
    @SerialName("messages")
    val messages: List<Message>,
    @SerialName("max_tokens")
    val maxTokens: Int = 300,
    @SerialName("temperature")
    val temperature: Double = 1.0,
    @SerialName("stream")
    val stream: Boolean = true,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    @SerialName("stop")
    val stop: List<String>? = null
)

@Serializable
data class ResponseFormat(
    @SerialName("type")
    val type: String // "text" or "json_object"
)

@Serializable
data class Message(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String
)

@Serializable
data class ChatResponse(
    @SerialName("choices")
    val choices: List<Choice>
)

@Serializable
data class Choice(
    @SerialName("message")
    val message: Message
)

@Serializable
data class ChatStreamResponse(
    @SerialName("choices")
    val choices: List<ChatStreamChoice>? = null,
    @SerialName("error")
    val error: OpenAIError? = null
)

@Serializable
data class OpenAIError(
    val message: String? = null,
    val type: String? = null
)

@Serializable
data class ChatStreamChoice(
    @SerialName("delta")
    val delta: ChatStreamDelta? = null,
    @SerialName("message")
    val message: ChatStreamDelta? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChatStreamDelta(
    @SerialName("content")
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("reasoning")
    val reasoning: String? = null,
    @SerialName("role")
    val role: String? = null
)

// Yandex GPT Models
@Serializable
data class YandexChatRequest(
    @SerialName("modelUri")
    val modelUri: String,
    @SerialName("completionOptions")
    val completionOptions: YandexCompletionOptions,
    @SerialName("messages")
    val messages: List<YandexMessage>
)

@Serializable
data class YandexCompletionOptions(
    @SerialName("stream")
    val stream: Boolean = true,
    @SerialName("temperature")
    val temperature: Double = 0.6,
    @SerialName("maxTokens")
    val maxTokens: Long = 2000 // Using Long for safety with large numbers, though Int is usually fine
)

@Serializable
data class YandexMessage(
    @SerialName("role")
    val role: String,
    @SerialName("text")
    val text: String
)

@Serializable
data class YandexResponse(
    @SerialName("result")
    val result: YandexResult? = null,
    @SerialName("error")
    val error: YandexError? = null
)

@Serializable
data class YandexResult(
    @SerialName("alternatives")
    val alternatives: List<YandexAlternative>? = null
)

@Serializable
data class YandexAlternative(
    @SerialName("message")
    val message: YandexMessage? = null,
    @SerialName("text")
    val text: String? = null,
    @SerialName("status")
    val status: String? = null
)

@Serializable
data class YandexError(
    @SerialName("message")
    val message: String? = null,
    @SerialName("grpcCode")
    val grpcCode: Int? = null,
    @SerialName("httpCode")
    val httpCode: Int? = null
)

@Serializable
data class YandexStreamResponse(
    @SerialName("result")
    val result: YandexResult
)

// OpenAI-compatible models for Qwen/QWQ
@Serializable
data class OpenAiChatRequest(
    @SerialName("model")
    val model: String,
    @SerialName("messages")
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
    @SerialName("temperature")
    val temperature: Double,
    @SerialName("stream")
    val stream: Boolean = false
)

@Serializable
data class OpenAiMessage(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String
)
