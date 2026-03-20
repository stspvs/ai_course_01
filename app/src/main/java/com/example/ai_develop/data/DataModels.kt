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
    val stream: Boolean = true
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
