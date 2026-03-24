package com.example.ai_develop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    @SerialName("model")
    val model: String,
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
    val modelUri: String,
    val completionOptions: YandexCompletionOptions,
    val messages: List<YandexMessage>
)

@Serializable
data class YandexCompletionOptions(
    val stream: Boolean = true,
    val temperature: Double = 0.6,
    val maxTokens: Int = 2000
)

@Serializable
data class YandexMessage(
    val role: String? = null,
    val text: String? = null
)

@Serializable
data class YandexResponse(
    val result: YandexResult? = null,
    val error: YandexError? = null
)

@Serializable
data class YandexResult(
    val alternatives: List<YandexAlternative>? = null
)

@Serializable
data class YandexAlternative(
    val message: YandexMessage? = null,
    val text: String? = null,
    val status: String? = null
)

@Serializable
data class YandexError(
    val message: String? = null,
    val grpcCode: Int? = null,
    val httpCode: Int? = null
)
