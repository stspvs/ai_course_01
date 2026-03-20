package com.example.ai_develop.data

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    @SerializedName("model")
    val model: String = "deepseek-chat",
    @SerializedName("messages")
    val messages: List<Message>,
    @SerializedName("max_tokens")
    val maxTokens: Int = 300,
    @SerializedName("stream")
    val stream: Boolean = true,
    @SerializedName("response_format")
    val responseFormat: ResponseFormat? = null,
    @SerializedName("stop")
    val stop: List<String>? = null
)

data class ResponseFormat(
    @SerializedName("type")
    val type: String // "text" or "json_object"
)

data class Message(
    @SerializedName("role")
    val role: String,
    @SerializedName("content")
    val content: String
)

data class ChatResponse(
    @SerializedName("choices")
    val choices: List<Choice>
)

data class Choice(
    @SerializedName("message")
    val message: Message
)

data class ChatStreamResponse(
    @SerializedName("choices")
    val choices: List<ChatStreamChoice>
)

data class ChatStreamChoice(
    @SerializedName("delta")
    val delta: ChatStreamDelta
)

data class ChatStreamDelta(
    @SerializedName("content")
    val content: String?
)
